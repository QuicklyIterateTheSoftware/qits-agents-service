package eu.wohlben.qits.agents.service.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import eu.wohlben.qits.agents.service.entity.ProjectAgent;
import eu.wohlben.qits.agents.service.entity.ReportedState;
import eu.wohlben.qits.agents.service.entity.TurnKind;
import eu.wohlben.qits.agents.service.events.ProjectAgentChanged;
import eu.wohlben.qits.agents.service.support.ContractCallers;
import eu.wohlben.qits.agents.service.support.Fixtures;
import eu.wohlben.qits.eventstream.QitsEvent;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** What each door answers, as an operator and as the agent itself. */
@QuarkusTest
class AgentsApiTest {

  static final String API = "/agents/api";

  static final String AGENTS = API + "/projects/qits/agents";

  static final String DEV = AGENTS + "/developer";

  static final UUID DEVELOPER = UUID.fromString("3e9cad4b-5f60-4182-9d2e-3f4a5b6c7d8e");

  static final UUID REVIEWER = UUID.fromString("4fa0be5c-6071-4293-8e3f-4a5b6c7d8e9f");

  static final UUID PENDING = UUID.fromString("5a1bcf6d-7182-43a4-9f40-5b6c7d8e9fa0");

  static final UUID DELIVERED = UUID.fromString("6b2cd07e-8293-44b5-8051-6c7d8e9fa0b1");

  static final UUID REVIEWERS_TURN = UUID.fromString("7c3de18f-93a4-45c6-9162-7d8e9fa0b1c2");

  static final Instant T0 = Instant.parse("2026-11-20T10:00:00Z");

  @Inject Fixtures fixtures;

  List<QitsEvent> events;

  String operator;

  String self;

  @BeforeEach
  void arrange() {
    fixtures.clear();
    events = Fixtures.recordEvents();
    fixtures.agent(DEVELOPER, "developer", 2);
    fixtures.agent(REVIEWER, "reviewer", 1);
    fixtures.claim(DEVELOPER, UUID.randomUUID(), "qits-1", T0, null);
    fixtures.claim(DEVELOPER, UUID.randomUUID(), "qits-2", T0.plusSeconds(60), null);
    fixtures.claim(DEVELOPER, UUID.randomUUID(), "qits-3", T0.plusSeconds(120), T0.plusSeconds(180));
    fixtures.turn(DELIVERED, DEVELOPER, 1, TurnKind.ASSIGNED, "first", T0, T0.plusSeconds(1));
    fixtures.turn(PENDING, DEVELOPER, 2, TurnKind.RECOVERY, "second", T0.plusSeconds(10), null);
    fixtures.turn(REVIEWERS_TURN, REVIEWER, 1, TurnKind.RECOVERY, "theirs", T0, null);
    operator = ContractCallers.caller("the-system", Roles.SYSTEM);
    self = "Bearer qits_tok_api-developer";
    ContractCallers.bearer(self, Fixtures.subject("qits", "developer"), Roles.AGENT);
  }

  private static RequestSpecification as(String authorization) {
    return given().header("Authorization", authorization);
  }

  @Test
  void theListAnswersTheAgentsByNameWithTheirOpenClaimsAndNoToken() {
    String body =
        as(operator)
            .get(AGENTS)
            .then()
            .statusCode(200)
            .body("name", contains("developer", "reviewer"))
            .body("[0].openClaims", equalTo(2))
            .body("[0].generation", equalTo(2))
            .body("[0].desiredState", equalTo("RUNNING"))
            .body("[0].reportedState", equalTo("UNKNOWN"))
            .body("[0].reportedDetail", nullValue())
            .body("[0].tokenSubject", equalTo("tok-project-agent-qits-developer"))
            .extract()
            .asString();
    assertEquals(false, body.contains("qits_tok_"), "no read serves the token value: " + body);
    as(operator).get(API + "/projects/" + Fixtures.PROJECT_ID + "/agents").then().body("$", hasSize(2));
    as(operator).get(API + "/projects/nobody/agents").then().statusCode(200).body("$", hasSize(0));
  }

  @Test
  void oneAgentCarriesItsTextsAndNoToken() {
    as(operator)
        .get(DEV)
        .then()
        .statusCode(200)
        .body("brief", equalTo("You are the developer of the qits project."))
        .body("recoveryPrompt", containsString("restarted"))
        .body("reportedDetail", nullValue())
        .body("model", nullValue())
        .body(not(containsString("qits_tok_")));
    as(operator).get(AGENTS + "/nobody").then().statusCode(404);
  }

  @Test
  void theClaimsAreOpenByDefaultNewestFirst() {
    as(operator).get(DEV + "/claims").then().statusCode(200).body("qualifiedId", contains("qits-2", "qits-1"));
    as(operator)
        .queryParam("state", "all")
        .get(DEV + "/claims")
        .then()
        .body("qualifiedId", contains("qits-3", "qits-2", "qits-1"))
        .body("[0].releasedBy", equalTo("a-person"));
    as(operator).queryParam("state", "closed").get(DEV + "/claims").then().statusCode(400);
  }

  @Test
  void theTurnsArePendingByDefaultOldestFirst() {
    as(self).get(DEV + "/turns").then().statusCode(200).body("id", contains(PENDING.toString()));
    as(self)
        .queryParam("pending", false)
        .get(DEV + "/turns")
        .then()
        .body("id", contains(DELIVERED.toString(), PENDING.toString()))
        .body("[1].kind", equalTo("RECOVERY"))
        .body("[1].text", equalTo("second"));
  }

  @Test
  void deliveredIsIdempotentAndRefusesAnotherAgentsTurn() {
    as(self).post(DEV + "/turns/" + PENDING + "/delivered").then().statusCode(204);
    Instant first = fixtures.turns(DEVELOPER).get(1).deliveredAt;
    assertNotNull(first);
    as(self).post(DEV + "/turns/" + PENDING + "/delivered").then().statusCode(204);
    assertEquals(first, fixtures.turns(DEVELOPER).get(1).deliveredAt, "the first delivery stays");
    as(self).get(DEV + "/turns").then().body("$", hasSize(0));
    as(self).post(DEV + "/turns/" + REVIEWERS_TURN + "/delivered").then().statusCode(404);
    as(self).post(DEV + "/turns/" + UUID.randomUUID() + "/delivered").then().statusCode(404);
  }

  @Test
  void launchCarriesTheTokenAndRefusesARetiredAgent() {
    fixtures.reported(DEVELOPER, ReportedState.IDLE, 2, "session-1");
    as(self)
        .get(DEV + "/launch")
        .then()
        .statusCode(200)
        .body("project", equalTo("qits"))
        .body("projectId", equalTo(Fixtures.PROJECT_ID.toString()))
        .body("name", equalTo("developer"))
        .body("generation", equalTo(2))
        .body("sessionId", equalTo("session-1"))
        .body("token", equalTo("qits_tok_contract-developer"))
        .body("tokenSubject", equalTo("tok-project-agent-qits-developer"));
    fixtures.retire(DEVELOPER);
    as(operator).get(DEV + "/launch").then().statusCode(409);
  }

  @Test
  void aReportIsRecordedAndPublishedWhenTheStateMoves() {
    as(self)
        .contentType(ContentType.JSON)
        .body(Map.of("generation", 2, "state", "busy", "sessionId", "session-7", "detail", "working"))
        .put(DEV + "/report")
        .then()
        .statusCode(204);
    ProjectAgent agent = fixtures.read(DEVELOPER);
    assertEquals(ReportedState.BUSY, agent.reportedState);
    assertEquals(2, agent.reportedGeneration);
    assertEquals("session-7", agent.sessionId);
    assertEquals("working", agent.reportedDetail);
    assertNotNull(agent.reportedAt);
    List<ProjectAgentChanged> changed = Fixtures.of(events, ProjectAgentChanged.class);
    assertEquals(1, changed.size());
    assertEquals("developer", changed.get(0).agentName());
    assertEquals("BUSY", changed.get(0).reportedState());
    assertEquals(Fixtures.PROJECT_ID, changed.get(0).projectId());
    as(operator).get(DEV).then().statusCode(200).body("reportedDetail", equalTo("working"));

    // The same state again, with no detail: recorded, not published, and the stale detail clears.
    as(self)
        .contentType(ContentType.JSON)
        .body(Map.of("generation", 1, "state", "BUSY"))
        .put(DEV + "/report")
        .then()
        .statusCode(204);
    assertEquals(1, Fixtures.of(events, ProjectAgentChanged.class).size());
    assertEquals(1, fixtures.read(DEVELOPER).reportedGeneration);
    assertEquals(
        null,
        fixtures.read(DEVELOPER).reportedDetail,
        "a report with no detail clears the stale one, not keeps it");
    as(operator).get(DEV).then().statusCode(200).body("reportedDetail", nullValue());
  }

  @Test
  void aReportIsRefusedForANewerGenerationOrAnUnreportableState() {
    as(self)
        .contentType(ContentType.JSON)
        .body(Map.of("generation", 3, "state", "IDLE"))
        .put(DEV + "/report")
        .then()
        .statusCode(409);
    as(self)
        .contentType(ContentType.JSON)
        .body(Map.of("generation", 2, "state", "UNKNOWN"))
        .put(DEV + "/report")
        .then()
        .statusCode(400);
    as(self)
        .contentType(ContentType.JSON)
        .body(Map.of("state", "IDLE"))
        .put(DEV + "/report")
        .then()
        .statusCode(400);
    assertEquals(ReportedState.UNKNOWN, fixtures.read(DEVELOPER).reportedState);
    assertEquals(0, Fixtures.of(events, ProjectAgentChanged.class).size());
  }

  @Test
  void meNamesTheCallersAgent() {
    as(self)
        .get(API + "/me")
        .then()
        .statusCode(200)
        .body("project", equalTo("qits"))
        .body("projectId", equalTo(Fixtures.PROJECT_ID.toString()))
        .body("name", equalTo("developer"))
        .body("generation", equalTo(2));
  }

  private static Map<String, Object> message(String id, String work, long input) {
    Map<String, Object> message = new java.util.LinkedHashMap<>();
    message.put("messageId", id);
    message.put("workQualifiedId", work);
    message.put("model", "claude-opus-5-5");
    message.put("inputTokens", input);
    message.put("outputTokens", 2);
    message.put("cacheReadTokens", 3);
    message.put("at", "2026-11-26T10:00:00Z");
    return message;
  }

  @Test
  void usageIsRecordedOncePerSessionAndMessage() {
    as(self)
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "sessionId",
                "session-7",
                "messages",
                List.of(message("msg_1", "qits-2565", 10), message("msg_2", null, 20))))
        .post(DEV + "/usage")
        .then()
        .statusCode(204);
    // The daemon resends after a restart: the repeat is absorbed, the new message recorded.
    as(self)
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "sessionId",
                "session-7",
                "messages",
                List.of(message("msg_1", "qits-2565", 99), message("msg_3", "qits-2565", 30))))
        .post(DEV + "/usage")
        .then()
        .statusCode(204);

    var recorded = fixtures.usageMessages();
    assertEquals(List.of("msg_1", "msg_2", "msg_3"), recorded.stream().map(m -> m.messageId).toList());
    assertEquals(10, recorded.get(0).inputTokens, "the first report of a message wins");
    assertEquals("qits-2565", recorded.get(0).workQualifiedId);
    assertEquals(null, recorded.get(1).workQualifiedId, "the overseer's own message");
    assertEquals(0, recorded.get(1).cacheCreationTokens, "a missing number counts as 0");
    assertEquals(DEVELOPER, recorded.get(2).agentId);
    assertEquals(Instant.parse("2026-11-26T10:00:00Z"), recorded.get(2).at);
  }

  @Test
  void usageIsRefusedWithoutSessionOrBeyond500Messages() {
    as(self)
        .contentType(ContentType.JSON)
        .body(Map.of("messages", List.of(message("msg_1", null, 1))))
        .post(DEV + "/usage")
        .then()
        .statusCode(400);
    List<Map<String, Object>> many = new java.util.ArrayList<>();
    for (int i = 0; i < 501; i++) {
      many.add(message("msg_" + i, null, 1));
    }
    as(self)
        .contentType(ContentType.JSON)
        .body(Map.of("sessionId", "session-7", "messages", many))
        .post(DEV + "/usage")
        .then()
        .statusCode(400);
    Map<String, Object> noModel = message("msg_x", null, 1);
    noModel.remove("model");
    as(self)
        .contentType(ContentType.JSON)
        .body(Map.of("sessionId", "session-7", "messages", List.of(noModel)))
        .post(DEV + "/usage")
        .then()
        .statusCode(400);
    assertEquals(List.of(), fixtures.usageMessages());
  }
}
