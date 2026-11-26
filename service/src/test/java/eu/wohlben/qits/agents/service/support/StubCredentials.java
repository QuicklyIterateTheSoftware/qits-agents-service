package eu.wohlben.qits.agents.service.support;

import eu.wohlben.qits.agents.service.idp.IdpAgentCredentials;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * qits-idp's commissioned tokens, recorded: each commission answers a fresh token id with the fixed
 * subject {@code tok-project-agent-<contextId>}, as qits-idp gives a {@code project-agent} token,
 * and records the refs it stated; each replace of a token's refs is recorded in {@link #replaced}.
 * {@link #down} refuses every commission and fails every revoke and replace. It extends the
 * deployable's bean because QuarkusMock replaces a bean only with an instance of its own class.
 */
public class StubCredentials extends IdpAgentCredentials {

  /** One commission: the context, the project claim and the refs it was asked with. */
  public record Commission(String contextId, UUID projectId, List<String> gitRefs, UUID tokenId) {}

  /** One replace of a token's refs. */
  public record Replacement(UUID tokenId, List<String> gitRefs) {}

  public final List<Commission> commissions = Collections.synchronizedList(new ArrayList<>());

  /** Every replace qits-idp took, in order. */
  public final List<Replacement> replaced = Collections.synchronizedList(new ArrayList<>());

  public final List<UUID> revoked = Collections.synchronizedList(new ArrayList<>());

  public boolean down;

  /** A replace that throws rather than answering false: a client bug, not a refusal. */
  public boolean replaceThrows;

  @Override
  public Issued commission(String contextId, UUID projectId, List<String> gitRefs) {
    if (down) {
      throw new Refused("stub qits-idp is down");
    }
    UUID tokenId = UUID.randomUUID();
    commissions.add(new Commission(contextId, projectId, List.copyOf(gitRefs), tokenId));
    return new Issued(tokenId, "tok-project-agent-" + contextId, "qits_tok_" + tokenId);
  }

  @Override
  public boolean replaceGitRefs(UUID tokenId, List<String> gitRefs) {
    if (replaceThrows) {
      throw new IllegalStateException("stub qits-idp blew up");
    }
    if (down) {
      return false;
    }
    replaced.add(new Replacement(tokenId, List.copyOf(gitRefs)));
    return true;
  }

  /** The list the last replace of {@code tokenId} stated, or null when none was taken. */
  public List<String> refsOf(UUID tokenId) {
    synchronized (replaced) {
      List<String> last = null;
      for (Replacement replacement : replaced) {
        if (replacement.tokenId().equals(tokenId)) {
          last = replacement.gitRefs();
        }
      }
      return last;
    }
  }

  @Override
  public boolean revoke(UUID tokenId) {
    if (down) {
      return false;
    }
    revoked.add(tokenId);
    return true;
  }
}
