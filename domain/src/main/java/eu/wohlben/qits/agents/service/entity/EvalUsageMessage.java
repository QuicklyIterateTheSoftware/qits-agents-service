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
 * One message of a project agent's transcript ({@code eval_usage_message}), as its daemon reports it
 * to {@code POST …/agents/{name}/usage}: the Claude model, the four token counts, and the item its
 * worktree names, null for the overseer's own messages.
 *
 * <p><b>Technical, uncaused.</b> A raw fact of the evaluation ledger (V4, epic qits-2521): no door
 * serves the row; {@code GET /projects/{project}/evaluation} serves only figures derived from it.
 * Keyed by the session and the message's id, inserted once, so the daemon may resend.
 */
@Entity
@Table(name = "eval_usage_message")
@IdClass(EvalUsageMessage.Key.class)
@Uncaused
@Technical(
    reason =
        "Evaluation ledger (qits-2521): a raw fact the evaluation door derives its figures from;"
            + " never served as a row.")
public class EvalUsageMessage extends PanacheEntityBase {

  @Id
  @Column(name = "session_id", nullable = false)
  public String sessionId;

  @Id
  @Column(name = "message_id", nullable = false)
  public String messageId;

  @Column(name = "agent_id", nullable = false)
  public UUID agentId;

  @Column(name = "work_qualified_id")
  public String workQualifiedId;

  @Column(name = "claude_model", nullable = false)
  public String claudeModel;

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

  /** The composite key. */
  public static class Key implements Serializable {

    private static final long serialVersionUID = 1L;

    public String sessionId;
    public String messageId;

    public Key() {}

    public Key(String sessionId, String messageId) {
      this.sessionId = sessionId;
      this.messageId = messageId;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Key other
          && Objects.equals(sessionId, other.sessionId)
          && Objects.equals(messageId, other.messageId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(sessionId, messageId);
    }
  }
}
