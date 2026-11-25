package eu.wohlben.qits.agents.service.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;
import java.util.UUID;

/**
 * <b>Consumed, not published</b>: this service's copy of qits-workspaces' {@code AgentUsageRecorded}, read off the
 * durable consumption {@code eval-usage} ({@code bus/EvalUsageListener}) into the evaluation ledger
 * (epic qits-2521). A workspace agent's transcript usage: one session's newly counted messages of one Claude model.
 *
 * <p>Bound by name and lenient to fields this copy does not know; a field the producer leaves out
 * binds as null. It is deliberately not a {@code QitsEvent}: nothing here publishes it.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentUsageRecorded(
    UUID eventId,
    String agentId,
    String workId,
    String workspaceId,
    String projectId,
    String sessionId,
    String model,
    int messages,
    long inputTokens,
    long outputTokens,
    long cacheCreationTokens,
    long cacheReadTokens,
    Instant occurredAt) {}
