package eu.wohlben.qits.agents.service.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * <b>Consumed, not published</b>: this service's copy of qits-projects' {@code EntityCommented},
 * read off the durable consumption {@code agents-comments} ({@code bus/CommentListener}) and
 * applied by {@code control/CommentNotices}. Somebody wrote a comment on a work item's thread.
 *
 * <p>Bound by name and lenient to fields this copy does not know. It is deliberately not a {@code
 * QitsEvent}: nothing here publishes it.
 *
 * @param rootEntityId the item's root work item — the epic of a feature or a task, the item itself
 *     otherwise — which is what an agent's claim is keyed by; absent from an older producer, when
 *     {@code entityId} stands in
 * @param author the principal, or the agent's id for an agent's comment
 * @param authorName who the comment is by, for a person to read
 * @param authorAgentOf for an agent's comment, the qualified id of the agent's work item
 * @param changedBy the principal that wrote it — an agent's own token subject for its own comment
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record EntityCommented(
    UUID eventId,
    String commentId,
    String entityId,
    String rootEntityId,
    String projectId,
    String archetype,
    long number,
    String title,
    String author,
    String authorName,
    String authorAgentOf,
    String changedBy,
    List<String> mentions,
    Instant commentedAt) {}
