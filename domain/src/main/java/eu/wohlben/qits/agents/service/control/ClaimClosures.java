package eu.wohlben.qits.agents.service.control;

import eu.wohlben.qits.agents.service.entity.AgentClaim;
import eu.wohlben.qits.agents.service.entity.DesiredState;
import eu.wohlben.qits.agents.service.entity.EvalTransition;
import eu.wohlben.qits.agents.service.entity.ProjectAgent;
import eu.wohlben.qits.agents.service.events.EntityTransitioned;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * <b>Claims follow their items' status</b> (qits-2718): a claim is released when its item closes,
 * and given back when the item reopens. Before this, only an assignee move ({@link Assignments})
 * released a claim, and qits-projects keeps the assignee of a DONE or DROPPED item — so the claim,
 * and the Git refs it grants the agent's token ({@link AgentGitRefs}), stayed for good.
 *
 * <p><b>Closed</b> is {@link #CLOSED}: DONE, DROPPED, RESOLVED and OBSOLETE. VERIFIED is not: a
 * VERIFIED ticket's wrapper delivery is still running, and the agent still pushes its wrapper
 * branch and creates or joins its release request against the same refs; the platform sets DONE
 * exactly when that delivery has ended. VERIFIED can also step back to VERIFYING.
 *
 * <ul>
 *   <li><b>Close</b>: every open claim on the item, whatever the agent (the event names no
 *       assignee), opened before the closing instant, is released with {@code released_on} set to
 *       the status. A claim opened after it — an assignment made while the item was closed — is left.
 *   <li><b>Reopen</b>: when the item moves from a closed status to an open one and nobody holds it,
 *       the item's latest claim is given back as a new row, if a closure released it before the
 *       reopening instant and its agent is RUNNING. No turn is queued: whoever reopens dispatches
 *       the work. An assignee move off the agent while the item was closed clears {@code
 *       released_on} ({@link Assignments}), so that claim is never given back.
 * </ul>
 *
 * <p>Two callers: {@code bus/ClaimClosureListener} on each {@code EntityTransitioned} ({@link
 * #transitioned}), and the roster reconcile on every run ({@link #settle}), which applies the same
 * rules to the latest status the evaluation ledger holds for each claimed item ({@code
 * eval_transition}, EPIC and TICKET). The reconcile is the backfill for claims on items that were
 * closed before this existed, and the net for an event missed, unreadable or delivered out of
 * order. Both read the state before writing, so a redelivery or a second run changes nothing.
 */
@ApplicationScoped
public class ClaimClosures {

  private static final Logger LOG = Logger.getLogger(ClaimClosures.class);

  /** The statuses that close an item: the terminal states of qits-projects' state machine. */
  public static final Set<String> CLOSED = Set.of("DONE", "DROPPED", "RESOLVED", "OBSOLETE");

  /** Who the reconcile releases or gives back a claim as. */
  static final String RECONCILE = "qits-agents-reconcile";

  /** Whether {@code status} closes an item. */
  public static boolean closed(String status) {
    return status != null && CLOSED.contains(status);
  }

  /**
   * Applies one transition batch.
   *
   * @return the Git refs of every agent holding a token whose open claims this event moved, to be
   *     handed to qits-idp once this transaction has committed; empty when none moved
   */
  // REQUIRES_NEW for the reason Assignments.assigned is: a durable listener runs inside
  // qits-eventstream's claiming transaction on another datasource.
  @Transactional(Transactional.TxType.REQUIRES_NEW)
  public List<AgentGitRefs.Scope> transitioned(EntityTransitioned event, Instant occurredAt) {
    if (event.entities() == null) {
      return List.of();
    }
    Instant at = Assignments.Ids.micros(first(event.transitionedAt(), occurredAt));
    Set<UUID> moved = new LinkedHashSet<>();
    for (EntityTransitioned.Entity entity : event.entities()) {
      UUID entityId = Assignments.Ids.uuid(entity.entityId());
      if (entityId == null || entity.status() == null) {
        continue;
      }
      if (Objects.equals(entity.status(), entity.statusBefore())) {
        continue; // reshaped, not moved
      }
      String by = Objects.requireNonNullElse(entity.changedBy(), "unknown");
      if (closed(entity.status())) {
        moved.addAll(close(entityId, entity.status(), at, by));
      } else if (closed(entity.statusBefore())) {
        moved.addAll(reopen(entityId, at, by));
      }
    }
    return scopes(moved);
  }

  /**
   * Brings the claims of the project's agents in line with the latest status the evaluation ledger
   * holds for their items: the reconcile's sweep. Touches only items the ledger knows.
   *
   * @return how many claims it released or gave back
   */
  @Transactional(Transactional.TxType.REQUIRES_NEW)
  public int settle(UUID projectId) {
    List<UUID> agentIds =
        ProjectAgent.<ProjectAgent>list("projectId = ?1", projectId).stream()
            .map(agent -> agent.id)
            .toList();
    if (agentIds.isEmpty()) {
      return 0;
    }
    List<AgentClaim> claims =
        AgentClaim.list(
            "agentId in ?1 and (releasedAt is null or releasedOn is not null)", agentIds);
    Set<UUID> items = new LinkedHashSet<>();
    claims.forEach(claim -> items.add(claim.entityId));
    if (items.isEmpty()) {
      return 0;
    }
    Map<UUID, EvalTransition> latest = new HashMap<>();
    for (EvalTransition row :
        EvalTransition.<EvalTransition>list("workId in ?1 order by at, status", items)) {
      latest.put(row.workId, row); // ascending, so the last one written is the latest
    }
    int settled = 0;
    for (UUID item : items) {
      EvalTransition now = latest.get(item);
      if (now == null) {
        continue;
      }
      if (closed(now.status)) {
        settled += close(item, now.status, now.at, RECONCILE).size();
      } else {
        settled += reopen(item, now.at, RECONCILE).size();
      }
    }
    return settled;
  }

  /** Releases the open claims on the item opened before {@code at}; the agents whose claims moved. */
  private static List<UUID> close(UUID entityId, String status, Instant at, String by) {
    List<UUID> moved = new ArrayList<>();
    for (AgentClaim open :
        AgentClaim.<AgentClaim>list(
            "entityId = ?1 and releasedAt is null and claimedAt < ?2", entityId, at)) {
      open.releasedAt = at;
      open.releasedBy = by;
      open.releasedOn = status;
      moved.add(open.agentId);
      LOG.infof("agent %s released %s: it is %s (%s)", open.agentId, open.qualifiedId, status, by);
    }
    return moved;
  }

  /**
   * Gives the item's latest claim back when a closure released it before {@code at} and nobody
   * holds the item; the agent whose claim moved, or none.
   */
  private static List<UUID> reopen(UUID entityId, Instant at, String by) {
    if (AgentClaim.count("entityId = ?1 and releasedAt is null", entityId) > 0) {
      return List.of();
    }
    AgentClaim last =
        AgentClaim.<AgentClaim>find("entityId = ?1 order by claimedAt desc, id", entityId)
            .firstResult();
    if (last == null
        || last.releasedOn == null
        || last.releasedAt == null
        || !last.releasedAt.isBefore(at)) {
      return List.of();
    }
    ProjectAgent agent = ProjectAgent.findById(last.agentId);
    if (agent == null || agent.desiredState != DesiredState.RUNNING) {
      return List.of();
    }
    AgentClaim claim = new AgentClaim();
    claim.id = UUID.randomUUID();
    claim.agentId = last.agentId;
    claim.entityId = entityId;
    claim.qualifiedId = last.qualifiedId;
    claim.archetype = last.archetype;
    claim.title = last.title;
    claim.claimedBy = by;
    claim.claimedAt = at;
    claim.persist();
    LOG.infof(
        "agent %s/%s claims %s again: it reopened (%s)",
        agent.projectSlug, agent.name, last.qualifiedId, by);
    return List.of(agent.id);
  }

  /** The refs of each moved agent holding a token, as its open claims now grant them. */
  private static List<AgentGitRefs.Scope> scopes(Set<UUID> agentIds) {
    List<AgentGitRefs.Scope> scopes = new ArrayList<>();
    for (UUID agentId : agentIds) {
      ProjectAgent agent = ProjectAgent.findById(agentId);
      if (agent == null || agent.tokenId == null) {
        continue;
      }
      scopes.add(
          new AgentGitRefs.Scope(
              agent.id,
              agent.tokenId,
              AgentGitRefs.of(
                  AgentClaim.<AgentClaim>list("agentId = ?1 and releasedAt is null", agent.id))));
    }
    return scopes;
  }

  private static Instant first(Instant a, Instant b) {
    return a != null ? a : b;
  }
}
