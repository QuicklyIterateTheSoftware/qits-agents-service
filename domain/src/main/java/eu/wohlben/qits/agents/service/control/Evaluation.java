package eu.wohlben.qits.agents.service.control;

import static eu.wohlben.qits.agents.service.control.EvaluationLedger.PROJECT_AGENT;
import static eu.wohlben.qits.agents.service.control.EvaluationLedger.WORKSPACE;

import eu.wohlben.qits.agents.service.entity.AgentClaim;
import eu.wohlben.qits.agents.service.entity.EvalActivity;
import eu.wohlben.qits.agents.service.entity.EvalBlock;
import eu.wohlben.qits.agents.service.entity.EvalExit;
import eu.wohlben.qits.agents.service.entity.EvalItem;
import eu.wohlben.qits.agents.service.entity.EvalRequest;
import eu.wohlben.qits.agents.service.entity.EvalRequestState;
import eu.wohlben.qits.agents.service.entity.EvalRun;
import eu.wohlben.qits.agents.service.entity.EvalTransition;
import eu.wohlben.qits.agents.service.entity.EvalUsage;
import eu.wohlben.qits.agents.service.entity.EvalUsageMessage;
import eu.wohlben.qits.agents.service.entity.EvalWait;
import eu.wohlben.qits.agents.service.entity.ProjectAgent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * <b>The evaluation report</b> (epic qits-2521): the two delivery models, WORKSPACE and
 * PROJECT_AGENT, compared on the figures of the design page "Comparison criteria", derived from the
 * ledger {@link EvaluationLedger} writes. {@link #report} reads the project's ledger; {@link
 * #compute} is the pure derivation, over rows already read, so a test can seed it in memory.
 *
 * <p>The rules, in short:
 *
 * <ul>
 *   <li>An EPIC or TICKET is WORKSPACE's when an agent was placed on it at or after {@code since},
 *       PROJECT_AGENT's when it was assigned to a roster agent then; both is BOTH ("double-worked"),
 *       listed and left out of both models. {@code takenAt} is the earliest of the two; an item is in
 *       the window when that is before {@code until}.
 *   <li>Every status, block, request state, wait, exit, CI run and token after {@code until} is
 *       left out, so a past window always reports the same.
 *   <li>A release request is an item's when one of its branches is {@code ticket/<qid>} or {@code
 *       epic/<qid>}, or that followed by {@code -<anything>}. A CI run of the request is shared by
 *       all its {@code n} branches, attributed or not: each attributed item gets {@code 1/n} of the
 *       run and of its seconds. Only runs that reached a terminal state count.
 *   <li>Busy and idle are the project's agents' intervals in the window, clipped to it. A project
 *       agent is idle only while it holds an open claim; STARTING, EXITED and FAILED are down.
 *   <li>Percentiles are nearest-rank over whole seconds, null with no data; a per-verified figure is
 *       null with nothing verified.
 * </ul>
 */
@ApplicationScoped
public class Evaluation {

  /** The wait states, in the order the report lists them. */
  public static final List<String> WAIT_STATES =
      List.of("MATCHED", "TIMED_OUT", "BROKEN", "CANCELLED");

  /** The request states counted, in the order the report lists them. */
  public static final List<String> REQUEST_STATES =
      List.of("FINALIZED", "REJECTED", "CONFLICTED", "FAILED", "WITHDRAWN");

  public static final String BOTH = "BOTH";

  @Inject EvaluationLedger ledger;

  @Inject ProjectDirectory directory;

  // --- the shapes ------------------------------------------------------------------------------

  /** The whole report: one figure set per model, the double-worked items, and every item. */
  public record Report(
      String project,
      Instant since,
      Instant until,
      List<ModelFigures> models,
      List<String> doubleWorked,
      List<ItemFigures> items) {}

  /** One model's figures; see the class comment for the rules. */
  public record ModelFigures(
      String model,
      long taken,
      long refined,
      long verified,
      long dropped,
      double verifiedPerDay,
      Percentiles leadTimeSeconds,
      Percentiles implementSeconds,
      List<Tokens> tokens,
      List<Tokens> overheadTokens,
      PerVerified tokensPerVerified,
      long busySeconds,
      long idleSeconds,
      long downSeconds,
      double busyShare,
      Map<String, Long> waits,
      long blocks,
      Map<String, Long> requests,
      long sessionExits,
      double ciRuns,
      double ciRunsRed,
      double ciRunSeconds,
      Double ciRunSecondsPerVerified) {}

  /** p50 and p90, nearest-rank; null with no data. */
  public record Percentiles(Long p50, Long p90) {}

  /** The sums for one Claude model id. */
  public record Tokens(
      String claudeModel,
      long messages,
      long inputTokens,
      long outputTokens,
      long cacheCreationTokens,
      long cacheReadTokens) {}

  /** The four sums, divided by the model's verified items. */
  public record PerVerified(
      double inputTokens, double outputTokens, double cacheCreationTokens, double cacheReadTokens) {}

  /** One item of the window. */
  public record ItemFigures(
      String qualifiedId,
      String model,
      String archetype,
      Instant takenAt,
      Instant verifiedAt,
      Long leadTimeSeconds,
      long blocks,
      Map<String, Long> requests,
      double ciRuns,
      double ciRunSeconds,
      long inputTokens,
      long outputTokens,
      long cacheCreationTokens,
      long cacheReadTokens) {}

  /** A project, as the door names it. */
  public record Project(UUID id, String slug) {}

  /** The rows one report reads, the project's agents' claims and names included. */
  public record Ledger(
      List<EvalItem> items,
      List<EvalTransition> transitions,
      List<EvalBlock> blocks,
      List<EvalRequest> requests,
      List<EvalRequestState> requestStates,
      List<EvalRun> runs,
      List<EvalActivity> activity,
      List<EvalExit> exits,
      List<EvalWait> waits,
      List<EvalUsage> usage,
      List<EvalUsageMessage> messages,
      List<AgentClaim> claims,
      Map<UUID, String> agentNames) {}

  // --- reading ---------------------------------------------------------------------------------

  /** Where the ledger starts, the default {@code since}. */
  public Instant since() {
    return ledger.since();
  }

  /**
   * The project the door names, by id or slug: its agents' rows first, then qits-projects' list.
   * Empty for a project neither knows.
   */
  @Transactional
  public Optional<Project> project(String project) {
    UUID id = EvaluationLedger.uuid(project);
    ProjectAgent agent =
        id != null
            ? ProjectAgent.<ProjectAgent>find("projectId", id).firstResult()
            : ProjectAgent.<ProjectAgent>find("projectSlug", project).firstResult();
    if (agent != null) {
      return Optional.of(new Project(agent.projectId, agent.projectSlug));
    }
    Map<String, UUID> projects;
    try {
      projects = directory.projectsBySlug();
    } catch (ProjectDirectory.Unavailable unavailable) {
      return Optional.empty();
    }
    for (Map.Entry<String, UUID> entry : projects.entrySet()) {
      if (entry.getValue().equals(id) || entry.getKey().equals(project)) {
        return Optional.of(new Project(entry.getValue(), entry.getKey()));
      }
    }
    return Optional.empty();
  }

  /** The project's report for the window. */
  @Transactional
  public Report report(Project project, Instant since, Instant until) {
    List<EvalItem> items =
        EvalItem.list(
            "projectId = ?1 and archetype in ?2", project.id(), EvaluationLedger.EVALUATED);
    List<UUID> workIds = items.stream().map(i -> i.workId).toList();
    List<ProjectAgent> agents = ProjectAgent.list("projectId", project.id());
    List<UUID> agentIds = agents.stream().map(a -> a.id).toList();
    Map<UUID, String> names = new HashMap<>();
    agents.forEach(a -> names.put(a.id, a.name));
    List<EvalRequest> requests =
        EvalRequest.list("projectId = ?1 or projectId is null", project.id());
    List<String> requestIds = requests.stream().map(r -> r.requestId).toList();
    Ledger rows =
        new Ledger(
            items,
            workIds.isEmpty() ? List.of() : EvalTransition.list("workId in ?1", workIds),
            workIds.isEmpty() ? List.of() : EvalBlock.list("workId in ?1", workIds),
            requests,
            requestIds.isEmpty()
                ? List.of()
                : EvalRequestState.list("requestId in ?1", requestIds),
            requestIds.isEmpty() ? List.of() : EvalRun.list("requestId in ?1", requestIds),
            EvalActivity.list("projectId", project.id()),
            workIds.isEmpty() ? List.of() : EvalExit.list("workId in ?1", workIds),
            workIds.isEmpty() ? List.of() : EvalWait.list("workId in ?1", workIds),
            workIds.isEmpty() ? List.of() : EvalUsage.list("workId in ?1", workIds),
            agentIds.isEmpty() ? List.of() : EvalUsageMessage.list("agentId in ?1", agentIds),
            agentIds.isEmpty() ? List.of() : AgentClaim.list("agentId in ?1", agentIds),
            names);
    return compute(project.slug(), rows, since, until, Instant.now());
  }

  // --- the derivation --------------------------------------------------------------------------

  /** One item's facts, attributed. */
  private record Item(
      EvalItem row,
      String model,
      Instant takenAt,
      Instant verifiedAt,
      Instant refinedAt,
      Instant droppedAt,
      Instant implementingAt,
      long blocks,
      Map<String, Long> requests,
      double ciRuns,
      double ciRunsRed,
      double ciRunSeconds,
      Map<String, long[]> tokens,
      Map<String, Long> waits,
      long exits) {}

  /**
   * The report over rows already read. {@code now} bounds an interval still open at the end of a
   * window that reaches into the future.
   */
  public static Report compute(
      String slug, Ledger rows, Instant since, Instant until, Instant now) {
    Map<UUID, List<EvalTransition>> transitions = group(rows.transitions(), t -> t.workId);
    Map<UUID, List<EvalBlock>> blocks = group(rows.blocks(), b -> b.workId);
    Map<String, List<EvalRequestState>> requestStates =
        group(rows.requestStates(), s -> s.requestId);
    Map<String, List<EvalRun>> runs = group(rows.runs(), r -> r.requestId);
    Map<UUID, List<EvalExit>> exits = group(rows.exits(), e -> e.workId);
    Map<UUID, List<EvalWait>> waits = group(rows.waits(), w -> w.workId);
    Map<UUID, List<EvalUsage>> usage = group(rows.usage(), u -> u.workId);
    Map<String, List<EvalUsageMessage>> messages =
        group(
            rows.messages().stream().filter(m -> m.workQualifiedId != null).toList(),
            m -> m.workQualifiedId);

    // Which requests are which item's, by branch.
    Pattern branch = Pattern.compile("^(?:ticket|epic)/(" + Pattern.quote(slug) + "-[0-9]+)(?:-.*)?$");
    Map<String, List<EvalRequest>> requestsByItem = new HashMap<>();
    for (EvalRequest request : rows.requests()) {
      for (String name : request.branches == null ? new String[0] : request.branches) {
        Matcher matcher = branch.matcher(name);
        if (matcher.matches()) {
          List<EvalRequest> of =
              requestsByItem.computeIfAbsent(matcher.group(1), k -> new ArrayList<>());
          if (!of.contains(request)) {
            of.add(request);
          }
        }
      }
    }

    List<Item> items = new ArrayList<>();
    for (EvalItem row : rows.items()) {
      if (!EvaluationLedger.EVALUATED.contains(row.archetype)) {
        continue;
      }
      Instant workspace = atOrAfter(row.workspaceTakenAt, since);
      Instant agent = atOrAfter(row.agentTakenAt, since);
      if (workspace == null && agent == null) {
        continue;
      }
      String model = workspace != null && agent != null ? BOTH : workspace != null ? WORKSPACE : PROJECT_AGENT;
      Instant taken = workspace == null ? agent : agent == null ? workspace : min(workspace, agent);
      if (!taken.isBefore(until)) {
        continue;
      }
      List<EvalTransition> moves = transitions.getOrDefault(row.workId, List.of());
      Instant verified =
          minOrNull(
              firstInto(moves, "VERIFIED", taken, until), firstInto(moves, "DONE", taken, until));
      Instant implementing = firstInto(moves, "IMPLEMENTING", Instant.MIN, until);

      long blockCount =
          blocks.getOrDefault(row.workId, List.of()).stream()
              .filter(b -> within(b.at, since, until))
              .count();

      Map<String, Long> requestCounts = new LinkedHashMap<>();
      double ciRuns = 0;
      double ciRunsRed = 0;
      double ciSeconds = 0;
      for (EvalRequest request : requestsByItem.getOrDefault(row.qualifiedId, List.of())) {
        for (EvalRequestState state : requestStates.getOrDefault(request.requestId, List.of())) {
          if (REQUEST_STATES.contains(state.state) && state.at.isBefore(until)) {
            requestCounts.merge(state.state, 1L, Long::sum);
          }
        }
        int n = request.branches.length;
        for (EvalRun run : runs.getOrDefault(request.requestId, List.of())) {
          if (run.terminalAt == null || !run.terminalAt.isBefore(until)) {
            continue;
          }
          ciRuns += 1.0 / n;
          if (!"SUCCESS".equals(run.terminalStatus)) {
            ciRunsRed += 1.0 / n;
          }
          ciSeconds += seconds(run.firstAt, run.terminalAt) / (double) n;
        }
      }

      Map<String, long[]> tokens = new TreeMap<>();
      for (EvalUsage u : usage.getOrDefault(row.workId, List.of())) {
        if (u.at.isBefore(until)) {
          add(tokens, u.claudeModel, u.messages, u.inputTokens, u.outputTokens, u.cacheCreationTokens, u.cacheReadTokens);
        }
      }
      if (row.qualifiedId != null) {
        for (EvalUsageMessage m : messages.getOrDefault(row.qualifiedId, List.of())) {
          if (m.at.isBefore(until)) {
            add(tokens, m.claudeModel, 1, m.inputTokens, m.outputTokens, m.cacheCreationTokens, m.cacheReadTokens);
          }
        }
      }

      Map<String, Long> waitCounts = new LinkedHashMap<>();
      for (EvalWait wait : waits.getOrDefault(row.workId, List.of())) {
        if (wait.closedAt.isBefore(until)) {
          waitCounts.merge(wait.state, 1L, Long::sum);
        }
      }
      long exitCount =
          exits.getOrDefault(row.workId, List.of()).stream()
              .filter(e -> within(e.at, since, until))
              .count();

      items.add(
          new Item(
              row,
              model,
              taken,
              verified,
              firstInto(moves, "REFINED", taken, until),
              firstInto(moves, "DROPPED", taken, until),
              implementing,
              blockCount,
              requestCounts,
              ciRuns,
              ciRunsRed,
              ciSeconds,
              tokens,
              waitCounts,
              exitCount));
    }
    items.sort(Comparator.comparing(Item::takenAt).thenComparing(i -> String.valueOf(i.row().qualifiedId)));

    Instant end = until.isAfter(now) ? now : until;
    double days = Duration.between(since, until).getSeconds() / 86400.0;
    Set<String> listed =
        items.stream()
            .map(i -> i.row().qualifiedId)
            .filter(java.util.Objects::nonNull)
            .collect(Collectors.toSet());
    List<ModelFigures> models = new ArrayList<>();
    for (String model : List.of(WORKSPACE, PROJECT_AGENT)) {
      List<Item> mine = items.stream().filter(i -> i.model().equals(model)).toList();
      models.add(figures(model, mine, listed, rows, since, until, end, days));
    }
    List<String> doubleWorked =
        items.stream().filter(i -> i.model().equals(BOTH)).map(i -> i.row().qualifiedId).toList();
    List<ItemFigures> itemFigures = items.stream().map(Evaluation::itemFigures).toList();
    return new Report(slug, since, until, models, doubleWorked, itemFigures);
  }

  /**
   * @param listed the qualified ids of every item the report lists, of any model: a project
   *     agent's message naming none of them (an item it only researched, or one not evaluated) is
   *     its overhead, never dropped (qits-2722)
   */
  private static ModelFigures figures(
      String model, List<Item> items, Set<String> listed, Ledger rows, Instant since,
      Instant until, Instant end, double days) {
    List<Item> verified = items.stream().filter(i -> i.verifiedAt() != null).toList();
    List<Long> leads =
        verified.stream().map(i -> seconds(i.takenAt(), i.verifiedAt())).toList();
    List<Long> implement =
        verified.stream()
            .filter(i -> i.implementingAt() != null && !i.implementingAt().isAfter(i.verifiedAt()))
            .map(i -> seconds(i.implementingAt(), i.verifiedAt()))
            .toList();

    Map<String, long[]> tokens = new TreeMap<>();
    items.forEach(i -> i.tokens().forEach((claude, sums) -> add(tokens, claude, sums)));
    Map<String, long[]> overhead = new TreeMap<>();
    if (PROJECT_AGENT.equals(model)) {
      for (EvalUsageMessage m : rows.messages()) {
        if ((m.workQualifiedId == null || !listed.contains(m.workQualifiedId))
            && within(m.at, since, until)) {
          add(overhead, m.claudeModel, 1, m.inputTokens, m.outputTokens, m.cacheCreationTokens, m.cacheReadTokens);
        }
      }
    }
    long n = verified.size();
    PerVerified perVerified = null;
    if (n > 0) {
      long[] all = new long[5];
      tokens.values().forEach(s -> sum(all, s));
      overhead.values().forEach(s -> sum(all, s));
      perVerified =
          new PerVerified(
              all[1] / (double) n, all[2] / (double) n, all[3] / (double) n, all[4] / (double) n);
    }

    long[] time = time(model, rows, since, end);

    Map<String, Long> waits = zeros(WAIT_STATES);
    items.forEach(i -> i.waits().forEach((state, count) -> waits.merge(state, count, Long::sum)));
    Map<String, Long> requests = zeros(REQUEST_STATES);
    items.forEach(i -> i.requests().forEach((state, count) -> requests.merge(state, count, Long::sum)));
    long exits =
        WORKSPACE.equals(model)
            ? items.stream().mapToLong(Item::exits).sum()
            : projectAgentExits(rows, since, until);
    double ciRuns = items.stream().mapToDouble(Item::ciRuns).sum();
    double ciSeconds = items.stream().mapToDouble(Item::ciRunSeconds).sum();
    return new ModelFigures(
        model,
        items.size(),
        items.stream().filter(i -> i.refinedAt() != null).count(),
        n,
        items.stream().filter(i -> i.droppedAt() != null).count(),
        days <= 0 ? 0.0 : n / days,
        percentiles(leads),
        percentiles(implement),
        tokenList(tokens),
        tokenList(overhead),
        perVerified,
        time[0],
        time[1],
        time[2],
        time[0] + time[1] == 0 ? 0.0 : time[0] / (double) (time[0] + time[1]),
        waits,
        items.stream().mapToLong(Item::blocks).sum(),
        requests,
        exits,
        ciRuns,
        items.stream().mapToDouble(Item::ciRunsRed).sum(),
        ciSeconds,
        n == 0 ? null : ciSeconds / n);
  }

  private static ItemFigures itemFigures(Item item) {
    long[] all = new long[5];
    item.tokens().values().forEach(s -> sum(all, s));
    return new ItemFigures(
        item.row().qualifiedId,
        item.model(),
        item.row().archetype,
        item.takenAt(),
        item.verifiedAt(),
        item.verifiedAt() == null ? null : seconds(item.takenAt(), item.verifiedAt()),
        item.blocks(),
        item.requests(),
        item.ciRuns(),
        item.ciRunSeconds(),
        all[1],
        all[2],
        all[3],
        all[4]);
  }

  // --- busy, idle, down ------------------------------------------------------------------------

  /** busy, idle and down seconds of the model's agents in [since, end). */
  private static long[] time(String model, Ledger rows, Instant since, Instant end) {
    long[] out = new long[3];
    Map<String, List<EvalActivity>> bySubject =
        group(rows.activity().stream().filter(a -> a.model.equals(model)).toList(), a -> a.subject);
    Map<String, List<Instant[]>> claims = new HashMap<>();
    for (AgentClaim claim : rows.claims()) {
      String name = rows.agentNames().get(claim.agentId);
      if (name != null) {
        claims
            .computeIfAbsent(name, k -> new ArrayList<>())
            .add(new Instant[] {claim.claimedAt, claim.releasedAt == null ? Instant.MAX : claim.releasedAt});
      }
    }
    for (Map.Entry<String, List<EvalActivity>> subject : bySubject.entrySet()) {
      List<EvalActivity> states = sorted(subject.getValue());
      for (int i = 0; i < states.size(); i++) {
        EvalActivity state = states.get(i);
        if ("ENDED".equals(state.state)) {
          continue;
        }
        Instant from = max(state.at, since);
        Instant to = min(i + 1 < states.size() ? states.get(i + 1).at : end, end);
        if (!from.isBefore(to)) {
          continue;
        }
        if (WORKSPACE.equals(model)) {
          switch (state.state) {
            case "BUSY" -> out[0] += millis(from, to);
            case "IDLE", "WAITING" -> out[1] += millis(from, to);
            default -> {}
          }
        } else {
          switch (state.state) {
            case "BUSY" -> out[0] += millis(from, to);
            case "IDLE" ->
                out[1] += overlap(from, to, claims.getOrDefault(subject.getKey(), List.of()));
            case "STARTING", "EXITED", "FAILED" -> out[2] += millis(from, to);
            default -> {}
          }
        }
      }
    }
    return new long[] {round(out[0]), round(out[1]), round(out[2])};
  }

  /** How many times a project agent entered EXITED or FAILED in the window. */
  private static long projectAgentExits(Ledger rows, Instant since, Instant until) {
    long exits = 0;
    Map<String, List<EvalActivity>> bySubject =
        group(
            rows.activity().stream().filter(a -> a.model.equals(PROJECT_AGENT)).toList(),
            a -> a.subject);
    for (List<EvalActivity> states : bySubject.values()) {
      String before = null;
      for (EvalActivity state : sorted(states)) {
        boolean down = "EXITED".equals(state.state) || "FAILED".equals(state.state);
        if (down && !state.state.equals(before) && within(state.at, since, until)) {
          exits++;
        }
        before = state.state;
      }
    }
    return exits;
  }

  /** Milliseconds of [from, to) covered by the union of the claims. */
  private static long overlap(Instant from, Instant to, List<Instant[]> claims) {
    List<Instant[]> cut = new ArrayList<>();
    for (Instant[] claim : claims) {
      Instant a = max(claim[0], from);
      Instant b = min(claim[1], to);
      if (a.isBefore(b)) {
        cut.add(new Instant[] {a, b});
      }
    }
    cut.sort(Comparator.comparing(c -> c[0]));
    long total = 0;
    Instant reached = from;
    for (Instant[] c : cut) {
      Instant a = max(c[0], reached);
      if (a.isBefore(c[1])) {
        total += millis(a, c[1]);
        reached = c[1];
      }
    }
    return total;
  }

  // --- small helpers ---------------------------------------------------------------------------

  /** Nearest-rank: the value at rank ceil(p * n) of the sorted values. */
  static Percentiles percentiles(List<Long> values) {
    if (values.isEmpty()) {
      return new Percentiles(null, null);
    }
    List<Long> sorted = values.stream().sorted().toList();
    return new Percentiles(rank(sorted, 50), rank(sorted, 90));
  }

  /** The value at rank ceil(percent / 100 * n), in integers so no rounding moves the rank. */
  private static Long rank(List<Long> sorted, int percent) {
    int rank = (percent * sorted.size() + 99) / 100;
    return sorted.get(Math.max(rank, 1) - 1);
  }

  private static Instant firstInto(
      List<EvalTransition> moves, String status, Instant from, Instant until) {
    return moves.stream()
        .filter(t -> t.status.equals(status) && !t.at.isBefore(from) && t.at.isBefore(until))
        .map(t -> t.at)
        .min(Comparator.naturalOrder())
        .orElse(null);
  }

  private static void add(
      Map<String, long[]> tokens, String claude, long messages, long input, long output,
      long creation, long read) {
    add(tokens, claude, new long[] {messages, input, output, creation, read});
  }

  private static void add(Map<String, long[]> tokens, String claude, long[] sums) {
    sum(tokens.computeIfAbsent(claude, k -> new long[5]), sums);
  }

  private static void sum(long[] into, long[] sums) {
    for (int i = 0; i < 5; i++) {
      into[i] += sums[i];
    }
  }

  private static List<Tokens> tokenList(Map<String, long[]> tokens) {
    return tokens.entrySet().stream()
        .map(e -> new Tokens(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2], e.getValue()[3], e.getValue()[4]))
        .toList();
  }

  private static Map<String, Long> zeros(List<String> keys) {
    Map<String, Long> out = new LinkedHashMap<>();
    keys.forEach(k -> out.put(k, 0L));
    return out;
  }

  private static List<EvalActivity> sorted(List<EvalActivity> states) {
    return states.stream()
        .sorted(Comparator.comparing((EvalActivity a) -> a.at).thenComparing(a -> a.sourceEventId))
        .toList();
  }

  private static <K, V> Map<K, List<V>> group(
      Collection<V> rows, java.util.function.Function<V, K> key) {
    return rows.stream()
        .filter(r -> key.apply(r) != null)
        .collect(Collectors.groupingBy(key, HashMap::new, Collectors.toList()));
  }

  private static boolean within(Instant at, Instant since, Instant until) {
    return !at.isBefore(since) && at.isBefore(until);
  }

  private static Instant atOrAfter(Instant at, Instant since) {
    return at == null || at.isBefore(since) ? null : at;
  }

  /** Whole seconds between two instants, rounded. */
  static long seconds(Instant from, Instant to) {
    return round(millis(from, to));
  }

  private static long millis(Instant from, Instant to) {
    return Duration.between(from, to).toMillis();
  }

  private static long round(long millis) {
    return Math.round(millis / 1000.0);
  }

  private static Instant min(Instant a, Instant b) {
    return a.isBefore(b) ? a : b;
  }

  private static Instant max(Instant a, Instant b) {
    return a.isAfter(b) ? a : b;
  }

  private static Instant minOrNull(Instant a, Instant b) {
    return a == null ? b : b == null ? a : min(a, b);
  }
}
