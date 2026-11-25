package eu.wohlben.qits.agents.service.bus;

import eu.wohlben.qits.agents.service.events.BuildStatusChanged;
import eu.wohlben.qits.eventstream.control.EventFrame;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * qits-ci's {@code BuildStatusChanged}: each CI run's first and terminal event, and its request. Consumed durably into the evaluation ledger (epic qits-2521), the way every {@link
 * EvalListener} is.
 */
@ApplicationScoped
public class EvalBuildsListener extends EvalListener<BuildStatusChanged> {

  /** The storage key, not a label (see {@link AssignmentListener#CONSUMER_ID}). */
  static final String CONSUMER_ID = "eval-builds";

  static final String SIGNATURE = "BuildStatusChanged";

  public EvalBuildsListener() {
    super(CONSUMER_ID, SIGNATURE, BuildStatusChanged.class);
  }

  @Override
  void apply(BuildStatusChanged event, EventFrame frame) {
    ledger.buildStatusChanged(event, frame.occurredAt());
  }
}
