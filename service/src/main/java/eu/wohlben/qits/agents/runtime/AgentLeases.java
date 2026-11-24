package eu.wohlben.qits.agents.runtime;

import eu.wohlben.qits.agents.runtime.SystemLeasesApi.Lease;
import eu.wohlben.qits.agents.runtime.SystemLeasesApi.LeaseSpec;
import eu.wohlben.qits.agents.runtime.SystemLeasesApi.LeaseState;
import eu.wohlben.qits.agents.runtime.SystemLeasesClient.LeaseRefused;
import eu.wohlben.qits.agents.service.control.Agents;
import eu.wohlben.qits.agents.service.entity.DesiredState;
import eu.wohlben.qits.agents.service.entity.ProjectAgent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.UUID;
import org.jboss.logging.Logger;

/**
 * An agent as an AGENT lease on an execution target (qits-2519) — qits-projects-service's {@code
 * targethost/DeskLeases}, for one agent rather than a project's desk.
 *
 * <ul>
 *   <li><b>{@link #apply}</b>: a RUNNING agent holding a token and no lease asks for one — {@code
 *       POST /system/api/leases} with {@code slotType AGENT}, {@code ownerRef <agentId>}, {@code
 *       workKey agent-<agentId>}, {@code priority 0} and {@link AgentSpecs#compose}'s spec — and the
 *       answer's id is stored in {@code agent.lease_id}. A {@code LEASE_EXISTS} refusal is a lease a
 *       crash left between the ask and the store: this tenant's live lease for the agent is adopted.
 *   <li><b>{@link #end}</b>: {@code DELETE /system/api/leases/{id}}, then {@code lease_id} cleared.
 *       The work volume is never touched: the AGENT policy keeps it for the agent's next lease.
 *   <li><b>{@link #observe}</b>: the lease read; EXITED or FAILED is ended and asked for again, as
 *       {@code DeskLeases.observe} does; ENDED (or gone) is forgotten and asked for again.
 * </ul>
 *
 * <p>Rows are read and written only through {@link Agents}; nothing here moves a generation, queues
 * a turn or publishes {@code ProjectAgentChanged}. The in-container daemon restarts the harness
 * from {@code launch}. Every HTTP call is made outside any transaction, and a refusal never throws:
 * the next sweep asks again. The spec carries the agent's token and is never logged.
 */
@ApplicationScoped
public class AgentLeases {

  private static final Logger LOG = Logger.getLogger(AgentLeases.class);

  @Inject Agents agents;

  @Inject AgentSpecs specs;

  @Inject SystemLeasesClient system;

  /** Asks for the agent's lease when it is RUNNING with none; answers the lease id it recorded. */
  public Optional<UUID> apply(ProjectAgent agent) {
    if (agent == null || agent.desiredState != DesiredState.RUNNING || agent.leaseId != null) {
      return Optional.empty();
    }
    if (agent.tokenValue == null || agent.tokenValue.isBlank()) {
      LOG.debugf(
          "agent %s/%s has no credential yet; its lease waits for the reconcile to commission one",
          agent.projectSlug, agent.name);
      return Optional.empty();
    }
    LeaseSpec spec;
    try {
      spec = specs.compose(agent);
    } catch (RuntimeException e) {
      LOG.warnf("Could not compose the container of agent %s/%s: %s", agent.projectSlug, agent.name, e.getMessage());
      return Optional.empty();
    }
    Lease lease;
    try {
      lease = system.create(agent.id, spec);
    } catch (LeaseRefused refused) {
      if (SystemLeasesClient.LEASE_EXISTS.equals(refused.code())) {
        return adopt(agent);
      }
      LOG.warnf(
          "qits-system has not leased agent %s/%s a slot; the next sweep asks again: %s",
          agent.projectSlug, agent.name, refused.getMessage());
      return Optional.empty();
    }
    if (lease == null || lease.id() == null) {
      LOG.warnf("qits-system answered the lease request of agent %s/%s with no lease", agent.projectSlug, agent.name);
      return Optional.empty();
    }
    if (!agents.storeLease(agent.id, lease.id())) {
      LOG.infof(
          "agent %s/%s changed while its lease %s was asked for; ending that lease",
          agent.projectSlug, agent.name, lease.id());
      endQuietly(lease.id());
      return Optional.empty();
    }
    LOG.infof(
        "Asked for AGENT lease %s for agent %s/%s (%s, image %s)",
        lease.id(), agent.projectSlug, agent.name, lease.state(), spec.image());
    return Optional.of(lease.id());
  }

  /**
   * Ends the agent's lease and forgets it; true when the row no longer names it. The work volume is
   * not touched.
   */
  public boolean end(ProjectAgent agent) {
    if (agent == null || agent.leaseId == null) {
      return false;
    }
    UUID leaseId = agent.leaseId;
    Optional<Lease> answer;
    try {
      answer = system.end(leaseId);
    } catch (LeaseRefused refused) {
      LOG.warnf(
          "Could not end lease %s of agent %s/%s; the next sweep asks again: %s",
          leaseId, agent.projectSlug, agent.name, refused.getMessage());
      return false;
    }
    agents.clearLease(agent.id, leaseId);
    LOG.infof(
        "Ended AGENT lease %s of agent %s/%s (%s); its work volume is kept",
        leaseId,
        agent.projectSlug,
        agent.name,
        answer.map(Lease::state).orElse("gone"));
    return true;
  }

  /**
   * Reads the lease a RUNNING agent's row names and acts on its state: EXITED or FAILED is ended and
   * asked for again, ENDED or gone is forgotten and asked for again. Answers the lease while it is
   * live and not ending (QUEUED, GRANTED, RUNNING) — the one {@link AgentSpecRoll} compares — and
   * empty otherwise, or when it could not be read.
   */
  public Optional<Lease> observe(ProjectAgent agent) {
    if (agent == null || agent.leaseId == null) {
      return Optional.empty();
    }
    UUID leaseId = agent.leaseId;
    Optional<Lease> read;
    try {
      read = system.get(leaseId);
    } catch (LeaseRefused refused) {
      LOG.debugf("Could not read lease %s of agent %s/%s: %s", leaseId, agent.projectSlug, agent.name, refused.getMessage());
      return Optional.empty();
    }
    LeaseState state = read.map(Lease::leaseState).orElse(LeaseState.ENDED);
    switch (state) {
      case EXITED, FAILED -> {
        Lease lease = read.get();
        LOG.infof(
            "AGENT lease %s of agent %s/%s is %s (exit %s%s); ending it and asking for the next",
            leaseId,
            agent.projectSlug,
            agent.name,
            state,
            lease.exitCode(),
            lease.detail() == null ? "" : ": " + lease.detail());
        if (end(agent)) {
          releaseAgain(agent.id);
        }
        return Optional.empty();
      }
      case ENDED -> {
        LOG.infof(
            "AGENT lease %s of agent %s/%s has ended; asking for the next", leaseId, agent.projectSlug, agent.name);
        agents.clearLease(agent.id, leaseId);
        releaseAgain(agent.id);
        return Optional.empty();
      }
      case ENDING -> {
        return Optional.empty();
      }
      default -> {
        return read;
      }
    }
  }

  // --- internals ---------------------------------------------------------------------------------

  private void releaseAgain(UUID agentId) {
    agents.byId(agentId).ifPresent(this::apply);
  }

  /**
   * qits-system already holds a live lease for the agent's work key that its row does not name — a
   * crash between the ask and the store. This tenant's live lease whose owner ref is the agent is
   * recorded; any other holder is left to a person, named in the WARN.
   */
  private Optional<UUID> adopt(ProjectAgent agent) {
    Optional<Lease> held;
    try {
      held =
          system.list().stream()
              .filter(l -> l.id() != null && agent.id.toString().equals(l.ownerRef()))
              .filter(l -> l.leaseState().live() && l.leaseState() != LeaseState.ENDING)
              .findFirst();
    } catch (LeaseRefused refused) {
      LOG.warnf(
          "qits-system holds a lease for agent %s/%s's work key, and the leases could not be listed"
              + " to adopt it: %s",
          agent.projectSlug, agent.name, refused.getMessage());
      return Optional.empty();
    }
    if (held.isEmpty()) {
      LOG.infof(
          "qits-system still holds a lease for agent %s/%s's work key (ending); the next sweep asks"
              + " again",
          agent.projectSlug, agent.name);
      return Optional.empty();
    }
    UUID leaseId = held.get().id();
    if (!agents.storeLease(agent.id, leaseId)) {
      return Optional.empty();
    }
    LOG.infof("Adopted AGENT lease %s of agent %s/%s, which no row named", leaseId, agent.projectSlug, agent.name);
    return Optional.of(leaseId);
  }

  private void endQuietly(UUID leaseId) {
    try {
      system.end(leaseId);
    } catch (LeaseRefused refused) {
      LOG.warnf("Could not end lease %s: %s", leaseId, refused.getMessage());
    }
  }
}
