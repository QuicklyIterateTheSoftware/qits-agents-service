package eu.wohlben.qits.agents.service.control;

import eu.wohlben.qits.agents.service.entity.DesiredState;
import eu.wohlben.qits.agents.service.entity.ProjectAgent;
import eu.wohlben.qits.agents.service.entity.TurnKind;
import eu.wohlben.qits.agents.service.events.ProjectAgentWaitClosed;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

/**
 * <b>WAIT turns from qits-workspaces' {@code ProjectAgentWaitClosed}</b> (qits-2597, epic
 * qits-2520): a project agent's declared wait closing tells the agent, as a WAIT turn in its
 * inbox. {@code state == CANCELLED} tells nobody — the holder cancelled it itself. Otherwise the
 * agent whose {@code tokenSubject} equals the event's {@code agentSubject} is found; none of
 * them, or one that is RETIRED, hears nothing (logged at DEBUG). Every other agent gets exactly
 * one WAIT turn, in the text {@link #text} renders.
 *
 * <p>A redelivered event queues nothing twice ({@code agent_turn_source_uq}, checked by {@link
 * Assignments.Turns#queue} with a read first rather than left to the unique index).
 */
@ApplicationScoped
public class WaitNotices {

  private static final Logger LOG = Logger.getLogger(WaitNotices.class);

  @Inject Agents agents;

  /** Applies one wait closing. */
  // Its own transaction on the agents datasource, never the listener's: qits-eventstream runs a
  // durable listener inside its claiming transaction on the eventstream outbox, and joining that
  // from a second non-XA datasource fails every event ("Exception in association of connection to
  // existing transaction", 2026.1124.52642). A redelivery after a failed claim commit is harmless:
  // agent_turn_source_uq makes the effect idempotent.
  @Transactional(Transactional.TxType.REQUIRES_NEW)
  public void waitClosed(ProjectAgentWaitClosed event) {
    if (ProjectAgentWaitClosed.CANCELLED.equals(event.state())) {
      return;
    }
    ProjectAgent agent = agents.bySubject(event.agentSubject()).orElse(null);
    if (agent == null) {
      LOG.debugf(
          "ProjectAgentWaitClosed %s names no agent (%s); nobody to tell",
          event.eventId(), event.agentSubject());
      return;
    }
    if (agent.desiredState == DesiredState.RETIRED) {
      LOG.debugf(
          "ProjectAgentWaitClosed %s is for retired agent %s/%s; nothing to tell",
          event.eventId(), agent.projectSlug, agent.name);
      return;
    }
    String text = text(event);
    if (text == null) {
      LOG.warnf(
          "ProjectAgentWaitClosed %s carries an unknown state %s, settled unhandled",
          event.eventId(), event.state());
      return;
    }
    Assignments.Turns.queue(agent, TurnKind.WAIT, text, event.eventId());
  }

  /** The turn's text, by {@link ProjectAgentWaitClosed#state()}; null for a state this reads none of. */
  static String text(ProjectAgentWaitClosed event) {
    if (ProjectAgentWaitClosed.MATCHED.equals(event.state())) {
      return matched(event);
    }
    if (ProjectAgentWaitClosed.TIMED_OUT.equals(event.state())) {
      return timedOut(event);
    }
    if (ProjectAgentWaitClosed.BROKEN.equals(event.state())) {
      return "(wait \""
          + event.label()
          + "\" for "
          + event.workQualifiedId()
          + " broke: "
          + event.reason()
          + ")";
    }
    return null;
  }

  private static String matched(ProjectAgentWaitClosed event) {
    if (event.matchedEventId() != null) {
      return "(wait \""
          + event.label()
          + "\" for "
          + event.workQualifiedId()
          + " matched "
          + event.eventName()
          + " "
          + event.matchedEventId()
          + ")";
    }
    return "(wait \"" + event.label() + "\" for " + event.workQualifiedId() + " matched on lookup)";
  }

  private static String timedOut(ProjectAgentWaitClosed event) {
    if (event.eventName() == null) {
      return "(timer \"" + event.label() + "\" for " + event.workQualifiedId() + " is up)";
    }
    return "(wait \"" + event.label() + "\" for " + event.workQualifiedId() + " timed out)";
  }
}
