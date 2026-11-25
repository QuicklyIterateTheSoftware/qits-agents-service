package eu.wohlben.qits.agents.service.entity;

import eu.wohlben.qits.db.exposure.Technical;
import eu.wohlben.qits.eventstream.Uncaused;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A status an item entered, and when ({@code eval_transition}), from qits-projects' {@code
 * EntityTransitioned}.
 *
 * <p><b>Technical, uncaused.</b> A raw fact of the evaluation ledger (V4, epic qits-2521): no door
 * serves the row; {@code GET /projects/{project}/evaluation} serves only figures derived from it.
 * Keyed by the item, the status and the instant, so a replay writes nothing twice.
 */
@Entity
@Table(name = "eval_transition")
@IdClass(EvalTransition.Key.class)
@Uncaused
@Technical(
    reason =
        "Evaluation ledger (qits-2521): a raw fact the evaluation door derives its figures from;"
            + " never served as a row.")
public class EvalTransition extends PanacheEntityBase {

  @Id
  @Column(name = "work_id", nullable = false)
  public UUID workId;

  @Id
  @Column(nullable = false)
  public String status;

  @Id
  @Column(nullable = false)
  public Instant at;

  /** The composite key. */
  public static class Key implements Serializable {

    private static final long serialVersionUID = 1L;

    public UUID workId;
    public String status;
    public Instant at;

    public Key() {}

    public Key(UUID workId, String status, Instant at) {
      this.workId = workId;
      this.status = status;
      this.at = at;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Key other
          && Objects.equals(workId, other.workId)
          && Objects.equals(status, other.status)
          && Objects.equals(at, other.at);
    }

    @Override
    public int hashCode() {
      return Objects.hash(workId, status, at);
    }
  }
}
