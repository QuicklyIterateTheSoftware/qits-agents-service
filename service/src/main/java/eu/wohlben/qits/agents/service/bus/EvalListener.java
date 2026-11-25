package eu.wohlben.qits.agents.service.bus;

import eu.wohlben.qits.agents.service.control.EvaluationLedger;
import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventFrame;
import jakarta.inject.Inject;
import java.util.Set;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * The shape every evaluation-ledger consumption shares (epic qits-2521): thin, as {@link
 * AssignmentListener} is — decode with {@code CanonicalJson.payloadTo}, hand to {@link
 * EvaluationLedger}, WARN and return on a payload that will not bind (poison), let a database
 * failure throw (the event stays owed).
 *
 * <p><b>Replayed from the epoch</b>, unlike the claim and turn consumptions: a new consumption pages
 * qits-events' log from its start, which is how the ledger is backfilled from {@code
 * qits.agents.evaluation.since} on; the ledger settles older events without a write. Its writes are
 * keyed by their source, so a replay after a rebuild gives the same ledger.
 */
abstract class EvalListener<E> implements QitsDurableEventListener {

  private static final Logger LOG = Logger.getLogger(EvalListener.class);

  @Inject EvaluationLedger ledger;

  private final String consumerId;

  private final String signature;

  private final Class<E> type;

  EvalListener(String consumerId, String signature, Class<E> type) {
    this.consumerId = consumerId;
    this.signature = signature;
    this.type = type;
  }

  @Override
  public String consumerId() {
    return consumerId;
  }

  @Override
  public Set<String> signatures() {
    return Set.of(signature);
  }

  /** Backfill: the window opens before this consumption first ran. */
  @Override
  public boolean replayFromEpoch() {
    return true;
  }

  @Override
  public void onFrame(EventFrame frame) {
    E event = decode(frame, type);
    if (event != null) {
      apply(event, frame);
    }
  }

  /** Hands the decoded event to the ledger. */
  abstract void apply(E event, EventFrame frame);

  /** The payload as {@code as}, or null (WARNed) for one that will not bind. */
  static <T> T decode(EventFrame frame, Class<T> as) {
    try {
      return CanonicalJson.payloadTo(frame.payload(), as);
    } catch (RuntimeException unreadable) {
      LOG.warnf(
          "%s %s carried an unreadable payload, settled unhandled: %s",
          frame.name(), frame.id(), unreadable.toString());
      return null;
    }
  }

  /** The event's id: the payload's, else the envelope's. */
  static UUID eventId(UUID payload, EventFrame frame) {
    if (payload != null) {
      return payload;
    }
    try {
      return UUID.fromString(frame.id());
    } catch (IllegalArgumentException | NullPointerException notOne) {
      return UUID.nameUUIDFromBytes(String.valueOf(frame.id()).getBytes());
    }
  }
}
