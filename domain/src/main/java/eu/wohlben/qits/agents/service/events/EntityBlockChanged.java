package eu.wohlben.qits.agents.service.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;
import java.util.UUID;

/**
 * <b>Consumed, not published</b>: this service's copy of qits-projects' {@code EntityBlockChanged}, read off the
 * durable consumption {@code eval-blocks} ({@code bus/EvalBlocksListener}) into the evaluation ledger
 * (epic qits-2521). An item's blocked flag was set or cleared.
 *
 * <p>Bound by name and lenient to fields this copy does not know; a field the producer leaves out
 * binds as null. It is deliberately not a {@code QitsEvent}: nothing here publishes it.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record EntityBlockChanged(
    UUID eventId,
    String entityId,
    String projectId,
    String archetype,
    long number,
    String qualifiedId,
    String status,
    boolean blocked,
    String reason,
    String changedBy,
    Instant changedAt) {}
