package eu.wohlben.qits.agents.service.bus;

import eu.wohlben.qits.agents.service.events.EntityTransitioned;
import eu.wohlben.qits.eventstream.control.EventFrame;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * qits-projects' {@code EntityTransitioned}: the statuses an EPIC or TICKET enters, and its identity. Consumed durably into the evaluation ledger (epic qits-2521), the way every {@link
 * EvalListener} is.
 */
@ApplicationScoped
public class EvalTransitionsListener extends EvalListener<EntityTransitioned> {

  /** The storage key, not a label (see {@link AssignmentListener#CONSUMER_ID}). */
  static final String CONSUMER_ID = "eval-transitions";

  static final String SIGNATURE = "EntityTransitioned";

  public EvalTransitionsListener() {
    super(CONSUMER_ID, SIGNATURE, EntityTransitioned.class);
  }

  @Override
  void apply(EntityTransitioned event, EventFrame frame) {
    ledger.transitioned(event, frame.occurredAt());
  }
}
