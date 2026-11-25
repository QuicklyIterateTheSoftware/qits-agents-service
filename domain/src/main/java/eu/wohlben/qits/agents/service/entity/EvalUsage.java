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
 * A workspace agent's transcript usage ({@code eval_usage}), from qits-workspaces' {@code
 * AgentUsageRecorded}: one session's messages of one Claude model, counted once each.
 *
 * <p><b>Technical, uncaused.</b> A raw fact of the evaluation ledger (V4, epic qits-2521): no door
 * serves the row; {@code GET /projects/{project}/evaluation} serves only figures derived from it.
 * Keyed by the source event's id.
 */
@Entity
@Table(name = "eval_usage")
@Uncaused
@Technical(
    reason =
        "Evaluation ledger (qits-2521): a raw fact the evaluation door derives its figures from;"
            + " never served as a row.")
public class EvalUsage extends PanacheEntityBase {

  @Id
  @Column(name = "source_event_id")
  public UUID sourceEventId;

  @Column(nullable = false)
  public String model;

  @Column(name = "project_id")
  public UUID projectId;

  @Column(name = "work_id")
  public UUID workId;

  @Column(name = "session_id", nullable = false)
  public String sessionId;

  @Column(name = "claude_model", nullable = false)
  public String claudeModel;

  @Column(nullable = false)
  public int messages;

  @Column(name = "input_tokens", nullable = false)
  public long inputTokens;

  @Column(name = "output_tokens", nullable = false)
  public long outputTokens;

  @Column(name = "cache_creation_tokens", nullable = false)
  public long cacheCreationTokens;

  @Column(name = "cache_read_tokens", nullable = false)
  public long cacheReadTokens;

  @Column(nullable = false)
  public Instant at;
}
