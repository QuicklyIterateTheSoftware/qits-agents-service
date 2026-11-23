package eu.wohlben.qits.agents.service.bus;

import eu.wohlben.qits.agents.service.control.CommentNotices;
import eu.wohlben.qits.agents.service.events.EntityCommented;
import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventFrame;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * qits-projects' {@code EntityCommented}, consumed durably and applied by {@link CommentNotices}: a
 * comment under an item an agent holds becomes a COMMENTED turn. Thin, as {@link
 * AssignmentListener} is, with the same poison rule.
 */
@ApplicationScoped
public class CommentListener implements QitsDurableEventListener {

  private static final Logger LOG = Logger.getLogger(CommentListener.class);

  /** The storage key, not a label (see {@link AssignmentListener#CONSUMER_ID}). */
  static final String CONSUMER_ID = "agents-comments";

  static final String SIGNATURE = "EntityCommented";

  @Inject CommentNotices notices;

  @Override
  public String consumerId() {
    return CONSUMER_ID;
  }

  @Override
  public Set<String> signatures() {
    return Set.of(SIGNATURE);
  }

  /** Consume from now: a comment from before the service ran tells nobody. */
  @Override
  public boolean replayFromEpoch() {
    return false;
  }

  @Override
  public void onFrame(EventFrame frame) {
    EntityCommented event;
    try {
      event = CanonicalJson.payloadTo(frame.payload(), EntityCommented.class);
    } catch (RuntimeException unreadable) {
      LOG.warnf(
          "%s %s carried an unreadable payload, settled unhandled: %s",
          frame.name(), frame.id(), unreadable.toString());
      return;
    }
    notices.commented(event);
  }
}
