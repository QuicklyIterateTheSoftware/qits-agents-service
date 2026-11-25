package eu.wohlben.qits.agents.service.api;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;

import eu.wohlben.qits.agents.service.support.ContractCallers;
import eu.wohlben.qits.agents.service.support.Fixtures;
import eu.wohlben.qits.agents.service.support.LedgerRows;
import eu.wohlben.qits.agents.service.support.StubDirectory;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code GET /projects/{project}/evaluation} (qits-2521) over a persisted ledger: the project by
 * slug or id, the window's defaults and refusals, and JSON and Markdown rendered from one report.
 * The figures themselves are {@code control.EvaluationReportTest}'s.
 */
@QuarkusTest
class EvaluationApiTest {

  static final String EVALUATION = "/agents/api/projects/qits/evaluation";

  static final UUID DEVELOPER = UUID.fromString("d0000000-0000-4000-8000-000000000031");

  static final Instant T = Instant.parse("2026-11-25T00:00:00Z");

  @Inject Fixtures fixtures;

  String reader;

  @BeforeEach
  void arrange() {
    fixtures.clear();
    Fixtures.recordEvents();
    Fixtures.directory(new StubDirectory().with("other", UUID.fromString("0f000000-0000-4000-8000-000000000001")));
    fixtures.agent(DEVELOPER, "developer", 1);
    fixtures.persist(
        LedgerRows.item("qits-11", "TICKET", T.plusSeconds(3600), null),
        LedgerRows.transition("qits-11", "VERIFIED", T.plusSeconds(7200)),
        LedgerRows.item("qits-12", "TICKET", T.plusSeconds(3600), T.plusSeconds(3700)));
    reader = ContractCallers.caller("a-desk", Roles.DESK);
  }

  private RequestSpecification as() {
    return given().header("Authorization", reader);
  }

  @Test
  void jsonByDefaultBySlugOrId() {
    as().queryParam("until", "2026-11-26T00:00:00Z")
        .get(EVALUATION)
        .then()
        .statusCode(200)
        .contentType(startsWith("application/json"))
        .body("project", equalTo("qits"))
        .body("since", equalTo("2026-11-25T00:00:00Z"))
        .body("models", hasSize(2))
        .body("models[0].model", equalTo("WORKSPACE"))
        .body("models[0].verified", equalTo(1))
        .body("models[0].leadTimeSeconds.p50", equalTo(3600))
        .body("doubleWorked[0]", equalTo("qits-12"));
    as().queryParam("until", "2026-11-26T00:00:00Z")
        .get("/agents/api/projects/" + Fixtures.PROJECT_ID + "/evaluation")
        .then()
        .statusCode(200)
        .body("project", equalTo("qits"));
  }

  @Test
  void markdownCarriesTheSameReport() {
    as().header("Accept", "text/markdown")
        .queryParam("until", "2026-11-26T00:00:00Z")
        .get(EVALUATION)
        .then()
        .statusCode(200)
        .contentType(startsWith("text/markdown"))
        .body(startsWith("# Evaluation of qits\n"))
        .body(containsString("| Figure | WORKSPACE | PROJECT_AGENT |"))
        .body(containsString("| verified | 1 | 0 |"))
        .body(containsString("| leadTimeSeconds.p50 | 3600 | - |"))
        .body(containsString("## Tokens by Claude model"))
        .body(containsString("## Double-worked\n\n- qits-12\n"))
        .body(containsString("<summary>Items (2)</summary>"));
  }

  @Test
  void aProjectWithoutAgentsIsFoundInQitsProjectsAndAnUnknownOneIs404() {
    as().get("/agents/api/projects/other/evaluation")
        .then()
        .statusCode(200)
        .body("project", equalTo("other"))
        .body("items", hasSize(0));
    as().get("/agents/api/projects/nobody/evaluation").then().statusCode(404);
  }

  @Test
  void anUnreadableOrEmptyWindowIs400() {
    as().queryParam("since", "yesterday").get(EVALUATION).then().statusCode(400);
    as().queryParam("since", "2026-11-26T00:00:00Z")
        .queryParam("until", "2026-11-26T00:00:00Z")
        .get(EVALUATION)
        .then()
        .statusCode(400);
  }
}
