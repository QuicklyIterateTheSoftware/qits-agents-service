package eu.wohlben.qits.agents.service.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;
import java.util.UUID;

/**
 * <b>Consumed, not published</b>: this service's copy of qits-projects' {@code EntityAssigned}, read
 * off the durable consumption {@code agents-assignments} ({@code bus/AssignmentListener}) and
 * applied by {@code control/Assignments}. An item's assignee changed — by a person, by the agent
 * itself, or cleared.
 *
 * <p>Bound by name and lenient to fields this copy does not know, so qits-projects may grow the
 * event without breaking this reader. Ids are strings on the wire (the producer's choice). It is
 * deliberately not a {@code QitsEvent}: nothing here publishes it.
 *
 * @param qualifiedId {@code <project-slug>-<number>}, absent when the producer could not read the
 *     slug
 * @param assignee the assignee now (an agent's name, or its token subject as an alias), absent when
 *     cleared
 * @param previousAssignee the assignee before, absent when there was none
 * @param changedBy the principal that made the change — the agent's own token subject when it
 *     self-assigned
 * @param changedByName who that is, for a person to read
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record EntityAssigned(
    UUID eventId,
    String entityId,
    String projectId,
    String archetype,
    long number,
    String qualifiedId,
    String title,
    String status,
    String assignee,
    String previousAssignee,
    boolean claimed,
    String changedBy,
    String changedByName,
    Instant assignedAt) {}
