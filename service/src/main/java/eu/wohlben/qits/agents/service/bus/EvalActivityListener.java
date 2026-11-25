package eu.wohlben.qits.agents.service.bus;

import eu.wohlben.qits.agents.service.events.AgentActivityChanged;
import eu.wohlben.qits.eventstream.control.EventFrame;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * qits-workspaces' {@code AgentActivityChanged}: a workspace agent's busy and idle intervals. Consumed durably into the evaluation ledger (epic qits-2521), the way every {@link
 * EvalListener} is.
 */
@ApplicationScoped
public class EvalActivityListener extends EvalListener<AgentActivityChanged> {

  /** The storage key, not a label (see {@link AssignmentListener#CONSUMER_ID}). */
  static final String CONSUMER_ID = "eval-activity";

  static final String SIGNATURE = "AgentActivityChanged";

  public EvalActivityListener() {
    super(CONSUMER_ID, SIGNATURE, AgentActivityChanged.class);
  }

  @Override
  void apply(AgentActivityChanged event, EventFrame frame) {
    ledger.activity(eventId(event.eventId(), frame), event, frame.occurredAt());
  }
}
