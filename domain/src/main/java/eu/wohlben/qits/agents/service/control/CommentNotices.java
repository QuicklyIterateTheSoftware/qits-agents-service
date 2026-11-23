package eu.wohlben.qits.agents.service.control;

import eu.wohlben.qits.agents.service.entity.AgentClaim;
import eu.wohlben.qits.agents.service.entity.DesiredState;
import eu.wohlben.qits.agents.service.entity.ProjectAgent;
import eu.wohlben.qits.agents.service.entity.TurnKind;
import eu.wohlben.qits.agents.service.events.EntityCommented;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * <b>COMMENTED turns from qits-projects' {@code EntityCommented}</b> (design decision 5): a comment
 * anywhere under an item an agent holds open — its {@code rootEntityId}, the epic of a task or a
 * feature, else the item itself — tells that agent, unless the agent wrote it ({@code changedBy} is
 * its token subject). The text is qits-projects' {@code CommentTurns.text} format with the claim's
 * qualified id: {@code (<who> just commented on <qualifiedId>)}, where {@code who} is {@code
 * authorAgentOf + "'s agent"} for an agent's comment, else the author's name, else the principal.
 *
 * <p>A RETIRED agent's claims stay for the record but hear nothing. A redelivered event queues
 * nothing twice ({@code agent_turn_source_uq}, checked by a read first).
 */
@ApplicationScoped
public class CommentNotices {

  private static final Logger LOG = Logger.getLogger(CommentNotices.class);

  @Inject Agents agents;

  /** Applies one comment. A payload that names no usable item is logged and ignored. */
  @Transactional
  public void commented(EntityCommented event) {
    String root = event.rootEntityId() != null ? event.rootEntityId() : event.entityId();
    UUID rootId = Assignments.Ids.uuid(root);
    if (rootId == null) {
      LOG.warnf("EntityCommented %s names no item (%s); nobody to tell", event.eventId(), root);
      return;
    }
    List<AgentClaim> open = AgentClaim.list("entityId = ?1 and releasedAt is null", rootId);
    for (AgentClaim claim : open) {
      ProjectAgent agent = agents.byId(claim.agentId).orElse(null);
      if (agent == null || agent.desiredState != DesiredState.RUNNING) {
        continue;
      }
      if (event.changedBy() != null && event.changedBy().equals(agent.tokenSubject)) {
        continue;
      }
      Assignments.Turns.queue(
          agent,
          TurnKind.COMMENTED,
          "(" + who(event) + " just commented on " + claim.qualifiedId + ")",
          event.eventId());
    }
  }

  /** Who the comment is by, as a person reads it. */
  static String who(EntityCommented event) {
    if (present(event.authorAgentOf())) {
      return event.authorAgentOf() + "'s agent";
    }
    if (present(event.authorName())) {
      return event.authorName();
    }
    return event.author();
  }

  private static boolean present(String value) {
    return value != null && !value.isBlank();
  }
}
