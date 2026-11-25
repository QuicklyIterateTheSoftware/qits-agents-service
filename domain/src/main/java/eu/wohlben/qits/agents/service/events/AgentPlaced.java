package eu.wohlben.qits.agents.service.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;
import java.util.UUID;

/**
 * <b>Consumed, not published</b>: this service's copy of qits-workspaces' {@code AgentPlaced}, read off the
 * durable consumption {@code eval-placements} ({@code bus/EvalPlacementsListener}) into the evaluation ledger
 * (epic qits-2521). A workspace agent was placed on a work item.
 *
 * <p>Bound by name and lenient to fields this copy does not know; a field the producer leaves out
 * binds as null. It is deliberately not a {@code QitsEvent}: nothing here publishes it.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentPlaced(
    UUID eventId,
    String agentId,
    String workId,
    String workspaceId,
    String repositoryId,
    Instant occurredAt) {}
