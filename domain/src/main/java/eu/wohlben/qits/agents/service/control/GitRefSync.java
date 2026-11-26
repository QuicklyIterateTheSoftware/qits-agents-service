package eu.wohlben.qits.agents.service.control;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * <b>Hands agents' Git refs to qits-idp</b> (qits-2705): each {@link AgentGitRefs.Scope} becomes a
 * replace of its token's list. Called after the claims it reflects have committed — by {@code
 * bus/AssignmentListener} once {@link Assignments#assigned} returns, and by {@link RosterReconcile}
 * on every run — and never inside a row's transaction.
 *
 * <p><b>Never throws.</b> A refused or unreachable qits-idp is a WARN and a {@code false}: an event's
 * consumption must not roll back over it (the claims are right; only the token lags), and the next
 * reconcile states every RUNNING agent's list again. Touches no row.
 */
@ApplicationScoped
public class GitRefSync {

  private static final Logger LOG = Logger.getLogger(GitRefSync.class);

  @Inject AgentCredentials credentials;

  /** Replaces each scope's list at qits-idp; true when every one was taken. */
  public boolean replace(List<AgentGitRefs.Scope> scopes) {
    boolean all = true;
    for (AgentGitRefs.Scope scope : scopes) {
      all &= replace(scope);
    }
    return all;
  }

  private boolean replace(AgentGitRefs.Scope scope) {
    try {
      if (credentials.replaceGitRefs(scope.tokenId(), scope.gitRefs())) {
        LOG.debugf(
            "agent %s: token %s may push %d refs",
            scope.agentId(), scope.tokenId(), scope.gitRefs().size());
        return true;
      }
    } catch (RuntimeException failed) {
      LOG.warnf(
          "agent %s: the git refs of token %s were not replaced; the next reconcile retries: %s",
          scope.agentId(), scope.tokenId(), failed.toString());
    }
    return false;
  }
}
