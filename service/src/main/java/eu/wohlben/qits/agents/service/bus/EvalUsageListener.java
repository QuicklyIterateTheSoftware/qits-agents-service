package eu.wohlben.qits.agents.service.bus;

import eu.wohlben.qits.agents.service.events.AgentUsageRecorded;
import eu.wohlben.qits.eventstream.control.EventFrame;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * qits-workspaces' {@code AgentUsageRecorded}: a workspace agent's transcript usage. Consumed durably into the evaluation ledger (epic qits-2521), the way every {@link
 * EvalListener} is.
 */
@ApplicationScoped
public class EvalUsageListener extends EvalListener<AgentUsageRecorded> {

  /** The storage key, not a label (see {@link AssignmentListener#CONSUMER_ID}). */
  static final String CONSUMER_ID = "eval-usage";

  static final String SIGNATURE = "AgentUsageRecorded";

  public EvalUsageListener() {
    super(CONSUMER_ID, SIGNATURE, AgentUsageRecorded.class);
  }

  @Override
  void apply(AgentUsageRecorded event, EventFrame frame) {
    ledger.usageRecorded(eventId(event.eventId(), frame), event, frame.occurredAt());
  }
}
