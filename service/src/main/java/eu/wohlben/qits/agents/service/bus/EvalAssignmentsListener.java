package eu.wohlben.qits.agents.service.bus;

import eu.wohlben.qits.agents.service.events.EntityAssigned;
import eu.wohlben.qits.eventstream.control.EventFrame;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * qits-projects' {@code EntityAssigned}: an item assigned to a roster agent is the PROJECT_AGENT
 * model's from then on. Consumed durably into the evaluation ledger (epic qits-2521), the way every {@link
 * EvalListener} is.
 */
@ApplicationScoped
public class EvalAssignmentsListener extends EvalListener<EntityAssigned> {

  /** The storage key, not a label (see {@link AssignmentListener#CONSUMER_ID}). */
  static final String CONSUMER_ID = "eval-assignments";

  static final String SIGNATURE = "EntityAssigned";

  public EvalAssignmentsListener() {
    super(CONSUMER_ID, SIGNATURE, EntityAssigned.class);
  }

  @Override
  void apply(EntityAssigned event, EventFrame frame) {
    ledger.assigned(event);
  }
}
