package eu.wohlben.qits.agents.service.contracts.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.agents.service.control.AgentCredentials;
import eu.wohlben.qits.agents.service.idp.IdpAgentCredentials;
import eu.wohlben.qits.agents.service.idp.IdpTokensApi;
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * <b>The agents' credentials at qits-idp</b>, driven through the real {@link IdpAgentCredentials}
 * over the real generated {@link IdpTokensApi} client against a pact mock server: the commission
 * of a {@code project-agent} token (stating the empty list of an agent that holds nothing yet, see
 * {@link IdpPactTest}), the replace of its Git refs (qits-2705) and the revocation of a RETIRED
 * agent's token. Each client is configured with the
 * Basic pair the state's {@code authorization} encodes, so what goes on the wire is exactly the
 * header the provider's state answers. {@code @QuarkusTest} because the reactive REST client is
 * built only inside a running application.
 */
@QuarkusTest
class IdpTokensPactTest {

  @Test
  void theReconcileCommissionsTheAgentsToken() {
    Assumptions.assumeTrue(
        IdpPactTest.IDP.recorded(
            IdpPactTest.COMMISSION.state(), IdpPactTest.COMMISSION.operationId()),
        "pending: no published qits-idp-golden-masters records '"
            + IdpPactTest.COMMISSION.state()
            + "' yet (qits-2559)");
    IdpPactTest.PACT.run(
        IdpPactTest.COMMISSION,
        (url, recorded) -> {
          Map<String, String> p = recorded.params();
          AgentCredentials.Issued issued =
              credentials(url, p.get("authorization"))
                  .commission(
                      p.get("contextId"), UUID.fromString(p.get("projectId")), List.of());
          var answer =
              IdpPactTest.IDP.json(
                  IdpPactTest.COMMISSION.state(), IdpPactTest.COMMISSION.operationId());
          assertEquals(answer.path("subject").asText(), issued.subject());
          assertEquals(answer.path("token").asText(), issued.token());
        });
  }

  /** qits-idp's recording states this list literally, so the row replays exactly it. */
  static final List<String> RECORDED_GIT_REFS = List.of("refs/heads/contract/run-1");

  @Test
  void theAgentsTokenFollowsItsClaims() {
    IdpPactTest.PACT.run(
        IdpPactTest.REPLACE_GIT_REFS,
        (url, recorded) -> {
          Map<String, String> p = recorded.params();
          assertTrue(
              credentials(url, p.get("authorization"))
                  .replaceGitRefs(UUID.fromString(p.get("tokenId")), RECORDED_GIT_REFS));
        });
  }

  @Test
  void theReconcileRevokesARetiredAgentsToken() {
    IdpPactTest.PACT.run(
        IdpPactTest.DELETE,
        (url, recorded) -> {
          Map<String, String> p = recorded.params();
          assertTrue(
              credentials(url, p.get("authorization"))
                  .revoke(UUID.fromString(p.get("tokenId"))));
        });
  }

  /** The credentials over a client at the mock's {@code /idp}, with the state's Basic pair. */
  private static IdpAgentCredentials credentials(String url, String authorization) {
    String[] pair =
        new String(
                Base64.getDecoder().decode(authorization.substring("Basic ".length())),
                StandardCharsets.UTF_8)
            .split(":", 2);
    IdpTokensApi client =
        QuarkusRestClientBuilder.newBuilder()
            .baseUri(URI.create(url + "/idp"))
            .build(IdpTokensApi.class);
    return IdpAgentCredentials.over(client, pair[0], pair[1]);
  }
}
