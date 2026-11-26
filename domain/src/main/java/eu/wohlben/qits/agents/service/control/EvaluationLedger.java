package eu.wohlben.qits.agents.service.control;

import eu.wohlben.qits.agents.service.entity.EvalItem;
import eu.wohlben.qits.agents.service.entity.ProjectAgent;
import eu.wohlben.qits.agents.service.events.AgentActivityChanged;
import eu.wohlben.qits.agents.service.events.AgentHarnessExited;
import eu.wohlben.qits.agents.service.events.AgentPlaced;
import eu.wohlben.qits.agents.service.events.AgentUsageRecorded;
import eu.wohlben.qits.agents.service.events.AgentWaitClosed;
import eu.wohlben.qits.agents.service.events.BuildStatusChanged;
import eu.wohlben.qits.agents.service.events.EntityAssigned;
import eu.wohlben.qits.agents.service.events.EntityBlockChanged;
import eu.wohlben.qits.agents.service.events.EntityTransitioned;
import eu.wohlben.qits.agents.service.events.ProjectAgentChanged;
import eu.wohlben.qits.agents.service.events.ProjectAgentWaitClosed;
import eu.wohlben.qits.agents.service.events.ReleaseRequestStateChanged;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.hibernate.query.NativeQuery;
import org.jboss.logging.Logger;

/**
 * <b>The evaluation ledger's writes</b> (V4, epic qits-2521): the raw facts {@code
 * GET /projects/{project}/evaluation} derives its figures from ({@link Evaluation}). Every write is
 * an insert keyed by its source with {@code ON CONFLICT}, so a resend or a replay writes nothing
 * twice and never trips a unique index's violation (which would poison the caller's transaction).
 *
 * <p>The event appliers run in their own transaction on the agents datasource ({@code
 * REQUIRES_NEW}), never the durable listener's claiming one, for the reason {@link Assignments}
 * gives. An event older than {@code qits.agents.evaluation.since} is settled without a write: the
 * consumptions replay qits-events' log from its start, and only the window from that day on is
 * evaluated.
 */
@ApplicationScoped
public class EvaluationLedger {

  private static final Logger LOG = Logger.getLogger(EvaluationLedger.class);

  /** At most this many messages per usage report. */
  public static final int MAX_USAGE_MESSAGES = 500;

  public static final String WORKSPACE = "WORKSPACE";

  public static final String PROJECT_AGENT = "PROJECT_AGENT";

  /** The archetypes an evaluation counts. */
  static final Set<String> EVALUATED = Set.of("EPIC", "TICKET");

  /** The words a CI run ends on. */
  public static final Set<String> TERMINAL =
      Set.of("SUCCESS", "FAILED", "CANCELLED", "CONFIG_ERROR", "TIMED_OUT");

  /** The harness stops that count as a session exit: the agent's own, or a kill. */
  static final Set<String> EXITS = Set.of("SELF", "KILLED");

  @ConfigProperty(name = "qits.agents.evaluation.since", defaultValue = "2026-11-25T00:00:00Z")
  Instant since;

  @Inject ProjectDirectory directory;

  /** Project id to slug, as far as the directory was asked; a slug never changes. */
  private final Map<UUID, String> slugs = new ConcurrentHashMap<>();

  /** Where the ledger starts: no fact older than this is written. */
  public Instant since() {
    return since;
  }

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

  // --- the project agent's own facts ------------------------------------------------------------

  /**
   * Records a project agent's transcript usage, each message once per session, so the daemon may
   * resend a batch. A resent message's tokens, model and time are never rewritten, but a resend
   * that names a work item re-attributes it (qits-2722): a daemon that attributed it to none, or to
   * another item, is corrected by the next one's rescan. A resend naming none never clears an
   * item. Answers how many messages were new or re-attributed.
   */
  @Transactional
  public int usage(UUID agentId, String sessionId, List<UsageMessage> messages) {
    int inserted = 0;
    for (UsageMessage message : messages) {
      inserted +=
          sql("insert into {h-schema}eval_usage_message as u (session_id, message_id, agent_id,"
                  + " work_qualified_id, claude_model, input_tokens, output_tokens,"
                  + " cache_creation_tokens, cache_read_tokens, at)"
                  + " values (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10)"
                  + " on conflict (session_id, message_id) do update"
                  + " set work_qualified_id = excluded.work_qualified_id"
                  + " where excluded.work_qualified_id is not null and"
                  + " u.work_qualified_id is distinct from"
                  + " excluded.work_qualified_id",
              sessionId,
              message.messageId(),
              agentId,
              nullable(blankToNull(message.workQualifiedId())),
              message.model(),
              message.inputTokens(),
              message.outputTokens(),
              message.cacheCreationTokens(),
              message.cacheReadTokens(),
              micros(message.at()));
    }
    LOG.debugf(
        "usage of agent %s session %s: %d of %d messages new or re-attributed",
        agentId, sessionId, inserted, messages.size());
    return inserted;
  }

  /**
   * A project agent's reported state, from this service's own {@code ProjectAgentChanged} (the
   * {@code eval-project-agents} consumption), keyed by the event's id. The report reads consecutive
   * rows of one agent as its busy, idle and down intervals, and an entry into EXITED or FAILED as a
   * session exit; a change that moved only the generation or the desired state repeats the reported
   * one, and the report merges it.
   */
  @Transactional(Transactional.TxType.REQUIRES_NEW)
  public void projectAgentChanged(UUID eventId, ProjectAgentChanged event, Instant occurredAt) {
    Instant at = micros(first(event.changedAt(), first(event.occurredAt(), occurredAt)));
    if (before(at)
        || blankToNull(event.agentName()) == null
        || blankToNull(event.reportedState()) == null) {
      return;
    }
    sql("insert into {h-schema}eval_activity (source_event_id, model, project_id, subject, work_id,"
            + " state, at) values (?1, ?2, ?3, ?4, null, ?5, ?6)"
            + " on conflict (source_event_id) do nothing",
        eventId,
        PROJECT_AGENT,
        nullable(event.projectId()),
        event.agentName(),
        event.reportedState(),
        at);
  }

  // --- qits-projects --------------------------------------------------------------------------

  /** Each EPIC or TICKET entry that moved its status: the item's identity and the status entered. */
  @Transactional(Transactional.TxType.REQUIRES_NEW)
  public void transitioned(EntityTransitioned event, Instant occurredAt) {
    Instant at = micros(first(event.transitionedAt(), occurredAt));
    if (before(at) || event.entities() == null) {
      return;
    }
    for (EntityTransitioned.Entity entity : event.entities()) {
      UUID workId = uuid(entity.entityId());
      if (workId == null || !EVALUATED.contains(entity.archetype())) {
        continue;
      }
      UUID projectId = uuid(entity.projectId());
      item(workId, projectId, qualifiedId(projectId, null, entity.number()), entity.archetype());
      if (entity.status() == null || entity.status().equals(entity.statusBefore())) {
        continue; // reshaped, not moved
      }
      sql("insert into {h-schema}eval_transition (work_id, status, at) values (?1, ?2, ?3)"
              + " on conflict do nothing",
          workId,
          entity.status(),
          at);
    }
  }

  /** An EPIC or TICKET assigned to one of its project's roster agents: the agent model took it. */
  @Transactional(Transactional.TxType.REQUIRES_NEW)
  public void assigned(EntityAssigned event) {
    Instant at = micros(event.assignedAt());
    UUID workId = uuid(event.entityId());
    UUID projectId = uuid(event.projectId());
    if (before(at)
        || workId == null
        || projectId == null
        || !EVALUATED.contains(event.archetype())) {
      return;
    }
    List<ProjectAgent> roster = ProjectAgent.list("projectId = ?1", projectId);
    ProjectAgent agent =
        roster.stream().filter(a -> Assignments.matches(a, event.assignee())).findFirst().orElse(null);
    if (agent == null) {
      return;
    }
    item(workId, projectId, qualifiedId(projectId, event.qualifiedId(), event.number()), event.archetype());
    sql("update {h-schema}eval_item set agent_taken_at = ?2, agent_name = ?3"
            + " where work_id = ?1 and (agent_taken_at is null or agent_taken_at > ?2)",
        workId,
        at,
        agent.name);
  }

  /** A block set on an item. A cleared one is no failure and writes nothing. */
  @Transactional(Transactional.TxType.REQUIRES_NEW)
  public void blockChanged(UUID eventId, EntityBlockChanged event, Instant occurredAt) {
    Instant at = micros(first(event.changedAt(), occurredAt));
    UUID workId = uuid(event.entityId());
    if (before(at) || !event.blocked() || workId == null) {
      return;
    }
    sql("insert into {h-schema}eval_block (source_event_id, work_id, at) values (?1, ?2, ?3)"
            + " on conflict do nothing",
        eventId,
        workId,
        at);
  }

  /**
   * A release request entered a state: its branches (as last seen, never emptied by an event that
   * carries none) and the first time it entered this state.
   */
  @Transactional(Transactional.TxType.REQUIRES_NEW)
  public void requestChanged(ReleaseRequestStateChanged event, Instant occurredAt) {
    Instant at = micros(first(event.changedAt(), occurredAt));
    String requestId = blankToNull(event.releaseRequestId());
    if (before(at) || requestId == null || event.state() == null) {
      return;
    }
    List<String> branches = event.branches() == null ? List.of() : event.branches();
    sql("insert into {h-schema}eval_request (request_id, project_id, qualified_id, repo_name,"
            + " branches, state, changed_at)"
            + " values (?1, ?2, ?3, ?4, string_to_array(?5, chr(10)), ?6, ?7)"
            + " on conflict (request_id) do update set"
            + " project_id = coalesce(eval_request.project_id, excluded.project_id),"
            + " qualified_id = coalesce(eval_request.qualified_id, excluded.qualified_id),"
            + " repo_name = coalesce(eval_request.repo_name, excluded.repo_name),"
            + " branches = case when cardinality(excluded.branches) > 0"
            + "   and excluded.changed_at >= eval_request.changed_at"
            + "   then excluded.branches else eval_request.branches end,"
            + " state = case when excluded.changed_at >= eval_request.changed_at"
            + "   then excluded.state else eval_request.state end,"
            + " changed_at = greatest(eval_request.changed_at, excluded.changed_at)",
        requestId,
        nullable(uuid(event.projectId())),
        nullable(event.releaseRequestQualifiedId()),
        nullable(event.repoName()),
        String.join("\n", branches),
        event.state(),
        at);
    sql("insert into {h-schema}eval_request_state (request_id, state, at) values (?1, ?2, ?3)"
            + " on conflict (request_id, state) do update set"
            + " at = least(eval_request_state.at, excluded.at)",
        requestId,
        event.state(),
        at);
  }

  // --- qits-ci ---------------------------------------------------------------------------------

  /**
   * A CI run's status moved: the first event seen for the run, and its terminal one. A run
   * terminated twice (a retry of the same run) keeps the later end.
   */
  @Transactional(Transactional.TxType.REQUIRES_NEW)
  public void buildStatusChanged(BuildStatusChanged event, Instant occurredAt) {
    Instant at = micros(first(event.occurredAt(), occurredAt));
    String runId = blankToNull(event.runId());
    if (before(at) || runId == null) {
      return;
    }
    boolean terminal = TERMINAL.contains(event.status());
    sql("insert into {h-schema}eval_run (run_id, project_id, request_id, first_at, terminal_at,"
            + " terminal_status) values (?1, ?2, ?3, ?4, ?5, ?6)"
            + " on conflict (run_id) do update set"
            + " project_id = coalesce(eval_run.project_id, excluded.project_id),"
            + " request_id = coalesce(eval_run.request_id, excluded.request_id),"
            + " first_at = least(eval_run.first_at, excluded.first_at),"
            + " terminal_status = case when excluded.terminal_at is not null"
            + "   and (eval_run.terminal_at is null or excluded.terminal_at >= eval_run.terminal_at)"
            + "   then excluded.terminal_status else eval_run.terminal_status end,"
            + " terminal_at = greatest(eval_run.terminal_at, excluded.terminal_at)",
        runId,
        nullable(uuid(event.projectId())),
        nullable(blankToNull(event.releaseRequestId())),
        at,
        nullable(terminal ? at : null),
        nullable(terminal ? event.status() : null));
  }

  // --- qits-workspaces -------------------------------------------------------------------------

  /** A workspace agent placed on an item: the workspace model took it. */
  @Transactional(Transactional.TxType.REQUIRES_NEW)
  public void placed(AgentPlaced event, Instant occurredAt) {
    Instant at = micros(first(event.occurredAt(), occurredAt));
    UUID workId = uuid(event.workId());
    if (before(at) || workId == null) {
      return;
    }
    sql("insert into {h-schema}eval_item (work_id, workspace_taken_at) values (?1, ?2)"
            + " on conflict (work_id) do update set"
            + " workspace_taken_at = least(eval_item.workspace_taken_at, excluded.workspace_taken_at)",
        workId,
        at);
  }

  /** A workspace agent's activity: IDLE, BUSY, WAITING or ENDED. */
  @Transactional(Transactional.TxType.REQUIRES_NEW)
  public void activity(UUID eventId, AgentActivityChanged event, Instant occurredAt) {
    Instant at = micros(first(instant(event.at()), first(event.occurredAt(), occurredAt)));
    if (before(at) || blankToNull(event.agentId()) == null || event.activity() == null) {
      return;
    }
    sql("insert into {h-schema}eval_activity (source_event_id, model, project_id, subject, work_id,"
            + " state, at) values (?1, ?2, ?3, ?4, ?5, ?6, ?7)"
            + " on conflict (source_event_id) do nothing",
        eventId,
        WORKSPACE,
        nullable(uuid(event.projectId())),
        event.agentId(),
        nullable(uuid(event.workId())),
        event.activity(),
        at);
  }

  /** A workspace agent's session that ended by itself or was killed; the daemon's stops are none. */
  @Transactional(Transactional.TxType.REQUIRES_NEW)
  public void harnessExited(UUID eventId, AgentHarnessExited event, Instant occurredAt) {
    Instant at = micros(first(event.occurredAt(), occurredAt));
    if (before(at) || !EXITS.contains(event.stoppedBy()) || blankToNull(event.agentId()) == null) {
      return;
    }
    sql("insert into {h-schema}eval_exit (source_event_id, model, subject, work_id, at, detail)"
            + " values (?1, ?2, ?3, ?4, ?5, ?6) on conflict (source_event_id) do nothing",
        eventId,
        WORKSPACE,
        event.agentId(),
        nullable(uuid(event.workId())),
        at,
        event.stoppedBy() + " " + event.status() + " exit " + event.exitCode());
  }

  /** A workspace agent's wait closed. */
  @Transactional(Transactional.TxType.REQUIRES_NEW)
  public void waitClosed(AgentWaitClosed event, Instant occurredAt) {
    recordWait(event.waitId(), WORKSPACE, event.workId(), event.state(), first(event.occurredAt(), occurredAt));
  }

  /** A project agent's wait closed. */
  @Transactional(Transactional.TxType.REQUIRES_NEW)
  public void waitClosed(ProjectAgentWaitClosed event, Instant occurredAt) {
    recordWait(
        event.waitId() == null ? null : event.waitId().toString(),
        PROJECT_AGENT,
        event.workId(),
        event.state(),
        first(event.occurredAt(), occurredAt));
  }

  /** A workspace agent's transcript usage. */
  @Transactional(Transactional.TxType.REQUIRES_NEW)
  public void usageRecorded(UUID eventId, AgentUsageRecorded event, Instant occurredAt) {
    Instant at = micros(first(event.occurredAt(), occurredAt));
    if (before(at) || blankToNull(event.sessionId()) == null || blankToNull(event.model()) == null) {
      return;
    }
    sql("insert into {h-schema}eval_usage (source_event_id, model, project_id, work_id, session_id,"
            + " claude_model, messages, input_tokens, output_tokens, cache_creation_tokens,"
            + " cache_read_tokens, at) values (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12)"
            + " on conflict (source_event_id) do nothing",
        eventId,
        WORKSPACE,
        nullable(uuid(event.projectId())),
        nullable(uuid(event.workId())),
        event.sessionId(),
        event.model(),
        event.messages(),
        event.inputTokens(),
        event.outputTokens(),
        event.cacheCreationTokens(),
        event.cacheReadTokens(),
        at);
  }

  // --- helpers ---------------------------------------------------------------------------------

  private void recordWait(String waitId, String model, String workId, String state, Instant closedAt) {
    Instant at = micros(closedAt);
    if (before(at) || blankToNull(waitId) == null || state == null) {
      return;
    }
    sql("insert into {h-schema}eval_wait (wait_id, model, work_id, state, closed_at)"
            + " values (?1, ?2, ?3, ?4, ?5) on conflict (wait_id) do nothing",
        waitId,
        model,
        nullable(uuid(workId)),
        state,
        at);
  }

  /** The item's identity, upserted: a value once known is never cleared. */
  private void item(UUID workId, UUID projectId, String qualifiedId, String archetype) {
    sql("insert into {h-schema}eval_item (work_id, project_id, qualified_id, archetype)"
            + " values (?1, ?2, ?3, ?4) on conflict (work_id) do update set"
            + " project_id = coalesce(excluded.project_id, eval_item.project_id),"
            + " qualified_id = coalesce(excluded.qualified_id, eval_item.qualified_id),"
            + " archetype = coalesce(excluded.archetype, eval_item.archetype)",
        workId,
        nullable(projectId),
        nullable(qualifiedId),
        nullable(archetype));
  }

  /**
   * {@code <project slug>-<number>}: the event's own when it carries one, else the slug from the
   * project's agent rows, else from qits-projects' project list (asked once and remembered). Null
   * for a project neither knows.
   *
   * @throws ProjectDirectory.Unavailable when qits-projects must be asked and cannot be: the event
   *     stays owed and is offered again
   */
  String qualifiedId(UUID projectId, String given, long number) {
    if (given != null && !given.isBlank()) {
      return given;
    }
    if (projectId == null) {
      return null;
    }
    String slug = slugs.get(projectId);
    if (slug == null) {
      ProjectAgent agent = ProjectAgent.find("projectId", projectId).firstResult();
      if (agent != null) {
        slug = agent.projectSlug;
      } else {
        for (Map.Entry<String, UUID> project : directory.projectsBySlug().entrySet()) {
          slugs.putIfAbsent(project.getValue(), project.getKey());
        }
        slug = slugs.get(projectId);
      }
      if (slug != null) {
        slugs.put(projectId, slug);
      }
    }
    return slug == null ? null : slug + "-" + number;
  }

  private boolean before(Instant at) {
    return at.isBefore(since);
  }

  /**
   * Runs one native statement, binding {@code ?1…?n} in order. An argument that may be null is
   * passed through {@code nullable}, which carries its type: postgres cannot infer one from a bare
   * null.
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private static int sql(String statement, Object... parameters) {
    EntityManager em = EvalItem.getEntityManager();
    NativeQuery<?> query = em.createNativeQuery(statement).unwrap(NativeQuery.class);
    for (int i = 0; i < parameters.length; i++) {
      Object parameter = parameters[i];
      if (parameter instanceof Typed typed) {
        query.setParameter(i + 1, typed.value(), (Class) typed.type());
      } else if (parameter == null) {
        throw new IllegalArgumentException("parameter " + (i + 1) + " is null and untyped");
      } else {
        query.setParameter(i + 1, parameter);
      }
    }
    return query.executeUpdate();
  }

  /** A parameter that may be null, with the type postgres is to be told. */
  private record Typed(Object value, Class<?> type) {}

  private static Typed nullable(UUID value) {
    return new Typed(value, UUID.class);
  }

  private static Typed nullable(String value) {
    return new Typed(value, String.class);
  }

  private static Typed nullable(Instant value) {
    return new Typed(value, Instant.class);
  }

  private static Instant first(Instant a, Instant b) {
    return a != null ? a : b;
  }

  private static Instant instant(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException unreadable) {
      return null;
    }
  }

  static UUID uuid(String value) {
    return Assignments.Ids.uuid(value);
  }

  static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }

  /** Microseconds, as postgres stores them, so a read-back equals what was written. */
  static Instant micros(Instant at) {
    return (at == null ? Instant.now() : at).truncatedTo(ChronoUnit.MICROS);
  }
}
