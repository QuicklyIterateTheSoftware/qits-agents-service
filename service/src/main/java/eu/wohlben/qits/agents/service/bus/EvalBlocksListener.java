package eu.wohlben.qits.agents.service.bus;

import eu.wohlben.qits.agents.service.events.EntityBlockChanged;
import eu.wohlben.qits.eventstream.control.EventFrame;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * qits-projects' {@code EntityBlockChanged}: every block set on an item. Consumed durably into the evaluation ledger (epic qits-2521), the way every {@link
 * EvalListener} is.
 */
@ApplicationScoped
public class EvalBlocksListener extends EvalListener<EntityBlockChanged> {

  /** The storage key, not a label (see {@link AssignmentListener#CONSUMER_ID}). */
  static final String CONSUMER_ID = "eval-blocks";

  static final String SIGNATURE = "EntityBlockChanged";

  public EvalBlocksListener() {
    super(CONSUMER_ID, SIGNATURE, EntityBlockChanged.class);
  }

  @Override
  void apply(EntityBlockChanged event, EventFrame frame) {
    ledger.blockChanged(eventId(event.eventId(), frame), event, frame.occurredAt());
  }
}
