package eu.wohlben.qits.agents.service.bus;

import eu.wohlben.qits.agents.service.events.ReleaseRequestStateChanged;
import eu.wohlben.qits.eventstream.control.EventFrame;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * qits-projects' {@code ReleaseRequestStateChanged}: a request's branches and the states it enters. Consumed durably into the evaluation ledger (epic qits-2521), the way every {@link
 * EvalListener} is.
 */
@ApplicationScoped
public class EvalRequestsListener extends EvalListener<ReleaseRequestStateChanged> {

  /** The storage key, not a label (see {@link AssignmentListener#CONSUMER_ID}). */
  static final String CONSUMER_ID = "eval-requests";

  static final String SIGNATURE = "ReleaseRequestStateChanged";

  public EvalRequestsListener() {
    super(CONSUMER_ID, SIGNATURE, ReleaseRequestStateChanged.class);
  }

  @Override
  void apply(ReleaseRequestStateChanged event, EventFrame frame) {
    ledger.requestChanged(event, frame.occurredAt());
  }
}
