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
 * One state an agent entered ({@code eval_activity}): a workspace agent's {@code
 * AgentActivityChanged} (model WORKSPACE, subject the agent's id), or a project agent's reported
 * state off its {@code ProjectAgentChanged} (model PROJECT_AGENT, subject the agent's name). The
 * report reads consecutive rows of one subject as intervals.
 *
 * <p><b>Technical, uncaused.</b> A raw fact of the evaluation ledger (V4, epic qits-2521): no door
 * serves the row; {@code GET /projects/{project}/evaluation} serves only figures derived from it.
 * Keyed by the source event's id.
 */
@Entity
@Table(name = "eval_activity")
@Uncaused
@Technical(
    reason =
        "Evaluation ledger (qits-2521): a raw fact the evaluation door derives its figures from;"
            + " never served as a row.")
public class EvalActivity extends PanacheEntityBase {

  @Id
  @Column(name = "source_event_id")
  public UUID sourceEventId;

  @Column(nullable = false)
  public String model;

  @Column(name = "project_id")
  public UUID projectId;

  @Column(nullable = false)
  public String subject;

  @Column(name = "work_id")
  public UUID workId;

  @Column(nullable = false)
  public String state;

  @Column(nullable = false)
  public Instant at;
}
