package eu.wohlben.qits.agents.service.api;

import static io.restassured.RestAssured.given;

import eu.wohlben.qits.agents.service.entity.TurnKind;
import eu.wohlben.qits.agents.service.support.ContractCallers;
import eu.wohlben.qits.agents.service.support.Fixtures;
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

/**
 * The design page's role matrix, door by door: no door answers an anonymous caller (401); the
 * three reads admit the whole READ set; the four runner doors admit the operators (admin,
 * desk-admin, system) and the agent itself, and refuse any other {@code qits:agent} — another
 * agent's own credential included — and the rest of the READ set 403; {@code GET /me} answers only
 * an agent's credential.
 */
@QuarkusTest
class RoleMatrixTest {

  static final String API = "/agents/api";

  static final String AGENT = API + "/projects/qits/agents/developer";

  static final UUID DEVELOPER = UUID.fromString("0b6f7a1e-2c3d-4e5f-8a9b-0c1d2e3f4a5b");

  static final UUID REVIEWER = UUID.fromString("1c7a8b2f-3d4e-4f60-9b0c-1d2e3f4a5b6c");

  static final UUID TURN = UUID.fromString("2d8b9c3a-4e5f-4071-8c1d-2e3f4a5b6c7d");

  /** The three reads. */
  static final List<String[]> READS =
      List.of(
          new String[] {"GET", API + "/projects/qits/agents"},
          new String[] {"GET", AGENT},
          new String[] {"GET", AGENT + "/claims"});

  @Inject Fixtures fixtures;

  @BeforeEach
  void arrange() {
    fixtures.clear();
    Fixtures.recordEvents();
    fixtures.agent(DEVELOPER, "developer", 2);
    fixtures.agent(REVIEWER, "reviewer", 1);
    fixtures.turn(
        TURN, DEVELOPER, 2, TurnKind.RECOVERY, "carry on", Instant.parse("2026-11-20T10:00:00Z"),
        null);
  }

  /** The four runner doors, called as {@code authorization}; each answers its status. */
  private static void runnerDoors(String authorization, int read, int write) {
    as(authorization).get(AGENT + "/turns").then().statusCode(read);
    as(authorization).get(AGENT + "/launch").then().statusCode(read);
    as(authorization).post(AGENT + "/turns/" + TURN + "/delivered").then().statusCode(write);
    as(authorization)
        .contentType(ContentType.JSON)
        .body(Map.of("generation", 2, "state", "IDLE"))
        .put(AGENT + "/report")
        .then()
        .statusCode(write);
  }

  private static RequestSpecification as(String authorization) {
    return authorization == null ? given() : given().header("Authorization", authorization);
  }

  private static String self(String name) {
    String bearer = "Bearer qits_tok_role-matrix-" + name;
    ContractCallers.bearer(bearer, Fixtures.subject("qits", name), Roles.AGENT);
    return bearer;
  }

  @Test
  void noDoorAnswersAnAnonymousCaller() {
    for (String[] door : READS) {
      given().request(door[0], door[1]).then().statusCode(401);
    }
    runnerDoors(null, 401, 401);
    given().get(API + "/me").then().statusCode(401);
  }

  @Test
  void theReadSetReadsEveryRead() {
    for (String role :
        List.of(
            Roles.ADMIN, Roles.ADMIN_AGENT, Roles.DESK_ADMIN, Roles.DESK, Roles.AGENT, Roles.SYSTEM)) {
      String caller = ContractCallers.caller("a-" + role.replace(':', '-'), role);
      for (String[] door : READS) {
        as(caller).request(door[0], door[1]).then().statusCode(200);
      }
    }
  }

  @Test
  void theOperatorsDriveTheRunnerDoors() {
    for (String role : List.of(Roles.ADMIN, Roles.DESK_ADMIN, Roles.SYSTEM)) {
      runnerDoors(ContractCallers.caller("an-operator", role), 200, 204);
    }
  }

  @Test
  void theAgentItselfDrivesItsRunnerDoors() {
    runnerDoors(self("developer"), 200, 204);
  }

  @Test
  void anotherAgentsCredentialIsRefusedTheRunnerDoors() {
    runnerDoors(self("reviewer"), 403, 403);
  }

  @Test
  void anAgentThatIsNoAgentsCredentialIsRefusedTheRunnerDoors() {
    runnerDoors(ContractCallers.caller("dev-qits-agent", Roles.AGENT), 403, 403);
  }

  @Test
  void theRestOfTheReadSetIsRefusedTheRunnerDoors() {
    for (String role : List.of(Roles.ADMIN_AGENT, Roles.DESK)) {
      runnerDoors(ContractCallers.caller("not-an-operator", role), 403, 403);
    }
  }

  @Test
  void aRoleOutsideEverySetIsRefusedEverywhere() {
    String stranger = ContractCallers.caller("a-stranger", "qits:something-else");
    for (String[] door : READS) {
      as(stranger).request(door[0], door[1]).then().statusCode(403);
    }
    runnerDoors(stranger, 403, 403);
    as(stranger).get(API + "/me").then().statusCode(403);
  }

  @Test
  void meAnswersOnlyAnAgentsCredential() {
    as(self("developer")).get(API + "/me").then().statusCode(200);
    as(ContractCallers.caller("the-admin", Roles.ADMIN)).get(API + "/me").then().statusCode(404);
    as(ContractCallers.caller("dev-qits-agent", Roles.AGENT))
        .get(API + "/me")
        .then()
        .statusCode(404);
    as(ContractCallers.caller("the-desk", Roles.DESK)).get(API + "/me").then().statusCode(403);
  }
}
