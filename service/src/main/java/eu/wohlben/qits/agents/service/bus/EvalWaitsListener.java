package eu.wohlben.qits.agents.service.bus;

import eu.wohlben.qits.agents.service.control.EvaluationLedger;
import eu.wohlben.qits.agents.service.events.AgentWaitClosed;
import eu.wohlben.qits.agents.service.events.ProjectAgentWaitClosed;
import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.control.EventFrame;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;

/**
 * qits-workspaces' two wait closings, {@code AgentWaitClosed} (WORKSPACE) and {@code
 * ProjectAgentWaitClosed} (PROJECT_AGENT), consumed durably into the evaluation ledger (epic
 * qits-2521) as one consumption: the library offers one listener both names. Thin and replayed from
 * the epoch, as every {@link EvalListener} is.
 */
@ApplicationScoped
public class EvalWaitsListener implements QitsDurableEventListener {

  /** The storage key, not a label (see {@link AssignmentListener#CONSUMER_ID}). */
  static final String CONSUMER_ID = "eval-waits";

  static final String WORKSPACE = "AgentWaitClosed";

  static final String PROJECT_AGENT = "ProjectAgentWaitClosed";

  @Inject EvaluationLedger ledger;

  @Override
  public String consumerId() {
    return CONSUMER_ID;
  }

  @Override
  public Set<String> signatures() {
    return Set.of(WORKSPACE, PROJECT_AGENT);
  }

  /** Backfill, as every evaluation consumption. */
  @Override
  public boolean replayFromEpoch() {
    return true;
  }

  @Override
  public void onFrame(EventFrame frame) {
    if (PROJECT_AGENT.equals(frame.name())) {
      ProjectAgentWaitClosed event = EvalListener.decode(frame, ProjectAgentWaitClosed.class);
      if (event != null) {
        ledger.waitClosed(event, frame.occurredAt());
      }
      return;
    }
    AgentWaitClosed event = EvalListener.decode(frame, AgentWaitClosed.class);
    if (event != null) {
      ledger.waitClosed(event, frame.occurredAt());
    }
  }
}
