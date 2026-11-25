package eu.wohlben.qits.agents.service.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;
import java.util.UUID;

/**
 * <b>Consumed, not published</b>: this service's copy of qits-workspaces' {@code AgentHarnessExited}, read off the
 * durable consumption {@code eval-exits} ({@code bus/EvalExitsListener}) into the evaluation ledger
 * (epic qits-2521). A workspace agent's harness exited; {@code stoppedBy} is DAEMON_REMOVE, DAEMON_YIELD, KILLED or
 * SELF.
 *
 * <p>Bound by name and lenient to fields this copy does not know; a field the producer leaves out
 * binds as null. It is deliberately not a {@code QitsEvent}: nothing here publishes it.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record AgentHarnessExited(
    UUID eventId,
    String agentId,
    String workId,
    String workspaceId,
    String repositoryId,
    String commandId,
    String status,
    Integer exitCode,
    String stoppedBy,
    String remoteSession,
    String remoteArchive,
    Instant occurredAt) {}
