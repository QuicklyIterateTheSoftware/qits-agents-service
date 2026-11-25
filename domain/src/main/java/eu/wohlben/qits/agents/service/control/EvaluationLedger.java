package eu.wohlben.qits.agents.service.control;

import eu.wohlben.qits.agents.service.entity.EvalUsageMessage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * <b>The evaluation ledger's writes</b> (V4, epic qits-2521): the raw facts {@code
 * GET /projects/{project}/evaluation} derives its figures from. Every write is an insert keyed by
 * its source with {@code ON CONFLICT}, so a resend or a replay writes nothing twice and never trips
 * a unique index's violation (which would poison the caller's transaction).
 */
@ApplicationScoped
public class EvaluationLedger {

  private static final Logger LOG = Logger.getLogger(EvaluationLedger.class);

  /** At most this many messages per usage report. */
  public static final int MAX_USAGE_MESSAGES = 500;

  /**
   * One message of a project agent's transcript, as its daemon reports it: {@code workQualifiedId}
   * is null for the overseer's own messages.
   */
  public record UsageMessage(
      String messageId,
      String workQualifiedId,
      String model,
      long inputTokens,
      long outputTokens,
      long cacheCreationTokens,
      long cacheReadTokens,
      Instant at) {}

  /**
   * Records a project agent's transcript usage, each message once per session: a message already
   * recorded is skipped, so the daemon may resend a batch. Answers how many were new.
   */
  @Transactional
  public int usage(UUID agentId, String sessionId, List<UsageMessage> messages) {
    EntityManager em = EvalUsageMessage.getEntityManager();
    int inserted = 0;
    for (UsageMessage message : messages) {
      inserted +=
          em.createNativeQuery(
                  "insert into {h-schema}eval_usage_message (session_id, message_id, agent_id,"
                      + " work_qualified_id, claude_model, input_tokens, output_tokens,"
                      + " cache_creation_tokens, cache_read_tokens, at)"
                      + " values (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10)"
                      + " on conflict (session_id, message_id) do nothing")
              .setParameter(1, sessionId)
              .setParameter(2, message.messageId())
              .setParameter(3, agentId)
              .setParameter(4, blankToNull(message.workQualifiedId()))
              .setParameter(5, message.model())
              .setParameter(6, message.inputTokens())
              .setParameter(7, message.outputTokens())
              .setParameter(8, message.cacheCreationTokens())
              .setParameter(9, message.cacheReadTokens())
              .setParameter(10, micros(message.at()))
              .executeUpdate();
    }
    LOG.debugf(
        "usage of agent %s session %s: %d of %d messages new",
        agentId, sessionId, inserted, messages.size());
    return inserted;
  }

  static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  /** Microseconds, as postgres stores them, so a read-back equals what was written. */
  static Instant micros(Instant at) {
    return (at == null ? Instant.now() : at).truncatedTo(ChronoUnit.MICROS);
  }
}
