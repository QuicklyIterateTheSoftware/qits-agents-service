package eu.wohlben.qits.agents.service.bus;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.agents.service.control.Agents;
import eu.wohlben.qits.agents.service.entity.EvalActivity;
import eu.wohlben.qits.agents.service.entity.EvalBlock;
import eu.wohlben.qits.agents.service.entity.EvalExit;
import eu.wohlben.qits.agents.service.entity.EvalItem;
import eu.wohlben.qits.agents.service.entity.EvalRequest;
import eu.wohlben.qits.agents.service.entity.EvalRequestState;
import eu.wohlben.qits.agents.service.entity.EvalRun;
import eu.wohlben.qits.agents.service.entity.EvalTransition;
import eu.wohlben.qits.agents.service.entity.EvalUsage;
import eu.wohlben.qits.agents.service.entity.EvalWait;
import eu.wohlben.qits.agents.service.entity.ReportedState;
import eu.wohlben.qits.agents.service.support.Fixtures;
import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventFrame;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The evaluation ledger's consumptions (qits-2521), each driven with a frame shaped as its producer
 * publishes it (plus a field this copy does not know) and called directly, without the library's
 * claim on the event, so a redelivery here proves the ledger's own idempotency. Every consumption
 * replays from the epoch, and an event older than {@code qits.agents.evaluation.since} is settled
 * without a write.
 */
@QuarkusTest
class EvaluationListenersTest {

  static final UUID DEVELOPER = UUID.fromString("d0000000-0000-4000-8000-000000000021");

  static final UUID TICKET = UUID.fromString("e0000000-0000-4000-8000-000000000021");

  static final Instant AT = Instant.parse("2026-11-25T10:00:00.123456Z");

  static final Instant BEFORE = Instant.parse("2026-11-24T23:59:59Z");

  @Inject EvalTransitionsListener transitions;

  @Inject EvalAssignmentsListener assignments;

  @Inject EvalBlocksListener blocks;

  @Inject EvalRequestsListener requests;

  @Inject EvalBuildsListener builds;

  @Inject EvalPlacementsListener placements;

  @Inject EvalActivityListener activity;

  @Inject EvalExitsListener exits;

  @Inject EvalWaitsListener waits;

  @Inject EvalUsageListener usage;

  @Inject Agents agents;

  @Inject Fixtures fixtures;

  @Inject
  @DataSource("eventstream")
  AgroalDataSource eventstream;

  @BeforeEach
  void arrange() {
    fixtures.clear();
    Fixtures.recordEvents();
    fixtures.agent(DEVELOPER, "developer", 1);
  }

  static EventFrame frame(String name, Instant occurredAt, Map<String, Object> payload) {
    payload.put("aFieldFromTheFuture", "ignored");
    return new EventFrame(
        String.valueOf(payload.getOrDefault("eventId", UUID.randomUUID())),
        name,
        occurredAt,
        CanonicalJson.canonicalize(payload),
        null,
        null,
        "dev");
  }

  static Map<String, Object> payload(Object... pairs) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("eventId", UUID.randomUUID().toString());
    for (int i = 0; i < pairs.length; i += 2) {
      p.put((String) pairs[i], pairs[i + 1]);
    }
    return p;
  }

  static EventFrame transitioned(String archetype, String status, String before, Instant at) {
    Map<String, Object> entity = new LinkedHashMap<>();
    entity.put("entityId", TICKET.toString());
    entity.put("projectId", Fixtures.PROJECT_ID.toString());
    entity.put("archetype", archetype);
    entity.put("slug", "ticket");
    entity.put("title", "A ticket");
    entity.put("status", status);
    entity.put("statusBefore", before);
    entity.put("changedBy", "ben");
    entity.put("number", 2565);
    return frame(
        "EntityTransitioned",
        at,
        payload("entities", List.of(entity), "transitionedAt", at.toString()));
  }

  static EventFrame assigned(String assignee, Instant at) {
    return frame(
        "EntityAssigned",
        at,
        payload(
            "entityId", TICKET.toString(),
            "projectId", Fixtures.PROJECT_ID.toString(),
            "archetype", "TICKET",
            "number", 2565,
            "qualifiedId", "qits-2565",
            "assignee", assignee,
            "claimed", true,
            "changedBy", "ben",
            "assignedAt", at.toString()));
  }

  static EventFrame placed(Instant at) {
    return frame(
        "AgentPlaced",
        at,
        payload(
            "agentId", "agent-1",
            "workId", TICKET.toString(),
            "workspaceId", "ws-1",
            "repositoryId", "repo-1",
            "occurredAt", at.toString()));
  }

  <T> List<T> rows(Class<T> type) {
    return fixtures.rows(type);
  }

  EvalItem item() {
    List<EvalItem> items = rows(EvalItem.class);
    assertEquals(1, items.size());
    return items.get(0);
  }

  @Test
  void everyConsumptionReplaysFromTheEpochUnderItsOwnId() {
    List<QitsDurableEventListener> all =
        List.of(transitions, assignments, blocks, requests, builds, placements, activity, exits,
            waits, usage);
    assertTrue(all.stream().allMatch(QitsDurableEventListener::replayFromEpoch));
    assertEquals(
        List.of(
            "eval-transitions", "eval-assignments", "eval-blocks", "eval-requests", "eval-builds",
            "eval-placements", "eval-activity", "eval-exits", "eval-waits", "eval-usage"),
        all.stream().map(QitsDurableEventListener::consumerId).toList());
  }

  @Test
  void aTransitionRecordsTheItemAndTheStatusItEntered() {
    EventFrame frame = transitioned("TICKET", "IMPLEMENTING", "READY_FOR_DEV", AT);
    transitions.onFrame(frame);
    transitions.onFrame(frame); // redelivered
    transitions.onFrame(transitioned("TICKET", "IMPLEMENTING", "IMPLEMENTING", AT.plusSeconds(60)));

    EvalItem item = item();
    assertEquals(TICKET, item.workId);
    assertEquals(Fixtures.PROJECT_ID, item.projectId);
    assertEquals("qits-2565", item.qualifiedId, "the slug from the project's agent rows");
    assertEquals("TICKET", item.archetype);
    List<EvalTransition> moves = rows(EvalTransition.class);
    assertEquals(1, moves.size(), "a reshape without a status move is no transition");
    assertEquals("IMPLEMENTING", moves.get(0).status);
    assertEquals(AT, moves.get(0).at);
  }

  @Test
  void aTaskAnOldEventAndAnUnreadablePayloadWriteNothing() {
    transitions.onFrame(transitioned("TASK", "IMPLEMENTING", "READY_FOR_DEV", AT));
    transitions.onFrame(transitioned("TICKET", "IMPLEMENTING", "READY_FOR_DEV", BEFORE));
    transitions.onFrame(
        new EventFrame(UUID.randomUUID().toString(), "EntityTransitioned", AT, "not json", null,
            null, "dev"));
    assertEquals(List.of(), rows(EvalItem.class));
    assertEquals(List.of(), rows(EvalTransition.class));
  }

  @Test
  void anAssignmentToARosterAgentIsTheProjectAgentsTakeTheFirstOneWins() {
    assignments.onFrame(assigned("ben", AT.minusSeconds(60))); // a person: not taken
    assignments.onFrame(assigned("developer", AT.plusSeconds(60)));
    assignments.onFrame(assigned(Fixtures.subject("qits", "developer"), AT));

    EvalItem item = item();
    assertEquals(AT, item.agentTakenAt);
    assertEquals("developer", item.agentName);
    assertEquals("qits-2565", item.qualifiedId);
    assertNull(item.workspaceTakenAt);
  }

  @Test
  void aPlacementIsTheWorkspacesTakeTheFirstOneWins() {
    placements.onFrame(placed(AT.plusSeconds(60)));
    placements.onFrame(placed(AT));
    placements.onFrame(placed(BEFORE));

    EvalItem item = item();
    assertEquals(AT, item.workspaceTakenAt);
    assertNull(item.projectId, "named by a qits-projects event later");
    transitions.onFrame(transitioned("TICKET", "IMPLEMENTING", "READY_FOR_DEV", AT));
    assertEquals("qits-2565", item().qualifiedId);
    assertEquals(AT, item().workspaceTakenAt);
  }

  @Test
  void aBlockSetCountsAndAClearedOneDoesNot() {
    Map<String, Object> set =
        payload(
            "entityId", TICKET.toString(),
            "projectId", Fixtures.PROJECT_ID.toString(),
            "archetype", "TICKET",
            "number", 2565,
            "qualifiedId", "qits-2565",
            "status", "IMPLEMENTING",
            "blocked", true,
            "reason", "waiting for the owner",
            "changedAt", AT.toString());
    EventFrame frame = frame("EntityBlockChanged", AT, set);
    blocks.onFrame(frame);
    blocks.onFrame(frame);
    Map<String, Object> cleared = new LinkedHashMap<>(set);
    cleared.put("eventId", UUID.randomUUID().toString());
    cleared.put("blocked", false);
    blocks.onFrame(frame("EntityBlockChanged", AT, cleared));

    List<EvalBlock> rows = rows(EvalBlock.class);
    assertEquals(1, rows.size());
    assertEquals(TICKET, rows.get(0).workId);
  }

  static EventFrame request(String state, Instant at, List<String> branches) {
    Map<String, Object> p =
        payload(
            "releaseRequestId", "rr-7",
            "releaseRequestQualifiedId", "qits-rr-7",
            "projectId", Fixtures.PROJECT_ID.toString(),
            "repoName", "qits-agents-service",
            "state", state,
            "changedAt", at.toString());
    p.put("branches", branches);
    return frame("ReleaseRequestStateChanged", at, p);
  }

  @Test
  void aRequestKeepsItsBranchesAndTheFirstEntryOfEachState() {
    requests.onFrame(request("OPEN", AT, List.of("ticket/qits-2565", "maintenance/bump")));
    requests.onFrame(request("CONFLICTED", AT.plusSeconds(60), null));
    requests.onFrame(request("CONFLICTED", AT.plusSeconds(120), List.of("ticket/qits-2565")));

    EvalRequest request = rows(EvalRequest.class).get(0);
    assertEquals("CONFLICTED", request.state);
    assertArrayEquals(new String[] {"ticket/qits-2565"}, request.branches);
    assertEquals(AT.plusSeconds(120), request.changedAt);
    List<EvalRequestState> states =
        rows(EvalRequestState.class).stream()
            .sorted(Comparator.comparing(s -> s.at))
            .toList();
    assertEquals(List.of("OPEN", "CONFLICTED"), states.stream().map(s -> s.state).toList());
    assertEquals(AT.plusSeconds(60), states.get(1).at, "the first entry");
  }

  static EventFrame build(String status, Instant envelope) {
    // occurredAt rides the envelope only, as qits-ci may send it.
    return frame(
        "BuildStatusChanged",
        envelope,
        payload(
            "runId", "run-7",
            "projectId", Fixtures.PROJECT_ID.toString(),
            "releaseRequestId", "rr-7",
            "status", status));
  }

  @Test
  void aRunKeepsItsFirstEventAndItsTerminalOne() {
    builds.onFrame(build("RUNNING", AT.plusSeconds(10)));
    builds.onFrame(build("QUEUED", AT));
    builds.onFrame(build("FAILED", AT.plusSeconds(300)));

    EvalRun run = rows(EvalRun.class).get(0);
    assertEquals(AT, run.firstAt);
    assertEquals(AT.plusSeconds(300), run.terminalAt);
    assertEquals("FAILED", run.terminalStatus);
    assertEquals("rr-7", run.requestId);
  }

  @Test
  void activityExitsWaitsAndUsageAreRecordedOnce() {
    EventFrame busy =
        frame(
            "AgentActivityChanged",
            AT,
            payload(
                "agentId", "agent-1",
                "workId", TICKET.toString(),
                "projectId", Fixtures.PROJECT_ID.toString(),
                "activity", "BUSY",
                "at", AT.toString()));
    activity.onFrame(busy);
    activity.onFrame(busy);
    exits.onFrame(
        frame(
            "AgentHarnessExited",
            AT,
            payload("agentId", "agent-1", "workId", TICKET.toString(), "status", "EXITED",
                "exitCode", 1, "stoppedBy", "SELF")));
    exits.onFrame(
        frame(
            "AgentHarnessExited",
            AT,
            payload("agentId", "agent-1", "workId", TICKET.toString(), "status", "EXITED",
                "exitCode", 0, "stoppedBy", "DAEMON_YIELD")));
    waits.onFrame(
        frame(
            "AgentWaitClosed",
            AT,
            payload("agentId", "agent-1", "workId", TICKET.toString(), "waitId", "wait-1",
                "state", "BROKEN", "occurredAt", AT.toString())));
    waits.onFrame(
        frame(
            "ProjectAgentWaitClosed",
            AT,
            payload("waitId", UUID.randomUUID().toString(), "workId", TICKET.toString(),
                "state", "TIMED_OUT", "occurredAt", AT.toString())));
    EventFrame used =
        frame(
            "AgentUsageRecorded",
            AT,
            payload(
                "agentId", "agent-1",
                "workId", TICKET.toString(),
                "projectId", Fixtures.PROJECT_ID.toString(),
                "sessionId", "session-1",
                "model", "claude-opus-5-5",
                "messages", 3,
                "inputTokens", 10,
                "outputTokens", 20,
                "cacheReadTokens", 40,
                "occurredAt", AT.toString()));
    usage.onFrame(used);
    usage.onFrame(used);

    EvalActivity state = rows(EvalActivity.class).get(0);
    assertEquals(1, rows(EvalActivity.class).size());
    assertEquals("WORKSPACE", state.model);
    assertEquals("agent-1", state.subject);
    assertEquals("BUSY", state.state);
    assertEquals(TICKET, state.workId);
    List<EvalExit> exited = rows(EvalExit.class);
    assertEquals(1, exited.size(), "a daemon's yield is no session exit");
    assertEquals(
        List.of("PROJECT_AGENT:TIMED_OUT", "WORKSPACE:BROKEN"),
        rows(EvalWait.class).stream().map(w -> w.model + ":" + w.state).sorted().toList());
    List<EvalUsage> usages = rows(EvalUsage.class);
    assertEquals(1, usages.size());
    assertEquals(3, usages.get(0).messages);
    assertEquals(0, usages.get(0).cacheCreationTokens);
    assertEquals(40, usages.get(0).cacheReadTokens);
  }

  @Test
  void aProjectAgentsReportedStateIsRecordedWhereItIsPublished() {
    agents.report(DEVELOPER, 1, ReportedState.BUSY, "session-1", null);
    agents.report(DEVELOPER, 1, ReportedState.BUSY, "session-1", null); // no move, no row
    agents.report(DEVELOPER, 1, ReportedState.EXITED, "session-1", "crashed");

    List<EvalActivity> states =
        rows(EvalActivity.class).stream().sorted(Comparator.comparing(a -> a.at)).toList();
    assertEquals(List.of("BUSY", "EXITED"), states.stream().map(a -> a.state).toList());
    assertEquals("PROJECT_AGENT", states.get(0).model);
    assertEquals("developer", states.get(0).subject);
    assertEquals(Fixtures.PROJECT_ID, states.get(0).projectId);
  }

  /** As qits-eventstream really calls a listener: inside its claiming transaction (see AGENTS.md). */
  @Test
  void aListenerCalledInsideTheClaimingTransactionStillWrites() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              enlistEventstream();
              transitions.onFrame(transitioned("TICKET", "VERIFIED", "IMPLEMENTED", AT));
            });
    assertEquals(1, rows(EvalTransition.class).size());
  }

  private void enlistEventstream() {
    try (Connection connection = eventstream.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("select 1");
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }
}
