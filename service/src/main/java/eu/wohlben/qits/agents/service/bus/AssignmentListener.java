package eu.wohlben.qits.agents.service.bus;

import eu.wohlben.qits.agents.service.control.Assignments;
import eu.wohlben.qits.agents.service.control.GitRefSync;
import eu.wohlben.qits.agents.service.events.EntityAssigned;
import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventFrame;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * qits-projects' {@code EntityAssigned}, consumed durably and applied by {@link Assignments}: the
 * claims an agent holds follow its items' assignee.
 *
 * <p>Thin on purpose: decode, hand over. A payload that will not bind is the poison case — the same
 * bytes would fail on every offer — so it is a WARN and a return, never a wedged watermark. A
 * database failure throws, rolls the claim back and leaves the event owed, which is right: a later
 * offer can do better.
 *
 * <p>Once {@link Assignments#assigned} has committed the claims (its own transaction), the Git refs
 * of every agent whose claims moved are handed to qits-idp ({@link GitRefSync}, qits-2705). That
 * never throws: a qits-idp failure is a WARN, the event is still consumed, and the next roster
 * reconcile states the list again.
 */
@ApplicationScoped
public class AssignmentListener implements QitsDurableEventListener {

  private static final Logger LOG = Logger.getLogger(AssignmentListener.class);

  /**
   * The storage key, not a label: it names this consumption's {@code consumed_event} rows and its
   * watermark, and must survive any rename of this class.
   */
  static final String CONSUMER_ID = "agents-assignments";

  static final String SIGNATURE = "EntityAssigned";

  @Inject Assignments assignments;

  @Inject GitRefSync gitRefs;

  @Override
  public String consumerId() {
    return CONSUMER_ID;
  }

  @Override
  public Set<String> signatures() {
    return Set.of(SIGNATURE);
  }

  /** Consume from now: no backfill (design decision 4). */
  @Override
  public boolean replayFromEpoch() {
    return false;
  }

  @Override
  public void onFrame(EventFrame frame) {
    EntityAssigned event;
    try {
      event = CanonicalJson.payloadTo(frame.payload(), EntityAssigned.class);
    } catch (RuntimeException unreadable) {
      LOG.warnf(
          "%s %s carried an unreadable payload, settled unhandled: %s",
          frame.name(), frame.id(), unreadable.toString());
      return;
    }
    gitRefs.replace(assignments.assigned(event));
  }
}
