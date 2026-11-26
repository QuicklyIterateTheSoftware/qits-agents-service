package eu.wohlben.qits.agents.service.contracts.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.pact.consumer.ConsumerPact;
import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import eu.wohlben.qits.pact.consumer.GoldenMasters;
import eu.wohlben.qits.pact.consumer.Trigger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.jose4j.jwk.JsonWebKey;
import org.jose4j.jwk.JsonWebKeySet;
import org.jose4j.jwk.RsaJsonWebKey;
import org.junit.jupiter.api.Test;

/**
 * <b>What this service asks qits-idp-service</b>:
 *
 * <ul>
 *   <li>the two reads quarkus-oidc makes when the machine-auth gate turns the tenant on — the
 *       discovery document, then the JWKS at its {@code jwks_uri} (rows here);
 *   <li>the client-credentials mint of the named client {@code qits}, spent by qits-eventstream and
 *       the reconcile's qits-projects read ({@code IdpTokenMintPactTest} runs it through the real
 *       oidc client);
 *   <li>the agents' credentials ({@code IdpTokensPactTest} runs each through the real REST
 *       client): {@code commissionToken} of kind {@code project-agent}, {@code replaceTokenGitRefs}
 *       (qits-2705) and {@code deleteToken}, HTTP Basic as this service's own client.
 * </ul>
 *
 * <p><b>The commission states an empty list.</b> qits-idp records {@value
 * #A_PROJECT_AGENTS_TOKEN_BEING_COMMISSIONED} (since {@code qits-idp-golden-masters}
 * 2026.1125.71255) with the literal body {@code {"contextKind":"project-agent","contextId":
 * "{contextId}","claims":{"project":"{projectId}"},"gitRefs":[]}}, so the row pins the commission of
 * an agent that holds no claim yet. A commission stating the refs of open claims needs a qits-idp
 * state whose body carries them as a param ({@code "gitRefs":["{gitRef}","{gitRef}-*"]}, as its
 * workspace-agent state does); until one is recorded the non-empty list reaches qits-idp through
 * {@link #REPLACE_GIT_REFS}, whose recorded body is the literal {@code
 * {"gitRefs":["refs/heads/contract/run-1"]}}.
 *
 * <p>The pact is {@code pacts/qits-agents-service_qits-idp-service.json}; {@code
 * -Dgolden.update=true} rewrites it.
 */
class IdpPactTest {

  static final String CONSUMER = "qits-agents-service";

  static final GoldenMasters IDP = GoldenMasters.of("qits-idp-service", "qits-idp");

  static final String STATE = "a published signing key";

  static final Trigger STARTUP = Trigger.event("StartupEvent");

  /** The issuer is bound exactly: a different value fails every token. */
  static final GoldenInteraction DISCOVERY =
      GoldenInteraction.of(STARTUP, STATE, "getOpenIdConfiguration")
          .consumes("issuer", "jwks_uri", "token_endpoint")
          .exact("issuer");

  /** The JWKS: at least one key, each with what quarkus-oidc needs to build and pick it. */
  static final GoldenInteraction JWKS =
      GoldenInteraction.of(STARTUP, STATE, "getJwks")
          .consumes("keys[].kid", "keys[].kty", "keys[].n", "keys[].e", "keys[].alg", "keys[].use");

  /** The qits-idp state that records the client-credentials mint of a service client. */
  static final String A_SERVICE_CLIENT_CALLING_ITS_PEERS = "a service client calling its peers";

  /** The mint, caused by whichever call needs this service's bearer first. */
  static final GoldenInteraction MINT =
      GoldenInteraction.of(
              Trigger.of(
                  "call", "call", "qits-eventstream (subscriber, publisher, query), ProjectsDirectory"),
              A_SERVICE_CLIENT_CALLING_ITS_PEERS,
              "issueToken")
          .header("Authorization", "{authorization}")
          .consumes("access_token", "expires_in");

  /** The qits-idp state that records a project-agent commission (pending, qits-2559). */
  static final String A_PROJECT_AGENTS_TOKEN_BEING_COMMISSIONED =
      "a project agent's token being commissioned";

  /** The reconcile commissions a declared agent's token when its row holds none. */
  static final Trigger RECONCILE = Trigger.event("StartupEvent").from("RosterReconcile");

  static final GoldenInteraction COMMISSION =
      GoldenInteraction.of(RECONCILE, A_PROJECT_AGENTS_TOKEN_BEING_COMMISSIONED, "commissionToken")
          .header("Authorization", "{authorization}")
          .consumes("tokenId", "token", "subject");

  /** The qits-idp state that records a live token its owner deletes. */
  static final String A_COMMISSIONED_TOKEN = "a commissioned token";

  /**
   * The Git refs of an agent's token follow its claims (qits-2705): replaced after an assignment and
   * by every reconcile. The token is one this service owns, as {@value #A_COMMISSIONED_TOKEN}'s
   * caller owns its token.
   */
  static final GoldenInteraction REPLACE_GIT_REFS =
      GoldenInteraction.of(
              Trigger.of("call", "call", "AssignmentListener, RosterReconcile"),
              A_COMMISSIONED_TOKEN,
              "replaceTokenGitRefs")
          .header("Authorization", "{authorization}");

  /** The reconcile revokes a RETIRED agent's token. */
  static final GoldenInteraction DELETE =
      GoldenInteraction.of(RECONCILE, A_COMMISSIONED_TOKEN, "deleteToken")
          .header("Authorization", "{authorization}");

  static final ConsumerPact PACT =
      ConsumerPact.of(
          CONSUMER, IDP, MINT, DISCOVERY, JWKS, COMMISSION, REPLACE_GIT_REFS, DELETE);

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void quarkusOidcReadsTheDiscoveryDocument() {
    PACT.run(DISCOVERY, (url, recorded) -> {
      JsonNode doc = JSON.readTree(get(url + recorded.path()));
      assertTrue(doc.path("issuer").isTextual(), "issuer: " + doc);
      URI.create(doc.path("jwks_uri").asText());
      URI.create(doc.path("token_endpoint").asText());
    });
  }

  @Test
  void quarkusOidcFindsThePublishedSigningKey() {
    PACT.run(JWKS, (url, recorded) -> {
      JsonWebKeySet keys = new JsonWebKeySet(get(url + recorded.path()));
      JsonWebKey key =
          keys.findJsonWebKey(recorded.params().get("kid"), RsaJsonWebKey.KEY_TYPE, "sig", "RS256");
      assertNotNull(key, () -> "an RSA signing key for RS256 named by the state's kid in " + keys.getJsonWebKeys());
      assertNotNull(((RsaJsonWebKey) key).getRsaPublicKey().getModulus());
    });
  }

  @Test
  void theCommittedPactIsWhatTheRowsWrite() {
    PACT.compareOrWritePactFile();
  }

  @Test
  void everyInteractionCarriesBothReferences() {
    PACT.assertEveryInteractionCarriesBothReferences();
    List<GoldenInteraction> unrecorded = PACT.unrecorded();
    assertTrue(
        unrecorded.isEmpty() || unrecorded.equals(List.of(COMMISSION)),
        "only the pending commission may be unrecorded: " + unrecorded);
    assertEquals(PACT.rows().size() - unrecorded.size(), PACT.recorded().size());
  }

  private static String get(String url) throws Exception {
    HttpResponse<String> answer = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create(url)).header("Accept", "application/json").build(),
        HttpResponse.BodyHandlers.ofString());
    assertEquals(200, answer.statusCode(), answer.body());
    return answer.body();
  }
}
