package eu.wohlben.qits.agents.service.api;

import io.quarkus.security.identity.SecurityIdentity;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Who is calling: the token's {@code sub}. For an agent's commissioned token that is the fixed
 * subject {@code tok-project-agent-<projectSlug>-<name>}, which is what {@code token_subject}
 * holds, so the self check and {@code GET /me} compare against it.
 *
 * <p>The {@code sub} rather than quarkus-oidc's principal name, which prefers {@code upn} and
 * {@code preferred_username}. An identity with no JWT behind it (a suite's {@code @TestSecurity})
 * answers its principal name.
 */
final class Callers {

  private Callers() {}

  static String subject(SecurityIdentity identity) {
    if (identity.getPrincipal() instanceof JsonWebToken jwt
        && jwt.getSubject() != null
        && !jwt.getSubject().isBlank()) {
      return jwt.getSubject();
    }
    return identity.getPrincipal().getName();
  }
}
