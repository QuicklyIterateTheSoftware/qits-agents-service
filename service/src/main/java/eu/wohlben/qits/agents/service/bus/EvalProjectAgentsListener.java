package eu.wohlben.qits.agents.service.bus;

import eu.wohlben.qits.agents.service.events.ProjectAgentChanged;
import eu.wohlben.qits.eventstream.control.EventFrame;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * This service's own {@code ProjectAgentChanged}: a project agent's busy, idle and down intervals.
 * Consumed off the bus rather than written beside the publish, so the window from {@code
 * qits.agents.evaluation.since} is replayed from the log the way every {@link EvalListener}'s is
 * (epic qits-2521).
 */
@ApplicationScoped
public class EvalProjectAgentsListener extends EvalListener<ProjectAgentChanged> {

  /** The storage key, not a label (see {@link AssignmentListener#CONSUMER_ID}). */
  static final String CONSUMER_ID = "eval-project-agents";

  static final String SIGNATURE = "ProjectAgentChanged";

  public EvalProjectAgentsListener() {
    super(CONSUMER_ID, SIGNATURE, ProjectAgentChanged.class);
  }

  @Override
  void apply(ProjectAgentChanged event, EventFrame frame) {
    ledger.projectAgentChanged(eventId(event.eventId(), frame), event, frame.occurredAt());
  }
}
