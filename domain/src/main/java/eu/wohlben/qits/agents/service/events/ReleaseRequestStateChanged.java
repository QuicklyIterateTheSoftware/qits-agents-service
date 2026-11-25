package eu.wohlben.qits.agents.service.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * <b>Consumed, not published</b>: this service's copy of qits-projects' {@code ReleaseRequestStateChanged}, read off the
 * durable consumption {@code eval-requests} ({@code bus/EvalRequestsListener}) into the evaluation ledger
 * (epic qits-2521). A release request entered {@code state}; {@code branches} are its source branches.
 *
 * <p>Bound by name and lenient to fields this copy does not know; a field the producer leaves out
 * binds as null. It is deliberately not a {@code QitsEvent}: nothing here publishes it.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReleaseRequestStateChanged(
    UUID eventId,
    String releaseRequestId,
    String releaseRequestQualifiedId,
    String projectId,
    String repoId,
    String repoName,
    String backingBranch,
    String state,
    String previousState,
    Instant changedAt,
    String mergedSha,
    String version,
    String detail,
    List<String> branches) {}
