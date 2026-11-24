package eu.wohlben.qits.agents.service.control;

import eu.wohlben.qits.agents.service.entity.AgentClaim;
import eu.wohlben.qits.agents.service.entity.AgentTurn;
import eu.wohlben.qits.agents.service.entity.DesiredState;
import eu.wohlben.qits.agents.service.entity.ProjectAgent;
import eu.wohlben.qits.agents.service.entity.TurnKind;
import eu.wohlben.qits.agents.service.events.EntityAssigned;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * <b>Claims from qits-projects' {@code EntityAssigned}</b> (design decision 5): the assignee of an
 * item moved onto an agent, off one, or both.
 *
 * <p>An agent of the event's project <em>matches</em> a value when it equals the agent's name or
 * its token subject (the alias an agent's own credential may write). When the previous assignee
 * matches agent A and the new one does not, A's open claim on the item is released. When the new
 * assignee matches a RUNNING agent B, B claims the item and — unless B assigned itself ({@code
 * changedBy} is B's subject) — an ASSIGNED turn is queued. A RETIRED agent gets neither.
 *
 * <p><b>A redelivered event changes nothing</b>, which the durable seam needs only for a retry after
 * a rollback, but which is cheap to make total: a claim is opened only when none is open AND none
 * was opened at this very instant (the event's own {@code assignedAt}, so a redelivery after a later
 * release does not reopen it); a turn is queued only with the claim it announces, and never twice
 * for one event ({@code agent_turn_source_uq}). Each is checked by a read first rather than left to
 * the unique index, whose violation would poison the claiming transaction.
 *
 * <p>Runs inside the durable consumption's transaction ({@code REQUIRED}), so the claim, the turn
 * and the consumer's claim on the event commit together.
 */
@ApplicationScoped
public class Assignments {

  private static final Logger LOG = Logger.getLogger(Assignments.class);

  @Inject Agents agents;

  /** Applies one assignment change. A payload that names no usable item is logged and ignored. */
  // Its own transaction on the agents datasource, never the listener's: qits-eventstream runs a
  // durable listener inside its claiming transaction on the eventstream outbox, and joining that
  // from a second non-XA datasource fails every event ("Exception in association of connection to
  // existing transaction", 2026.1124.52642). A redelivery after a failed claim commit is harmless:
  // the open-claim check and agent_turn_source_uq make the effect idempotent.
  @Transactional(Transactional.TxType.REQUIRES_NEW)
  public void assigned(EntityAssigned event) {
    UUID entityId = Ids.uuid(event.entityId());
    if (entityId == null || event.projectId() == null) {
      LOG.warnf(
          "EntityAssigned %s names no item or project (%s in %s); nothing to claim",
          event.eventId(), event.entityId(), event.projectId());
      return;
    }
    Instant at = Ids.micros(event.assignedAt());
    List<ProjectAgent> roster = agents.ofProject(event.projectId());
    for (ProjectAgent agent : roster) {
      boolean was = matches(agent, event.previousAssignee());
      boolean is = matches(agent, event.assignee());
      if (was && !is) {
        release(agent, entityId, at, event.changedBy());
      }
      if (is) {
        claim(agent, entityId, at, event);
      }
    }
  }

  /** Whether {@code value} names the agent: its name, or its token subject. */
  static boolean matches(ProjectAgent agent, String value) {
    if (value == null || value.isBlank()) {
      return false;
    }
    return value.equals(agent.name) || value.equals(agent.tokenSubject);
  }

  private void release(ProjectAgent agent, UUID entityId, Instant at, String by) {
    AgentClaim open =
        AgentClaim.<AgentClaim>find(
                "agentId = ?1 and entityId = ?2 and releasedAt is null", agent.id, entityId)
            .firstResult();
    if (open == null) {
      return;
    }
    open.releasedAt = at;
    open.releasedBy = by == null ? "unknown" : by;
    LOG.infof(
        "agent %s/%s released %s (%s)", agent.projectSlug, agent.name, open.qualifiedId, by);
  }

  private void claim(ProjectAgent agent, UUID entityId, Instant at, EntityAssigned event) {
    if (agent.desiredState != DesiredState.RUNNING) {
      return;
    }
    long already =
        AgentClaim.count(
            "agentId = ?1 and entityId = ?2 and (releasedAt is null or claimedAt = ?3)",
            agent.id,
            entityId,
            at);
    if (already > 0) {
      return;
    }
    String qualifiedId =
        event.qualifiedId() != null && !event.qualifiedId().isBlank()
            ? event.qualifiedId()
            : agent.projectSlug + "-" + event.number();
    AgentClaim claim = new AgentClaim();
    claim.id = UUID.randomUUID();
    claim.agentId = agent.id;
    claim.entityId = entityId;
    claim.qualifiedId = qualifiedId;
    claim.archetype = Objects.requireNonNullElse(event.archetype(), "UNKNOWN");
    claim.title = Objects.requireNonNullElse(event.title(), "");
    claim.claimedBy = Objects.requireNonNullElse(event.changedBy(), "unknown");
    claim.claimedAt = at;
    claim.persist();
    LOG.infof(
        "agent %s/%s claimed %s (%s)",
        agent.projectSlug, agent.name, qualifiedId, event.changedBy());
    if (event.changedBy() != null && event.changedBy().equals(agent.tokenSubject)) {
      return;
    }
    String who =
        event.changedByName() != null && !event.changedByName().isBlank()
            ? event.changedByName()
            : event.changedBy();
    Turns.queue(
        agent,
        TurnKind.ASSIGNED,
        "(" + who + " has assigned " + qualifiedId + " to you)",
        event.eventId());
  }

  /** Small shared helpers of the two event appliers. */
  static final class Ids {

    private Ids() {}

    static UUID uuid(String value) {
      if (value == null) {
        return null;
      }
      try {
        return UUID.fromString(value);
      } catch (IllegalArgumentException notOne) {
        return null;
      }
    }

    /** Microseconds, as postgres stores them, so a read-back equals what was written. */
    static Instant micros(Instant at) {
      return (at == null ? Instant.now() : at).truncatedTo(ChronoUnit.MICROS);
    }
  }

  /** Queues event-driven turns, once per agent and source event. */
  static final class Turns {

    private Turns() {}

    static void queue(ProjectAgent agent, TurnKind kind, String text, UUID sourceEventId) {
      if (sourceEventId != null
          && AgentTurn.count("agentId = ?1 and sourceEventId = ?2", agent.id, sourceEventId) > 0) {
        return;
      }
      AgentTurn turn = new AgentTurn();
      turn.id = UUID.randomUUID();
      turn.agentId = agent.id;
      turn.generation = agent.generation;
      turn.kind = kind;
      turn.text = text;
      turn.sourceEventId = sourceEventId;
      turn.createdAt = Ids.micros(Instant.now());
      turn.persist();
      LOG.infof("agent %s/%s: %s turn queued", agent.projectSlug, agent.name, kind);
    }
  }
}
