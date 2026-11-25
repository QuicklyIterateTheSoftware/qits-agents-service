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
 * A CI run ({@code eval_run}), from qits-ci's {@code BuildStatusChanged}: the first event seen for
 * it, its terminal one, and the release request it ran for.
 *
 * <p><b>Technical, uncaused.</b> A raw fact of the evaluation ledger (V4, epic qits-2521): no door
 * serves the row; {@code GET /projects/{project}/evaluation} serves only figures derived from it.
 * Keyed by the run's id.
 */
@Entity
@Table(name = "eval_run")
@Uncaused
@Technical(
    reason =
        "Evaluation ledger (qits-2521): a raw fact the evaluation door derives its figures from;"
            + " never served as a row.")
public class EvalRun extends PanacheEntityBase {

  @Id
  @Column(name = "run_id")
  public String runId;

  @Column(name = "project_id")
  public UUID projectId;

  @Column(name = "request_id")
  public String requestId;

  @Column(name = "first_at", nullable = false)
  public Instant firstAt;

  @Column(name = "terminal_at")
  public Instant terminalAt;

  @Column(name = "terminal_status")
  public String terminalStatus;
}
