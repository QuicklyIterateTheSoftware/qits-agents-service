package eu.wohlben.qits.agents.runtime;

import eu.wohlben.qits.agents.runtime.SystemLeasesApi.Lease;
import eu.wohlben.qits.agents.runtime.SystemLeasesApi.LeaseSpec;
import eu.wohlben.qits.agents.runtime.SystemLeasesClient.LeaseRefused;
import eu.wohlben.qits.agents.service.entity.ProjectAgent;
import eu.wohlben.qits.agentsdaemon.protocol.AgentImage;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Rolls a live AGENT lease onto the agent's current spec (qits-2519) — qits-projects-service's {@code
 * deskhost/FrontDeskSpecRoll}. When {@link AgentSpecs#hash} of {@link AgentSpecs#compose} differs
 * from the {@code specHash} qits-system answers for the lease — the image tag after a pin bump of
 * {@code qits-agents-daemon-protocol}, a rotated token — the spec is replaced ({@code PUT
 * /system/api/leases/{id}/spec}). The target recreates the container on the same work volume and the
 * daemon resumes the same generation from {@code launch}. An unchanged spec sends nothing.
 *
 * <p>The comparison needs no stored copy of the spec: qits-system hashes the spec it was sent
 * exactly as {@link AgentSpecs#hash} does (its {@code LeaseSpecs.hash}), and its answers redact
 * every env value, so the hash is the one thing a reader can compare a token by.
 *
 * <p><b>The platform CA's anchors never move this hash on their own</b> (qits-2636). {@link
 * AgentSpecs#compose} reads {@code agent.trustAnchors} — the bundle {@link AgentLeases#apply} froze
 * on the row when this lease was asked for — never qits-trust-core's live bundle, which this class
 * does not inject at all. So a CA rotation changes nothing this method composes, and {@link #roll}
 * answers {@code false} for it exactly as it does for an unchanged image or token; the new bundle
 * reaches the agent only at its next lease.
 *
 * <p><b>Never backwards</b> (qits-2667). During a rolling deploy two instances of this service run
 * side by side, and each composes its own pinned image, {@code qits/agent:<AgentImage.VERSION>}. The
 * older instance would roll a lease the newer one just rolled back onto its older image; every roll
 * restarts the container, the restart's {@code ProjectAgentChanged} sweeps again on whichever
 * instance hears it, and the two ping-pong. So a roll never moves a lease to an older {@code
 * qits/agent} tag: when the lease already runs a newer one, {@link #roll} sends nothing and answers
 * {@code false} ({@link #newerAgentTag}). The consequence: after a deliberate rollback of this
 * service, live leases keep the newer image until they are leased again.
 */
@ApplicationScoped
public class AgentSpecRoll {

  private static final Logger LOG = Logger.getLogger(AgentSpecRoll.class);

  @Inject AgentSpecs specs;

  @Inject SystemLeasesClient system;

  /** Replaces {@code lease}'s spec when it is not the agent's current one; true when it landed. */
  public boolean roll(ProjectAgent agent, Lease lease) {
    if (agent == null
        || lease == null
        || lease.id() == null
        || !lease.id().equals(agent.leaseId)
        || lease.specHash() == null
        || agent.tokenValue == null
        || agent.tokenValue.isBlank()) {
      return false;
    }
    LeaseSpec spec;
    try {
      spec = specs.compose(agent);
    } catch (RuntimeException e) {
      LOG.warnf("Could not compose the container of agent %s/%s: %s", agent.projectSlug, agent.name, e.getMessage());
      return false;
    }
    String hash = AgentSpecs.hash(spec);
    if (hash.equals(lease.specHash())) {
      return false;
    }
    String leaseTag = agentTag(lease.image());
    String ownTag = agentTag(spec.image());
    if (newerAgentTag(leaseTag, ownTag)) {
      LOG.infof(
          "Not rolling AGENT lease %s of agent %s/%s back from image tag %s to this instance's older %s",
          lease.id(), agent.projectSlug, agent.name, leaseTag, ownTag);
      return false;
    }
    try {
      system.replaceSpec(lease.id(), spec);
    } catch (LeaseRefused refused) {
      LOG.warnf(
          "Could not roll AGENT lease %s of agent %s/%s; the next sweep asks again: %s",
          lease.id(), agent.projectSlug, agent.name, refused.getMessage());
      return false;
    }
    LOG.infof(
        "Rolled AGENT lease %s of agent %s/%s onto spec %s (was %s, image %s)",
        lease.id(), agent.projectSlug, agent.name, hash, lease.specHash(), spec.image());
    return true;
  }

  /**
   * The tag of a {@code …/qits/agent:<tag>} image reference, or null for any other image (another
   * repository, a digest, no tag, none at all).
   */
  static String agentTag(String image) {
    if (image == null) {
      return null;
    }
    int colon = image.lastIndexOf(':');
    if (colon < 0 || image.indexOf('/', colon) >= 0) {
      return null;
    }
    String repository = image.substring(0, colon);
    if (!repository.equals(AgentImage.REPOSITORY)
        && !repository.endsWith("/" + AgentImage.REPOSITORY)) {
      return null;
    }
    String tag = image.substring(colon + 1);
    return tag.isEmpty() ? null : tag;
  }

  /**
   * True only when both tags are CalVer ({@code 2026.1125.201021}) and {@code tag} is the newer,
   * compared numerically segment by segment (a missing segment is 0: {@code 2026.1} is older than
   * {@code 2026.1125.1}, and {@code 2026.1125.93924} older than {@code 2026.1125.201021}). Equal,
   * older, absent or non-numeric tags answer false — a roll goes ahead as before.
   */
  static boolean newerAgentTag(String tag, String than) {
    long[] a = calVer(tag);
    long[] b = calVer(than);
    if (a == null || b == null) {
      return false;
    }
    for (int i = 0; i < Math.max(a.length, b.length); i++) {
      long x = i < a.length ? a[i] : 0;
      long y = i < b.length ? b[i] : 0;
      if (x != y) {
        return x > y;
      }
    }
    return false;
  }

  private static long[] calVer(String tag) {
    if (tag == null || tag.isEmpty()) {
      return null;
    }
    String[] parts = tag.split("\\.", -1);
    long[] segments = new long[parts.length];
    for (int i = 0; i < parts.length; i++) {
      String part = parts[i];
      if (part.isEmpty()
          || part.length() > 18
          || !part.chars().allMatch(c -> c >= '0' && c <= '9')) {
        return null;
      }
      segments[i] = Long.parseLong(part);
    }
    return segments;
  }
}
