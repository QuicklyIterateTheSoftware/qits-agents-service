package eu.wohlben.qits.agents.service.support;

import eu.wohlben.qits.agents.service.idp.IdpAgentCredentials;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * qits-idp's commissioned tokens, recorded: each commission answers a fresh token id with the fixed
 * subject {@code tok-project-agent-<contextId>}, as qits-idp gives a {@code project-agent} token.
 * {@link #down} refuses every commission and fails every revoke. It extends the deployable's bean
 * because QuarkusMock replaces a bean only with an instance of its own class.
 */
public class StubCredentials extends IdpAgentCredentials {

  /** One commission: the context and the project claim it was asked with. */
  public record Commission(String contextId, UUID projectId, UUID tokenId) {}

  public final List<Commission> commissions = Collections.synchronizedList(new ArrayList<>());

  public final List<UUID> revoked = Collections.synchronizedList(new ArrayList<>());

  public boolean down;

  @Override
  public Issued commission(String contextId, UUID projectId) {
    if (down) {
      throw new Refused("stub qits-idp is down");
    }
    UUID tokenId = UUID.randomUUID();
    commissions.add(new Commission(contextId, projectId, tokenId));
    return new Issued(tokenId, "tok-project-agent-" + contextId, "qits_tok_" + tokenId);
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
