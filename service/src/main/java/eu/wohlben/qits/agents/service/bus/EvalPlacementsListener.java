package eu.wohlben.qits.agents.service.bus;

import eu.wohlben.qits.agents.service.events.AgentPlaced;
import eu.wohlben.qits.eventstream.control.EventFrame;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * qits-workspaces' {@code AgentPlaced}: an item a workspace agent is placed on is the WORKSPACE
 * model's from then on. Consumed durably into the evaluation ledger (epic qits-2521), the way every {@link
 * EvalListener} is.
 */
@ApplicationScoped
public class EvalPlacementsListener extends EvalListener<AgentPlaced> {

  /** The storage key, not a label (see {@link AssignmentListener#CONSUMER_ID}). */
  static final String CONSUMER_ID = "eval-placements";

  static final String SIGNATURE = "AgentPlaced";

  public EvalPlacementsListener() {
    super(CONSUMER_ID, SIGNATURE, AgentPlaced.class);
  }

  @Override
  void apply(AgentPlaced event, EventFrame frame) {
    ledger.placed(event, frame.occurredAt());
  }
}
