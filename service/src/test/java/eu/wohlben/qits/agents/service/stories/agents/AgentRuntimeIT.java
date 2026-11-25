package eu.wohlben.qits.agents.service.stories.agents;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.agents.service.testdb.EmbeddedPg;
import eu.wohlben.qits.servicemock.idp.MockIdp;
import eu.wohlben.qits.userflows.Interactions;
import eu.wohlben.qits.userflows.NetworkCapture;
import eu.wohlben.qits.userflows.NetworkEdge;
import eu.wohlben.qits.userflows.NetworkTaps;
import eu.wohlben.qits.userflows.UserStory;
import eu.wohlben.qits.userflows.UserStoryDescription;
import eu.wohlben.qits.userflows.report.ReportAssertions;
import eu.wohlben.qits.userflows.report.UserflowReport;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * The service as it is <b>packaged</b>, with the OIDC tenant <b>on</b> against {@link MockIdp} —
 * the shipped validation block (discovery, JWKS, audience {@code qits-platform}, {@code groups} as
 * roles) that no {@code @QuarkusTest} here exercises, since every suite turns the gate off.
 *
 * <p>It is also this repository's <b>userflow catalogue</b>: the stories' documentation, with an
 * observed network diagram, is written under {@code service/target/userstories/}, which the
 * java-service release recipe publishes as {@code @userflows/qits-agents}. Both stories are
 * browserless and ordered, because the startup JWKS fetch is attributed to whichever story drains
 * first.
 *
 * <p>The boot reconcile is off here (it would ask the real qits-projects): the agent row is written
 * straight into the packaged service's database, as the reconcile would have left it.
 *
 * <p>Run with {@code ./mvnw verify -DskipITs=false}.
 */
@QuarkusIntegrationTest
@TestProfile(AgentRuntimeIT.PackagedWithMockIdp.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class AgentRuntimeIT {

  static final String CATEGORY = "agents";

  static final String LAUNCH_SLUG = "the-runtime-launches-an-agent-with-its-own-credential";

  static final String REFUSED_SLUG = "another-agent-is-refused-the-runner-doors-of-the-developer";

  static final String SERVICE = "qits-agents";

  static final String AGENT = "/agents/api/projects/qits/agents/developer";

  static final String SUBJECT = "tok-project-agent-qits-developer";

  static final UUID PROJECT_ID = UUID.fromString("0c1d2e3f-4a5b-4c6d-8e7f-a0b1c2d3e4f5");

  private static final List<String> MINTED = new ArrayList<>();

  /**
   * The packaged process's environment: both {@code QITS_RESOURCE_*} triples on this JVM's embedded
   * postgres, the gate on, the idp moved to the mock, the bus and telemetry dark, and the boot
   * reconcile off. Every key is a RUNTIME key: a packaged artifact takes its configuration as
   * {@code -D} arguments.
   */
  public static class PackagedWithMockIdp implements QuarkusTestProfile {

    static final String URL_PROPERTY = "qits.test.userflow-it.db-url";

    private static final String EVENTSTREAM_URL_PROPERTY =
        "qits.test.userflow-it.eventstream-url";

    @Override
    public Map<String, String> getConfigOverrides() {
      MockIdp idp = MockIdp.ensureStarted();
      Map<String, String> overrides = new LinkedHashMap<>();
      overrides.put("QITS_RESOURCE_DB_URL", databaseUrl(URL_PROPERTY, "agents_userflow_it"));
      overrides.put("QITS_RESOURCE_DB_USERNAME", EmbeddedPg.USER);
      overrides.put("QITS_RESOURCE_DB_PASSWORD", EmbeddedPg.PASSWORD);
      overrides.put(
          "QITS_RESOURCE_EVENTSTREAM_URL",
          databaseUrl(EVENTSTREAM_URL_PROPERTY, "agents_userflow_eventstream_it"));
      overrides.put("QITS_RESOURCE_EVENTSTREAM_USERNAME", EmbeddedPg.USER);
      overrides.put("QITS_RESOURCE_EVENTSTREAM_PASSWORD", EmbeddedPg.PASSWORD);
      overrides.put("qits.auth.machine.required", "true");
      overrides.put("quarkus.oidc.auth-server-url", idp.baseUrl());
      overrides.put("quarkus.otel.sdk.disabled", "true");
      overrides.put("qits.eventstream.enabled", "false");
      overrides.put("qits.agents.roster-reconcile-at-boot", "false");
      // No outbound call to qits-certificates from this IT (qits-2636): NORMAL mode, not %test.
      overrides.put("qits.trust.enabled", "false");
      return overrides;
    }

    /** One url per database per JVM, parked in a system property for the other classloaders. */
    static synchronized String databaseUrl(String property, String database) {
      String recorded = System.getProperty(property);
      if (recorded != null) {
        return recorded;
      }
      String url = EmbeddedPg.url(database);
      System.setProperty(property, url);
      return url;
    }
  }

  @BeforeAll
  static void tapBothEndsOfTheNetwork() {
    NetworkTaps.restAssured(SERVICE);
    NetworkCapture.source(
        "mock-idp",
        () ->
            MockIdp.attach().recordedRequests().stream()
                .map(
                    request ->
                        NetworkEdge.http(
                            SERVICE,
                            MockIdp.SERVICE_NAME,
                            request.method() + " " + request.path() + " -> " + request.status()))
                .toList());
  }

  private static String mint(String subject, String role) {
    String token =
        MockIdp.attach().token().subject(subject).audience("qits-platform").groups(role).mint();
    MINTED.add(token);
    return token;
  }

  /** The developer agent as the reconcile leaves it: generation 1, RUNNING, its token stored. */
  private static void declareTheDeveloper() throws Exception {
    String url =
        PackagedWithMockIdp.databaseUrl(PackagedWithMockIdp.URL_PROPERTY, "agents_userflow_it");
    Timestamp now = Timestamp.from(Instant.parse("2026-11-20T08:00:00Z"));
    try (Connection db = DriverManager.getConnection(url, EmbeddedPg.USER, EmbeddedPg.PASSWORD);
        PreparedStatement insert =
            db.prepareStatement(
                "insert into agents.agent (id, project_id, project_slug, name, description, brief,"
                    + " recovery_prompt, declaration_hash, generation, desired_state,"
                    + " reported_state, token_id, token_subject, token_value, created_at,"
                    + " updated_at) values (?, ?, 'qits', 'developer', 'Develops.', 'You develop.',"
                    + " 'Carry on.', ?, 1, 'RUNNING', 'UNKNOWN', ?, ?, 'qits_tok_userflow', ?, ?)"
                    + " on conflict do nothing")) {
      insert.setObject(1, UUID.fromString("1d2e3f4a-5b6c-4d7e-8f90-b1c2d3e4f5a6"));
      insert.setObject(2, PROJECT_ID);
      insert.setString(3, "b".repeat(64));
      insert.setObject(4, UUID.fromString("2e3f4a5b-6c7d-4e8f-9a01-c2d3e4f5a6b7"));
      insert.setString(5, SUBJECT);
      insert.setTimestamp(6, now);
      insert.setTimestamp(7, now);
      insert.executeUpdate();
    }
  }

  @UserStory(value = "The runtime launches an agent with its own credential", category = CATEGORY)
  @UserStoryDescription(
      """
      The roster reconcile made qits' developer agent a row at generation 1 and commissioned its
      credential, a qits-idp token whose subject is tok-project-agent-qits-developer. The agent's
      in-container daemon holds no system credential: it calls the runner doors with that token.
      It reads the launch (the generation to run and the token), reports the agent IDLE, reads
      the pending turns, and asks GET /me which agent it is.
      """)
  @Order(1)
  void theRuntimeLaunchesAnAgent(Interactions story) throws Exception {
    MockIdp idp = MockIdp.attach();
    story.note("qits-agents starts with the OIDC tenant on, beside a reachable qits-idp");
    given().get("/agents/q/health/ready").then().statusCode(200);
    assertTrue(
        idp.recordedRequests().stream().anyMatch(r -> "/idp/jwks".equals(r.path())),
        "the packaged service never fetched /idp/jwks at startup");
    story.note("the signing keys were fetched at startup").as("jwks-fetched");
    declareTheDeveloper();

    NetworkCapture.actor("the developer agent's daemon");
    String bearer = mint(SUBJECT, "qits:agent");
    given()
        .header("Authorization", "Bearer " + bearer)
        .get(AGENT + "/launch")
        .then()
        .statusCode(200)
        .body("generation", equalTo(1))
        .body("tokenSubject", equalTo(SUBJECT));
    story.note("the launch answers generation 1 and the agent's credential").as("launched");

    given()
        .header("Authorization", "Bearer " + bearer)
        .contentType(ContentType.JSON)
        .body(Map.of("generation", 1, "state", "IDLE", "sessionId", "session-1"))
        .put(AGENT + "/report")
        .then()
        .statusCode(204);
    story.note("the agent is reported IDLE at generation 1").as("reported");

    given()
        .header("Authorization", "Bearer " + bearer)
        .get(AGENT + "/turns")
        .then()
        .statusCode(200)
        .body("$", hasSize(0));
    story.note("a first start has no turn waiting").as("no-turns");

    given()
        .header("Authorization", "Bearer " + bearer)
        .get("/agents/api/me")
        .then()
        .statusCode(200)
        .body("name", equalTo("developer"))
        .body("project", equalTo("qits"));
    story.note("GET /me names the developer of qits").as("me");
  }

  @UserStory(value = "Another agent is refused the runner doors of the developer", category = CATEGORY)
  @UserStoryDescription(
      """
      Every agent holds qits:agent, so the role alone cannot keep one agent out of another's
      credential: the runner doors also compare the caller's subject with the agent's. Another
      agent's token reads the agents like any reader, but is refused 403 at the launch, which
      carries the developer's token. A caller with no bearer is refused 401 everywhere.
      """)
  @Order(2)
  void anotherAgentIsRefused(Interactions story) {
    NetworkCapture.actor("another agent");
    String bearer = mint("tok-project-agent-qits-reviewer", "qits:agent");
    given()
        .header("Authorization", "Bearer " + bearer)
        .get("/agents/api/projects/qits/agents")
        .then()
        .statusCode(200);
    story.note("another agent reads the project's agents").as("reads");
    given()
        .header("Authorization", "Bearer " + bearer)
        .get(AGENT + "/launch")
        .then()
        .statusCode(403);
    story.note("it is refused 403 at the developer's launch").as("refused");

    NetworkCapture.actor("an anonymous caller");
    given().get(AGENT + "/launch").then().statusCode(401);
    story.note("a caller with no bearer is refused 401").as("anonymous-refused");
  }

  @AfterAll
  static void bothStoryReportsAreComplete() {
    ReportAssertions.assertComplete(CATEGORY, LAUNCH_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertEdge(
        CATEGORY, LAUNCH_SLUG, "http", SERVICE, MockIdp.SERVICE_NAME, "GET /idp/jwks -> 200");
    ReportAssertions.assertEdge(
        CATEGORY,
        LAUNCH_SLUG,
        "http",
        "the developer agent's daemon",
        SERVICE,
        "GET " + AGENT + "/launch -> 200");
    ReportAssertions.assertStepId(CATEGORY, LAUNCH_SLUG, "me");
    ReportAssertions.assertComplete(CATEGORY, REFUSED_SLUG, UserflowReport.PASSED);
    ReportAssertions.assertEdge(
        CATEGORY, REFUSED_SLUG, "http", "another agent", SERVICE, "GET " + AGENT + "/launch -> 403");
    ReportAssertions.assertStepId(CATEGORY, REFUSED_SLUG, "anonymous-refused");
    for (String bearer : MINTED) {
      ReportAssertions.assertNotLeaked(CATEGORY, LAUNCH_SLUG, bearer);
      ReportAssertions.assertNotLeaked(CATEGORY, REFUSED_SLUG, bearer);
    }
  }

  /**
   * The packaged artifact carries its roster. The JVM suites cannot see this: the native image
   * finds {@code AgentRoster} providers only when the service loader is registered for it, and
   * 2026.1123.194720 shipped without one ("agent rosters on the classpath: []").
   */
  @Test
  @Order(3)
  void thePackagedServiceCarriesTheQitsRoster() {
    given()
        .when()
        .get("/agents/q/health/ready")
        .then()
        .statusCode(200)
        .body("checks.find { it.name == 'agent-rosters' }.status", equalTo("UP"))
        .body("checks.find { it.name == 'agent-rosters' }.data.projects", equalTo("qits"));
  }
}
