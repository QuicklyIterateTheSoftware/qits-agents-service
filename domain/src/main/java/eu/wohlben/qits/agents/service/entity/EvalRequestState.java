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

/**
 * The first time a release request entered a state ({@code eval_request_state}).
 *
 * <p><b>Technical, uncaused.</b> A raw fact of the evaluation ledger (V4, epic qits-2521): no door
 * serves the row; {@code GET /projects/{project}/evaluation} serves only figures derived from it.
 * Keyed by the request and the state.
 */
@Entity
@Table(name = "eval_request_state")
@IdClass(EvalRequestState.Key.class)
@Uncaused
@Technical(
    reason =
        "Evaluation ledger (qits-2521): a raw fact the evaluation door derives its figures from;"
            + " never served as a row.")
public class EvalRequestState extends PanacheEntityBase {

  @Id
  @Column(name = "request_id", nullable = false)
  public String requestId;

  @Id
  @Column(nullable = false)
  public String state;

  @Column(nullable = false)
  public Instant at;

  /** The composite key. */
  public static class Key implements Serializable {

    private static final long serialVersionUID = 1L;

    public String requestId;
    public String state;

    public Key() {}

    public Key(String requestId, String state) {
      this.requestId = requestId;
      this.state = state;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Key other
          && Objects.equals(requestId, other.requestId)
          && Objects.equals(state, other.state);
    }

    @Override
    public int hashCode() {
      return Objects.hash(requestId, state);
    }
  }
}
