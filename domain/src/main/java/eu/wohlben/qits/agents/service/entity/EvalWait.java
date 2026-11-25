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
 * A closed wait of either model ({@code eval_wait}): {@code AgentWaitClosed} (WORKSPACE) or {@code
 * ProjectAgentWaitClosed} (PROJECT_AGENT).
 *
 * <p><b>Technical, uncaused.</b> A raw fact of the evaluation ledger (V4, epic qits-2521): no door
 * serves the row; {@code GET /projects/{project}/evaluation} serves only figures derived from it.
 * Keyed by the wait's id.
 */
@Entity
@Table(name = "eval_wait")
@Uncaused
@Technical(
    reason =
        "Evaluation ledger (qits-2521): a raw fact the evaluation door derives its figures from;"
            + " never served as a row.")
public class EvalWait extends PanacheEntityBase {

  @Id
  @Column(name = "wait_id")
  public String waitId;

  @Column(nullable = false)
  public String model;

  @Column(name = "work_id")
  public UUID workId;

  @Column(nullable = false)
  public String state;

  @Column(name = "closed_at", nullable = false)
  public Instant closedAt;
}
