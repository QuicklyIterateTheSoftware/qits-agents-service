package eu.wohlben.qits.agents.service.control;

import java.util.List;
import java.util.UUID;

/**
 * An agent's credential at qits-idp: a commissioned token of kind {@code project-agent} for the
 * context {@code <projectSlug>-<name>}, claiming {@code project=<projectId>}, that may push its
 * assigned tickets' and epics' branches ({@link AgentGitRefs}, qits-2705): the list is stated at
 * commission and replaced whenever the agent's claims move, and by every reconcile. Its subject is
 * fixed, {@code tok-project-agent-<contextId>}; commissioning the same context again rotates the
 * value. An interface so the reconcile can be driven in a test without a qits-idp; the deployable's
 * implementation is {@code idp.IdpAgentCredentials}.
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
   * Commissions (or rotates) the agent's token, stating the Git refs it may push ({@link
   * AgentGitRefs#of}; {@code []} pushes nothing).
   *
   * @throws Refused when qits-idp is not wired, unreachable, or refuses
   */
  Issued commission(String contextId, UUID projectId, List<String> gitRefs);

  /**
   * Replaces the Git refs a token may push with {@code gitRefs} (qits-idp's {@code PUT
   * /api/tokens/{tokenId}/git-refs}). True when qits-idp took it; false — logged as a WARN, never
   * thrown — when it is not wired, cannot be reached or refuses, which the next reconcile retries.
   */
  boolean replaceGitRefs(UUID tokenId, List<String> gitRefs);

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
