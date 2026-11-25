package eu.wohlben.qits.agents.service.support;

import eu.wohlben.qits.agents.runtime.AgentSpecs;
import eu.wohlben.qits.agents.runtime.SystemLeasesApi.Lease;
import eu.wohlben.qits.agents.runtime.SystemLeasesApi.LeaseSpec;
import eu.wohlben.qits.agents.runtime.SystemLeasesApi.LeaseSpecView;
import eu.wohlben.qits.agents.runtime.SystemLeasesClient;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * qits-system's lease doors as a map, recording every write (create, roll, end) the runtime sends.
 * A created lease is QUEUED with the hash of the spec it was sent, as qits-system answers; an end
 * answers ENDED. It extends the deployable's bean because QuarkusMock replaces a bean only with an
 * instance of its own class. Nothing here touches a work volume: there is no such call.
 */
public class FakeSystem extends SystemLeasesClient {

  /** One write the runtime sent. */
  public record Call(String operation, UUID leaseId, UUID agentId, LeaseSpec spec) {}

  public final Map<UUID, Lease> leases = Collections.synchronizedMap(new LinkedHashMap<>());

  public final List<Call> calls = Collections.synchronizedList(new ArrayList<>());

  /** A lease qits-system holds for {@code agentId}, in {@code state}, running a spec of this hash. */
  public UUID hold(UUID agentId, String state, String specHash) {
    return hold(agentId, state, specHash, null);
  }

  /** {@link #hold(UUID, String, String)}, answering {@code image} as the lease's spec image. */
  public UUID hold(UUID agentId, String state, String specHash, String image) {
    UUID id = UUID.randomUUID();
    leases.put(id, lease(id, agentId, state, specHash, null, image));
    return id;
  }

  /** Moves a held lease to {@code state}. */
  public void move(UUID leaseId, String state, Integer exitCode) {
    Lease was = leases.get(leaseId);
    leases.put(
        leaseId,
        new Lease(
            was.id(), was.slotType(), was.ownerRef(), was.workKey(), state, was.specHash(), exitCode,
            null, was.spec()));
  }

  public List<Call> writes() {
    synchronized (calls) {
      return List.copyOf(calls);
    }
  }

  public List<Call> writes(String operation) {
    return writes().stream().filter(c -> c.operation().equals(operation)).toList();
  }

  @Override
  public Lease create(UUID agentId, LeaseSpec spec) {
    calls.add(new Call("create", null, agentId, spec));
    // A live lease already holding the agent's work key: qits-system refuses a second one.
    if (leases.values().stream()
            .anyMatch(l -> l.ownerRef().equals(agentId.toString()) && l.leaseState().live())) {
      throw new LeaseRefused(409, LEASE_EXISTS, "a live lease holds agent-" + agentId, null);
    }
    UUID id = UUID.randomUUID();
    Lease lease = lease(id, agentId, "QUEUED", AgentSpecs.hash(spec), null, spec.image());
    leases.put(id, lease);
    return lease;
  }

  @Override
  public Optional<Lease> get(UUID leaseId) {
    return Optional.ofNullable(leases.get(leaseId));
  }

  @Override
  public List<Lease> list() {
    synchronized (leases) {
      return List.copyOf(leases.values());
    }
  }

  @Override
  public Lease replaceSpec(UUID leaseId, LeaseSpec spec) {
    calls.add(new Call("roll", leaseId, null, spec));
    Lease was = leases.get(leaseId);
    if (was == null) {
      throw new LeaseRefused(404, null, "no lease " + leaseId, null);
    }
    Lease rolled =
        new Lease(
            was.id(),
            was.slotType(),
            was.ownerRef(),
            was.workKey(),
            was.state(),
            AgentSpecs.hash(spec),
            null,
            null,
            new LeaseSpecView(spec.image()));
    leases.put(leaseId, rolled);
    return rolled;
  }

  @Override
  public Optional<Lease> end(UUID leaseId) {
    calls.add(new Call("end", leaseId, null, null));
    Lease was = leases.get(leaseId);
    if (was == null) {
      return Optional.empty();
    }
    Lease ended =
        new Lease(
            was.id(), was.slotType(), was.ownerRef(), was.workKey(), "ENDED", was.specHash(),
            was.exitCode(), null, was.spec());
    leases.put(leaseId, ended);
    return Optional.of(ended);
  }

  private static Lease lease(
      UUID id, UUID agentId, String state, String specHash, Integer exitCode, String image) {
    return new Lease(
        id,
        SLOT_TYPE,
        agentId.toString(),
        workKey(agentId),
        state,
        specHash,
        exitCode,
        null,
        image == null ? null : new LeaseSpecView(image));
  }
}
