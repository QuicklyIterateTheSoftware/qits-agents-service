package eu.wohlben.qits.agents.service.support;

import io.quarkus.arc.Arc;
import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AuthenticationRequest;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.smallrye.mutiny.Uni;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * <b>The callers a suite names by their {@code Authorization} value</b>, for the tests with more
 * than one caller (the golden masters, the pact verification, the self check): the {@code %test}
 * suite runs no idp, so an {@code Authorization} header means nothing until it is granted here.
 * (The shape of qits-workspaces-service's {@code ContractBearers} and {@code
 * SuiteCallerMechanism}, in one class.)
 *
 * <ul>
 *   <li>{@link #caller} — a named caller holding roles, as a plain principal; its scheme is {@code
 *       Suite}, never {@code Bearer}.
 *   <li>{@link #bearer} — a granted bearer value (a provider state's {@code authorization} param),
 *       which becomes the identity the edge forwards for that token: a JWT principal with its
 *       {@code sub} and roles. An agent's own commissioned token is one.
 * </ul>
 *
 * <p>Tried before the shipped mechanisms; a request whose value nobody granted is left alone (and
 * is anonymous, with the OIDC tenant off). A suite with one caller uses {@code @TestSecurity}.
 */
@ApplicationScoped
public class ContractCallers implements HttpAuthenticationMechanism {

  static final String SCHEME = "Suite ";

  static final String PLATFORM_AUDIENCE = "qits-platform";

  record Grant(String subject, Set<String> roles, boolean token) {}

  private final Map<String, Grant> grants = new ConcurrentHashMap<>();

  /** The {@code Authorization} value of {@code subject} holding {@code roles}. */
  public static String caller(String subject, String... roles) {
    String authorization = SCHEME + subject + "." + String.join("+", roles);
    bean().grant(authorization, new Grant(subject, Set.of(roles), false));
    return authorization;
  }

  /** A request with {@code Authorization: <authorization>} is the token of {@code subject}. */
  public static void bearer(String authorization, String subject, String... roles) {
    bean().grant(authorization, new Grant(subject, Set.of(roles), true));
  }

  private static ContractCallers bean() {
    return Arc.container().instance(ContractCallers.class).get();
  }

  void grant(String authorization, Grant grant) {
    grants.put(authorization, grant);
  }

  @Override
  public Uni<SecurityIdentity> authenticate(
      RoutingContext context, IdentityProviderManager identityProviderManager) {
    String authorization = context.request().getHeader("Authorization");
    Grant grant = authorization == null ? null : grants.get(authorization);
    if (grant == null) {
      return Uni.createFrom().optional(Optional.empty());
    }
    QuarkusSecurityIdentity.Builder identity =
        QuarkusSecurityIdentity.builder()
            .setPrincipal(
                grant.token() ? new Token(grant.subject()) : new QuarkusPrincipal(grant.subject()));
    grant.roles().forEach(identity::addRole);
    return Uni.createFrom().item(identity.build());
  }

  @Override
  public Uni<ChallengeData> getChallenge(RoutingContext context) {
    return Uni.createFrom().optional(Optional.empty());
  }

  @Override
  public Set<Class<? extends AuthenticationRequest>> getCredentialTypes() {
    return Set.of();
  }

  @Override
  public int getPriority() {
    return DEFAULT_PRIORITY + 1000;
  }

  /** The JWT the edge would forward: a subject and the platform audience, nothing else. */
  private record Token(String subject) implements JsonWebToken {

    @Override
    public String getName() {
      return subject;
    }

    @Override
    public String getSubject() {
      return subject;
    }

    @Override
    public Set<String> getAudience() {
      return Set.of(PLATFORM_AUDIENCE);
    }

    @Override
    public Set<String> getClaimNames() {
      return Set.of("sub", "aud");
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getClaim(String claimName) {
      return switch (claimName) {
        case "sub" -> (T) subject;
        case "aud" -> (T) Set.of(PLATFORM_AUDIENCE);
        default -> null;
      };
    }
  }
}
