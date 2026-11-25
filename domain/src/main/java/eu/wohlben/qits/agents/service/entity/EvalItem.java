package eu.wohlben.qits.agents.service.entity;

import eu.wohlben.qits.db.exposure.Technical;
import eu.wohlben.qits.eventstream.Uncaused;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One EPIC or TICKET of the evaluation ({@code eval_item}): its identity, from qits-projects' events,
 * and when each model took it — {@code workspace_taken_at} at the first {@code AgentPlaced} naming
 * it, {@code agent_taken_at} at the first {@code EntityAssigned} to a roster agent. Both set is the
 * failure mode "double-worked" (BOTH).
 *
 * <p><b>Technical, uncaused.</b> A raw fact of the evaluation ledger (V4, epic qits-2521): no door
 * serves the row; {@code GET /projects/{project}/evaluation} serves only figures derived from it.
 * Its cause is the events named, each upserted by the work item's id.
 */
@Entity
@Table(name = "eval_item")
@Uncaused
@Technical(
    reason =
        "Evaluation ledger (qits-2521): a raw fact the evaluation door derives its figures from;"
            + " never served as a row.")
public class EvalItem extends PanacheEntityBase {

  @Id
  @Column(name = "work_id")
  public UUID workId;

  @Column(name = "project_id")
  public UUID projectId;

  @Column(name = "qualified_id")
  public String qualifiedId;

  public String archetype;

  @Column(name = "workspace_taken_at")
  public Instant workspaceTakenAt;

  @Column(name = "agent_taken_at")
  public Instant agentTakenAt;

  @Column(name = "agent_name")
  public String agentName;
}
