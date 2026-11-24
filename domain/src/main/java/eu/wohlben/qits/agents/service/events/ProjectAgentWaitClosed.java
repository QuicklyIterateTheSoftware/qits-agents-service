package eu.wohlben.qits.agents.service.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;
import java.util.UUID;

/**
 * <b>Consumed, not published</b>: this service's copy of qits-workspaces' {@code
 * ProjectAgentWaitClosed} (qits-2597, epic qits-2520), read off the durable consumption {@code
 * agents-waits} ({@code bus/WaitClosedListener}) and applied by {@code control/WaitNotices}. A
 * project agent's declared wait closed — an event matched it, its subject was looked up in a
 * target state, its timeout passed, its subject can no longer reach what it waits for, or the
 * agent cancelled it — and this service renders the turn qits-workspaces does not.
 *
 * <p>Bound by name and lenient to fields this copy does not know. It is deliberately not a {@code
 * QitsEvent}: nothing here publishes it.
 *
 * @param agentSubject the holder's token principal (e.g. {@code
 *     tok-project-agent-qits-developer})
 * @param workId the item the wait was held for
 * @param workQualifiedId the item's qualified id
 * @param label the holder's own text, written by the agent and asked to be told
 * @param state {@link #MATCHED}, {@link #TIMED_OUT}, {@link #BROKEN} or {@link #CANCELLED}
 * @param eventName the event the wait selected; absent for a timer
 * @param matchedEventId the event that matched; absent unless an event did (a lookup names none)
 * @param reason the platform's one line on why it broke; absent unless BROKEN
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProjectAgentWaitClosed(
    UUID eventId,
    UUID waitId,
    String projectId,
    String agentSubject,
    String workId,
    String workQualifiedId,
    String label,
    String state,
    String eventName,
    String matchedEventId,
    String reason,
    Instant occurredAt) {

  /** An event matched the wait's selection, or a lookup found its subject in a target state. */
  public static final String MATCHED = "MATCHED";

  /** The wait's timeout (or a timer's time) passed first. */
  public static final String TIMED_OUT = "TIMED_OUT";

  /** The wait's subject can no longer reach what it waits for. */
  public static final String BROKEN = "BROKEN";

  /** The holder cancelled it. */
  public static final String CANCELLED = "CANCELLED";
}
