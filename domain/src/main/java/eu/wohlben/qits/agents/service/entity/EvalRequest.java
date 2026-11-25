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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * A release request as qits-projects' {@code ReleaseRequestStateChanged} last told it ({@code
 * eval_request}): its source branches, which attribute it (and its CI runs) to items, and its state.
 *
 * <p><b>Technical, uncaused.</b> A raw fact of the evaluation ledger (V4, epic qits-2521): no door
 * serves the row; {@code GET /projects/{project}/evaluation} serves only figures derived from it.
 * Keyed by the request's id.
 */
@Entity
@Table(name = "eval_request")
@Uncaused
@Technical(
    reason =
        "Evaluation ledger (qits-2521): a raw fact the evaluation door derives its figures from;"
            + " never served as a row.")
public class EvalRequest extends PanacheEntityBase {

  @Id
  @Column(name = "request_id")
  public String requestId;

  @Column(name = "project_id")
  public UUID projectId;

  @Column(name = "qualified_id")
  public String qualifiedId;

  @Column(name = "repo_name")
  public String repoName;

  @JdbcTypeCode(SqlTypes.ARRAY)
  @Column(nullable = false, columnDefinition = "text[]")
  public String[] branches;

  @Column(nullable = false)
  public String state;

  @Column(name = "changed_at", nullable = false)
  public Instant changedAt;
}
