package eu.wohlben.qits.agents.runtime;

import eu.wohlben.qits.agents.runtime.SystemLeasesApi.Lease;
import eu.wohlben.qits.agents.runtime.SystemLeasesApi.LeaseSpec;
import eu.wohlben.qits.agents.runtime.SystemLeasesClient.LeaseRefused;
import eu.wohlben.qits.agents.service.entity.ProjectAgent;
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
}
