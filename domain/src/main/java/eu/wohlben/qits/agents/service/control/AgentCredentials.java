package eu.wohlben.qits.agents.service.control;

import java.util.UUID;

/**
 * An agent's credential at qits-idp: a commissioned token of kind {@code project-agent} for the
 * context {@code <projectSlug>-<name>}, claiming {@code project=<projectId>} and no Git refs. Its
 * subject is fixed, {@code tok-project-agent-<contextId>}; commissioning the same context again
 * rotates the value. An interface so the reconcile can be driven in a test without a qits-idp; the
 * deployable's implementation is {@code idp.IdpAgentCredentials}.
 */
public interface AgentCredentials {

  /** The commission kind every agent token is. */
  String KIND = "project-agent";

  /** A commissioned token. {@code token} is the value, answered once and never logged. */
  record Issued(UUID tokenId, String subject, String token) {
    @Override
    public String toString() {
      return "Issued[tokenId=" + tokenId + ", subject=" + subject + "]";
    }
  }

  /**
   * Commissions (or rotates) the agent's token.
   *
   * @throws Refused when qits-idp is not wired, unreachable, or refuses
   */
  Issued commission(String contextId, UUID projectId);

  /** Revokes a token. True when it is gone (404 included); false leaves it for the next reconcile. */
  boolean revoke(UUID tokenId);

  /** A commission that did not happen. The reconcile logs it and retries on its next run. */
  final class Refused extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public Refused(String message) {
      super(message);
    }

    public Refused(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
