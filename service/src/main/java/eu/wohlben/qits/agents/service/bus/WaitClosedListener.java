package eu.wohlben.qits.agents.service.bus;

import eu.wohlben.qits.agents.service.control.WaitNotices;
import eu.wohlben.qits.agents.service.events.ProjectAgentWaitClosed;
import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventFrame;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * qits-workspaces' {@code ProjectAgentWaitClosed} (qits-2597, epic qits-2520), consumed durably
 * and applied by {@link WaitNotices}: a project agent's declared wait closing becomes a WAIT turn
 * in its inbox. Thin, as {@link AssignmentListener} is, with the same poison rule.
 */
@ApplicationScoped
public class WaitClosedListener implements QitsDurableEventListener {

  private static final Logger LOG = Logger.getLogger(WaitClosedListener.class);

  /** The storage key, not a label (see {@link AssignmentListener#CONSUMER_ID}). */
  static final String CONSUMER_ID = "agents-waits";

  static final String SIGNATURE = "ProjectAgentWaitClosed";

  @Inject WaitNotices notices;

  @Override
  public String consumerId() {
    return CONSUMER_ID;
  }

  @Override
  public Set<String> signatures() {
    return Set.of(SIGNATURE);
  }

  /** Consume from now: a wait closed before the service ran tells nobody. */
  @Override
  public boolean replayFromEpoch() {
    return false;
  }

  @Override
  public void onFrame(EventFrame frame) {
    ProjectAgentWaitClosed event;
    try {
      event = CanonicalJson.payloadTo(frame.payload(), ProjectAgentWaitClosed.class);
    } catch (RuntimeException unreadable) {
      LOG.warnf(
          "%s %s carried an unreadable payload, settled unhandled: %s",
          frame.name(), frame.id(), unreadable.toString());
      return;
    }
    notices.waitClosed(event);
  }
}
