package eu.wohlben.qits.agents.service.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.agents.service.entity.AgentTurn;
import eu.wohlben.qits.agents.service.entity.TurnKind;
import eu.wohlben.qits.agents.service.events.ProjectAgentWaitClosed;
import eu.wohlben.qits.agents.service.support.Fixtures;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code bus.WaitClosedListener}, driven with frames shaped as qits-workspaces publishes them
 * (plus a field this copy does not know), the way {@code ClaimsAndTurnsFromEventsTest} drives the
 * other two durable listeners. Called directly, without the library's claim on the event, so a
 * redelivery here proves {@code control.WaitNotices}' own idempotency.
 */
@QuarkusTest
class WaitClosedListenerTest {

  static final UUID DEVELOPER = UUID.fromString("d0000000-0000-4000-8000-000000000004");

  static final String DEVELOPER_SUBJECT = Fixtures.subject(Fixtures.PROJECT, "developer");

  static final Instant AT = Instant.parse("2026-11-24T09:00:00Z");

  @Inject WaitClosedListener listener;

  @Inject Fixtures fixtures;

  @Inject
  @DataSource("eventstream")
  AgroalDataSource eventstream;

  @BeforeEach
  void arrange() {
    fixtures.clear();
    Fixtures.recordEvents();
    fixtures.agent(DEVELOPER, "developer", 5);
  }

  static EventFrame waitClosed(UUID eventId, String state, String eventName, String matchedEventId) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("eventId", eventId.toString());
    p.put("waitId", UUID.randomUUID().toString());
    p.put("projectId", Fixtures.PROJECT_ID.toString());
    p.put("agentSubject", DEVELOPER_SUBJECT);
    p.put("workId", UUID.randomUUID().toString());
    p.put("workQualifiedId", "qits-2518");
    p.put("label", "ci green");
    p.put("state", state);
    p.put("eventName", eventName);
    p.put("matchedEventId", matchedEventId);
    p.put("reason", null);
    p.put("occurredAt", AT.toString());
    p.put("aFieldFromTheFuture", "ignored");
    return new EventFrame(
        eventId.toString(), "ProjectAgentWaitClosed", AT, CanonicalJson.canonicalize(p), null, null, "dev");
  }

  List<AgentTurn> turns() {
    return fixtures.turns(DEVELOPER);
  }

  @Test
  void aMatchedWaitQueuesTheMatchText() {
    listener.onFrame(
        waitClosed(
            UUID.randomUUID(),
            ProjectAgentWaitClosed.MATCHED,
            "BuildStatusChanged",
            "0f3c2a4e-5b6c-47d8-8e9f-a0b1c2d3e4f5"));

    List<AgentTurn> turns = turns();
    assertEquals(1, turns.size());
    assertEquals(TurnKind.WAIT, turns.get(0).kind);
    assertEquals(5, turns.get(0).generation);
    assertEquals(
        "(wait \"ci green\" for qits-2518 matched BuildStatusChanged"
            + " 0f3c2a4e-5b6c-47d8-8e9f-a0b1c2d3e4f5)",
        turns.get(0).text);
  }

  @Test
  void aRedeliveredEventQueuesNoSecondTurn() {
    EventFrame frame =
        waitClosed(
            UUID.randomUUID(), ProjectAgentWaitClosed.TIMED_OUT, null, null);
    listener.onFrame(frame);
    listener.onFrame(frame);

    assertEquals(1, turns().size());
    assertEquals("(timer \"ci green\" for qits-2518 is up)", turns().get(0).text);
  }

  @Test
  void anUnreadablePayloadIsSettledUnhandled() {
    listener.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(), "ProjectAgentWaitClosed", AT, "not json", null, null,
            "dev"));

    assertEquals(List.of(), turns());
  }

  /**
   * The way qits-eventstream really calls a durable listener: inside its claiming transaction,
   * with the eventstream outbox datasource already enlisted. 2026.1124.52642 joined that
   * transaction from the agents datasource and failed every event; a frames-only test could not
   * see it. {@code control.WaitNotices.waitClosed} follows the same {@code REQUIRES_NEW} rule as
   * {@code Assignments} and {@code CommentNotices}.
   */
  @Test
  void aListenerCalledInsideTheClaimingTransactionStillWrites() {
    UUID eventId = UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              enlistEventstream();
              listener.onFrame(
                  waitClosed(eventId, ProjectAgentWaitClosed.MATCHED, "BuildStatusChanged",
                      "0f3c2a4e-5b6c-47d8-8e9f-a0b1c2d3e4f5"));
            });

    assertEquals(1, turns().size());
    assertEquals(TurnKind.WAIT, turns().get(0).kind);
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
