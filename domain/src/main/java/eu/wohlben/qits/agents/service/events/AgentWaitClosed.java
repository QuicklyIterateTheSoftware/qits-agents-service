package eu.wohlben.qits.agents.service.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;
import java.util.UUID;

/**
 * <b>Consumed, not published</b>: this service's copy of qits-workspaces' {@code AgentWaitClosed}, read off the
 * durable consumption {@code eval-waits} ({@code bus/EvalWaitsListener}) into the evaluation ledger
 * (epic qits-2521). A workspace agent's wait closed: MATCHED, TIMED_OUT, BROKEN or CANCELLED.
 *
 * <p>Bound by name and lenient to fields this copy does not know; a field the producer leaves out
 * binds as null. It is deliberately not a {@code QitsEvent}: nothing here publishes it.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentWaitClosed(
    UUID eventId,
    String agentId,
    String workId,
    String workspaceId,
    String repositoryId,
    String projectId,
    String waitId,
    String state,
    String matchedEventId,
    String label,
    String reason,
    Instant occurredAt) {}
