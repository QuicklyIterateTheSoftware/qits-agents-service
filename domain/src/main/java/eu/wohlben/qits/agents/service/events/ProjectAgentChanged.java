package eu.wohlben.qits.agents.service.events;

import eu.wohlben.qits.eventstream.QitsEvent;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;
import java.util.UUID;

/**
 * An agent's generation, desired state or reported state moved ({@code ProjectAgentChanged}).
 * Published by {@code control.Agents}, the one place that writes those columns, in the transaction
 * that writes the row: the roster reconcile (a new agent, a new generation, a retirement) and every
 * {@code PUT …/report} that changes {@code reported_state}. qits-2520's waits match it.
 *
 * <p>The agent's name is {@code agentName}, never {@code name}: that is {@link QitsEvent#name()},
 * the event's own name on the envelope, which qits-eventstream hides from every payload — a record
 * component called {@code name} would rename the event itself. {@code changedAt} is the moment the
 * row changed; {@code occurredAt} carries the same instant on the envelope, beside {@code eventId}.
 */
@RegisterForReflection
public record ProjectAgentChanged(
    UUID eventId,
    UUID projectId,
    String projectSlug,
    String agentName,
    int generation,
    String desiredState,
    String reportedState,
    String declarationHash,
    Instant changedAt,
    Instant occurredAt)
    implements QitsEvent {}
