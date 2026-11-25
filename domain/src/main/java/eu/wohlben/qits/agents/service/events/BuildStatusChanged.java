package eu.wohlben.qits.agents.service.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;
import java.util.UUID;

/**
 * <b>Consumed, not published</b>: this service's copy of qits-ci's {@code BuildStatusChanged}, read off the
 * durable consumption {@code eval-builds} ({@code bus/EvalBuildsListener}) into the evaluation ledger
 * (epic qits-2521). A CI run's status moved; its terminal words are SUCCESS, FAILED, CANCELLED, CONFIG_ERROR and
 * TIMED_OUT. {@code occurredAt} may ride only the envelope.
 *
 * <p>Bound by name and lenient to fields this copy does not know; a field the producer leaves out
 * binds as null. It is deliberately not a {@code QitsEvent}: nothing here publishes it.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record BuildStatusChanged(
    UUID eventId,
    String runId,
    String repoId,
    String projectId,
    String repoName,
    String branch,
    String commitSha,
    String phase,
    String releaseRequestId,
    String releaseRequestQualifiedId,
    String status,
    String previousStatus,
    Instant occurredAt) {}
