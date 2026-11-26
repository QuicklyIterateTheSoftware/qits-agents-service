package eu.wohlben.qits.agents.service.bus;

import eu.wohlben.qits.agents.service.control.ClaimClosures;
import eu.wohlben.qits.agents.service.control.GitRefSync;
import eu.wohlben.qits.agents.service.events.EntityTransitioned;
import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventFrame;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * qits-projects' {@code EntityTransitioned}, consumed durably and applied by {@link ClaimClosures}
 * (qits-2718): a claim is released when its item closes, and given back when the item reopens.
 *
 * <p>Thin, as {@link AssignmentListener} is: decode, hand over, WARN and return on a payload that
 * will not bind, let a database failure throw. Once the claims have committed (their own
 * transaction), the Git refs of every agent whose claims moved are handed to qits-idp ({@link
 * GitRefSync}), which never throws.
 *
 * <p><b>Consumes from now</b>: the claims of items that closed before this consumption existed are
 * settled by the roster reconcile ({@link ClaimClosures#settle}), not by a replay.
 */
@ApplicationScoped
public class ClaimClosureListener implements QitsDurableEventListener {

  private static final Logger LOG = Logger.getLogger(ClaimClosureListener.class);

  /** The storage key, not a label (see {@link AssignmentListener#CONSUMER_ID}). */
  static final String CONSUMER_ID = "agents-claim-closures";

  static final String SIGNATURE = "EntityTransitioned";

  @Inject ClaimClosures closures;

  @Inject GitRefSync gitRefs;

  @Override
  public String consumerId() {
    return CONSUMER_ID;
  }

  @Override
  public Set<String> signatures() {
    return Set.of(SIGNATURE);
  }

  @Override
  public boolean replayFromEpoch() {
    return false;
  }

  @Override
  public void onFrame(EventFrame frame) {
    EntityTransitioned event;
    try {
      event = CanonicalJson.payloadTo(frame.payload(), EntityTransitioned.class);
    } catch (RuntimeException unreadable) {
      LOG.warnf(
          "%s %s carried an unreadable payload, settled unhandled: %s",
          frame.name(), frame.id(), unreadable.toString());
      return;
    }
    gitRefs.replace(closures.transitioned(event, frame.occurredAt()));
  }
}
