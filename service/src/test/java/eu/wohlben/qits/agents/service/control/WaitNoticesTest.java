package eu.wohlben.qits.agents.service.control;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.agents.service.api.Roles;
import eu.wohlben.qits.agents.service.entity.AgentTurn;
import eu.wohlben.qits.agents.service.entity.TurnKind;
import eu.wohlben.qits.agents.service.events.ProjectAgentWaitClosed;
import eu.wohlben.qits.agents.service.support.ContractCallers;
import eu.wohlben.qits.agents.service.support.Fixtures;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code control.WaitNotices}: WAIT turns from qits-workspaces' {@code ProjectAgentWaitClosed}
 * (qits-2597, epic qits-2520). Each test builds the typed event directly, exactly as {@code
 * bus.WaitClosedListener} hands it over once decoded.
 */
@QuarkusTest
class WaitNoticesTest {

  static final UUID DEVELOPER = UUID.fromString("d0000000-0000-4000-8000-000000000003");

  static final String DEVELOPER_SUBJECT = Fixtures.subject(Fixtures.PROJECT, "developer");

  static final Instant AT = Instant.parse("2026-11-24T08:00:00Z");

  @Inject WaitNotices notices;

  @Inject Fixtures fixtures;

  @BeforeEach
  void arrange() {
    fixtures.clear();
    Fixtures.recordEvents();
    fixtures.agent(DEVELOPER, "developer", 4);
  }

  static ProjectAgentWaitClosed event(
      UUID eventId, String subject, String state, String eventName, String matchedEventId,
      String reason) {
    return new ProjectAgentWaitClosed(
        eventId,
        UUID.randomUUID(),
        Fixtures.PROJECT_ID.toString(),
        subject,
        UUID.randomUUID().toString(),
        "qits-2518",
        "ci green",
        state,
        eventName,
        matchedEventId,
        reason,
        AT);
  }

  static ProjectAgentWaitClosed event(
      UUID eventId, String state, String eventName, String matchedEventId, String reason) {
    return event(eventId, DEVELOPER_SUBJECT, state, eventName, matchedEventId, reason);
  }

  List<AgentTurn> turns() {
    return fixtures.turns(DEVELOPER);
  }

  @Test
  void matchedWithAnEventQueuesTheMatchText() {
    notices.waitClosed(
        event(
            UUID.randomUUID(),
            ProjectAgentWaitClosed.MATCHED,
            "BuildStatusChanged",
            "0f3c2a4e-5b6c-47d8-8e9f-a0b1c2d3e4f5",
            null));

    List<AgentTurn> turns = turns();
    assertEquals(1, turns.size());
    assertEquals(TurnKind.WAIT, turns.get(0).kind);
    assertEquals(4, turns.get(0).generation, "the agent's current generation");
    assertEquals(
        "(wait \"ci green\" for qits-2518 matched BuildStatusChanged"
            + " 0f3c2a4e-5b6c-47d8-8e9f-a0b1c2d3e4f5)",
        turns.get(0).text);
  }

  @Test
  void matchedWithNoMatchedEventIdQueuesTheLookupText() {
    notices.waitClosed(event(UUID.randomUUID(), ProjectAgentWaitClosed.MATCHED, null, null, null));

    assertEquals("(wait \"ci green\" for qits-2518 matched on lookup)", turns().get(0).text);
  }

  @Test
  void timedOutWithNoEventNameIsATimer() {
    notices.waitClosed(event(UUID.randomUUID(), ProjectAgentWaitClosed.TIMED_OUT, null, null, null));

    assertEquals("(timer \"ci green\" for qits-2518 is up)", turns().get(0).text);
  }

  @Test
  void timedOutWithAnEventNameTimedOut() {
    notices.waitClosed(
        event(UUID.randomUUID(), ProjectAgentWaitClosed.TIMED_OUT, "BuildStatusChanged", null, null));

    assertEquals("(wait \"ci green\" for qits-2518 timed out)", turns().get(0).text);
  }

  @Test
  void brokenNamesTheReason() {
    notices.waitClosed(
        event(
            UUID.randomUUID(), ProjectAgentWaitClosed.BROKEN, null, null, "the item was deleted"));

    assertEquals(
        "(wait \"ci green\" for qits-2518 broke: the item was deleted)", turns().get(0).text);
  }

  @Test
  void cancelledQueuesNoTurn() {
    notices.waitClosed(event(UUID.randomUUID(), ProjectAgentWaitClosed.CANCELLED, null, null, null));

    assertEquals(List.of(), turns());
  }

  @Test
  void anUnknownSubjectQueuesNoTurn() {
    notices.waitClosed(
        event(
            UUID.randomUUID(),
            "tok-project-agent-qits-nobody",
            ProjectAgentWaitClosed.MATCHED,
            "BuildStatusChanged",
            UUID.randomUUID().toString(),
            null));

    assertEquals(List.of(), turns());
  }

  @Test
  void aRetiredAgentQueuesNoTurn() {
    fixtures.retire(DEVELOPER);
    notices.waitClosed(
        event(
            UUID.randomUUID(),
            ProjectAgentWaitClosed.MATCHED,
            "BuildStatusChanged",
            UUID.randomUUID().toString(),
            null));

    assertEquals(List.of(), turns());
  }

  @Test
  void aRedeliveredEventQueuesNoSecondTurn() {
    ProjectAgentWaitClosed closed =
        event(
            UUID.randomUUID(),
            ProjectAgentWaitClosed.MATCHED,
            "BuildStatusChanged",
            UUID.randomUUID().toString(),
            null);
    notices.waitClosed(closed);
    notices.waitClosed(closed);

    assertEquals(1, turns().size(), "once, redelivered or not");
  }

  @Test
  void theTurnIsServedPendingWithKindWait() {
    notices.waitClosed(
        event(
            UUID.randomUUID(),
            ProjectAgentWaitClosed.MATCHED,
            "BuildStatusChanged",
            "0f3c2a4e-5b6c-47d8-8e9f-a0b1c2d3e4f5",
            null));
    String bearer = "Bearer qits_tok_wait-notices-test";
    ContractCallers.bearer(bearer, DEVELOPER_SUBJECT, Roles.AGENT);

    given()
        .header("Authorization", bearer)
        .queryParam("pending", true)
        .get("/agents/api/projects/" + Fixtures.PROJECT + "/agents/developer/turns")
        .then()
        .statusCode(200)
        .body("[0].kind", equalTo("WAIT"))
        .body(
            "[0].text",
            equalTo(
                "(wait \"ci green\" for qits-2518 matched BuildStatusChanged"
                    + " 0f3c2a4e-5b6c-47d8-8e9f-a0b1c2d3e4f5)"));
  }
}
