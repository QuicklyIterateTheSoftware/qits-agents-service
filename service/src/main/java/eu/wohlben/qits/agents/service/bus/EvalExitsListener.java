package eu.wohlben.qits.agents.service.bus;

import eu.wohlben.qits.agents.service.events.AgentHarnessExited;
import eu.wohlben.qits.eventstream.control.EventFrame;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * qits-workspaces' {@code AgentHarnessExited}: a session that ended by itself or was killed. Consumed durably into the evaluation ledger (epic qits-2521), the way every {@link
 * EvalListener} is.
 */
@ApplicationScoped
public class EvalExitsListener extends EvalListener<AgentHarnessExited> {

  /** The storage key, not a label (see {@link AssignmentListener#CONSUMER_ID}). */
  static final String CONSUMER_ID = "eval-exits";

  static final String SIGNATURE = "AgentHarnessExited";

  public EvalExitsListener() {
    super(CONSUMER_ID, SIGNATURE, AgentHarnessExited.class);
  }

  @Override
  void apply(AgentHarnessExited event, EventFrame frame) {
    ledger.harnessExited(eventId(event.eventId(), frame), event, frame.occurredAt());
  }
}
