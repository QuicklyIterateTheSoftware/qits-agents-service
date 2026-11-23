package eu.wohlben.qits.agents.service.api;

import eu.wohlben.qits.agents.service.control.AgentsException;
import eu.wohlben.qits.agents.service.entity.ProjectAgent;
import io.quarkus.security.identity.SecurityIdentity;

/**
 * The runner doors' check after {@code @RolesAllowed}: an operator ({@link Roles#OPERATORS}) acts
 * on any agent; anyone else got in as {@code qits:agent} and must be <b>this</b> agent — its
 * {@code sub} equal to the row's {@code token_subject}, the same match {@code GET /me} makes. The
 * agent's harness holds no {@code qits:system} credential, so its in-container daemon calls these
 * doors with the agent's own token. Any other agent, and an agent that holds no credential yet, is
 * 403.
 */
final class SelfOrOperator {

  static final String NOT_SELF = "only this agent's own credential, or an operator, may do that";

  private SelfOrOperator() {}

  static void check(SecurityIdentity identity, ProjectAgent agent) {
    for (String role : Roles.OPERATORS) {
      if (identity.hasRole(role)) {
        return;
      }
    }
    String subject = Callers.subject(identity);
    if (agent.tokenSubject == null || !agent.tokenSubject.equals(subject)) {
      throw AgentsException.forbidden(NOT_SELF);
    }
  }
}
