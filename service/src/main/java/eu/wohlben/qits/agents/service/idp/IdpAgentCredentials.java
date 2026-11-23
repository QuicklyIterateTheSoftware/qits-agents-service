package eu.wohlben.qits.agents.service.idp;

import eu.wohlben.qits.agents.service.control.AgentCredentials;
import eu.wohlben.qits.agents.service.idp.IdpTokensApi.Commission;
import eu.wohlben.qits.agents.service.idp.IdpTokensApi.Commissioned;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;

/**
 * The roster reconcile's {@link AgentCredentials}: qits-idp's commissioned tokens, as qits-projects'
 * {@code IdpTokens} commissions its desks' — HTTP Basic with this service's own {@code qits} client
 * id and secret (only a static {@code qits:system} client may commission). Kind {@value
 * AgentCredentials#KIND}, context {@code <projectSlug>-<name>}, claims {@code {project:
 * <projectId>}}, {@code gitRefs: []}: the agent may push nothing through this credential. qits-idp
 * gives the token the fixed subject {@code tok-project-agent-<contextId>}, and commissioning the same
 * context again replaces the value (rotation).
 *
 * <p>Not wired — the {@code qits} client off, or no secret (a clone-alone suite) — every commission
 * is {@link AgentCredentials.Refused} and every revoke false, which the reconcile retries.
 */
@ApplicationScoped
public class IdpAgentCredentials implements AgentCredentials {

  private static final Logger LOG = Logger.getLogger(IdpAgentCredentials.class);

  @Inject @RestClient IdpTokensApi tokens;

  @ConfigProperty(name = "quarkus.oidc-client.qits.client-enabled")
  boolean enabled;

  @ConfigProperty(name = "quarkus.oidc-client.qits.client-id")
  String clientId;

  @ConfigProperty(name = "quarkus.oidc-client.qits.credentials.secret")
  Optional<String> clientSecret;

  /**
   * The credentials over a given client and pair, outside CDI: the consumer pact drives the real
   * class through a client built against its mock server.
   */
  public static IdpAgentCredentials over(IdpTokensApi tokens, String clientId, String secret) {
    IdpAgentCredentials credentials = new IdpAgentCredentials();
    credentials.tokens = tokens;
    credentials.enabled = true;
    credentials.clientId = clientId;
    credentials.clientSecret = Optional.of(secret);
    return credentials;
  }

  @Override
  public Issued commission(String contextId, UUID projectId) {
    String basic =
        basic().orElseThrow(() -> new Refused("this service has no idp client wired"));
    Commissioned answer;
    try {
      answer =
          tokens.commissionToken(
              basic,
              new Commission(KIND, contextId, Map.of("project", projectId.toString()), List.of()));
    } catch (WebApplicationException refused) {
      throw new Refused(
          "qits-idp answered "
              + refused.getResponse().getStatus()
              + " commissioning the token of "
              + contextId);
    } catch (ProcessingException unreachable) {
      throw new Refused("qits-idp cannot be reached: " + unreachable.getMessage(), unreachable);
    }
    if (answer == null || blank(answer.tokenId()) || blank(answer.token()) || blank(answer.subject())) {
      throw new Refused("qits-idp answered the commission of " + contextId + " with no usable token");
    }
    try {
      return new Issued(UUID.fromString(answer.tokenId()), answer.subject(), answer.token());
    } catch (IllegalArgumentException notAUuid) {
      throw new Refused("qits-idp answered a token id that is no uuid for " + contextId);
    }
  }

  @Override
  public boolean revoke(UUID tokenId) {
    Optional<String> basic = basic();
    if (basic.isEmpty()) {
      return false;
    }
    try {
      tokens.deleteToken(basic.get(), tokenId.toString());
      return true;
    } catch (WebApplicationException refused) {
      int status = refused.getResponse().getStatus();
      if (status == 404) {
        return true;
      }
      LOG.warnf("qits-idp answered %d revoking token %s; the next run retries", status, tokenId);
    } catch (ProcessingException unreachable) {
      LOG.warnf(
          "qits-idp cannot be reached to revoke token %s; the next run retries: %s",
          tokenId, unreachable.getMessage());
    }
    return false;
  }

  private Optional<String> basic() {
    Optional<String> secret =
        clientSecret == null ? Optional.empty() : clientSecret.filter(s -> !s.isBlank());
    if (!enabled || secret.isEmpty()) {
      return Optional.empty();
    }
    String pair = clientId + ":" + secret.get();
    return Optional.of(
        "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8)));
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }
}
