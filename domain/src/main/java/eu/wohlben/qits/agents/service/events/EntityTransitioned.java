package eu.wohlben.qits.agents.service.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * <b>Consumed, not published</b>: this service's copy of qits-projects' {@code EntityTransitioned}, read off the
 * durable consumption {@code eval-transitions} ({@code bus/EvalTransitionsListener}) into the evaluation ledger
 * (epic qits-2521). One or more entities moved; each entry is the entity's post-state.
 *
 * <p>Bound by name and lenient to fields this copy does not know; a field the producer leaves out
 * binds as null. It is deliberately not a {@code QitsEvent}: nothing here publishes it.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record EntityTransitioned(
    UUID eventId,
    List<Entity> entities,
    Instant transitionedAt) {

  /**
   * One entity's post-state.
   *
   * @param status the status now
   * @param statusBefore the status before: equal to {@code status} for an entity the batch reshaped
   *     without moving it, absent for one the batch created
   * @param number the {@code <n>} of {@code <project-slug>-<n>}
   */
  @RegisterForReflection
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Entity(
      String entityId,
      String projectId,
      String archetype,
      String parentId,
      Integer position,
      String slug,
      String title,
      String status,
      String statusBefore,
      String changedBy,
      long number) {}
}
