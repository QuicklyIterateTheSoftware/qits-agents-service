package eu.wohlben.qits.agents.service.contracts.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.agents.runtime.AgentSpecs;
import eu.wohlben.qits.agents.runtime.SystemLeasesApi;
import eu.wohlben.qits.agents.runtime.SystemLeasesApi.Lease;
import eu.wohlben.qits.agents.runtime.SystemLeasesApi.LeaseSpec;
import eu.wohlben.qits.agents.runtime.SystemLeasesApi.LeaseState;
import eu.wohlben.qits.agents.runtime.SystemLeasesClient;
import eu.wohlben.qits.agents.service.projects.ServiceBearer;
import eu.wohlben.qits.pact.consumer.ConsumerPact;
import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import eu.wohlben.qits.pact.consumer.GoldenMasters;
import eu.wohlben.qits.pact.consumer.Trigger;
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * <b>What this service asks qits-system-service, as the AGENT tenant, and why</b> (qits-2519): the
 * runtime's lease calls, driven through the real {@link SystemLeasesClient} over the real generated
 * {@link SystemLeasesApi} client against a pact mock server, with the state's {@code authorization}
 * as the bearer (qits-system names the tenant by the bearer's client id, {@code qits-agents}).
 *
 * <ul>
 *   <li>{@code createLease} in {@value #QUEUED}: {@code AgentLeases.apply} asks for an agent's lease
 *       ({@code slotType AGENT}, {@code ownerRef <agentId>}, {@code workKey agent-<agentId>}, {@code
 *       priority 0}) and records the answer's id;
 *   <li>{@code getLease}, {@code replaceLeaseSpec} and {@code endLease} in {@value #RUNNING}: the
 *       sweep reads the lease, rolls it onto a changed spec, and ends it for a RETIRED agent;
 *   <li>{@code listLeases} in {@value #RUNNING}: the adoption of a lease a crash left unrecorded.
 * </ul>
 *
 * <p>The pact is {@code pacts/qits-agents-service_qits-system-service.json}; {@code
 * -Dgolden.update=true} rewrites it. {@code @QuarkusTest} because the reactive REST client is built
 * only inside a running application.
 */
@QuarkusTest
class SystemPactTest {

  static final GoldenMasters SYSTEM = GoldenMasters.of("qits-system-service", "qits-system");

  static final String QUEUED = "a queued agent lease";

  static final String RUNNING = "a running agent lease";

  /** Every call is made by a pass of {@code AgentLeaseSweep} (its interval, or a committed change). */
  static final GoldenInteraction CREATE =
      GoldenInteraction.of(Trigger.schedule("AgentLeases.apply"), QUEUED, "createLease")
          .header("Authorization", "{authorization}")
          .consumes("id", "state");

  static final GoldenInteraction GET =
      GoldenInteraction.of(Trigger.schedule("AgentLeases.observe"), RUNNING, "getLease")
          .header("Authorization", "{authorization}")
          .consumes("id", "state", "specHash");

  static final GoldenInteraction LIST =
      GoldenInteraction.of(Trigger.schedule("AgentLeases.apply"), RUNNING, "listLeases")
          .header("Authorization", "{authorization}")
          .consumes("[].id", "[].ownerRef", "[].state");

  static final GoldenInteraction ROLL =
      GoldenInteraction.of(Trigger.schedule("AgentSpecRoll.roll"), RUNNING, "replaceLeaseSpec")
          .header("Authorization", "{authorization}")
          .consumes("id", "specHash");

  static final GoldenInteraction END =
      GoldenInteraction.of(Trigger.schedule("AgentLeases.end"), RUNNING, "endLease")
          .header("Authorization", "{authorization}")
          .consumes("id", "state");

  static final ConsumerPact PACT =
      ConsumerPact.of(IdpPactTest.CONSUMER, SYSTEM, CREATE, GET, LIST, ROLL, END);

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void applyAsksForAnAgentLeaseAndReadsItsId() {
    PACT.run(
        CREATE,
        (url, recorded) -> {
          UUID agentId = UUID.fromString(recorded.body().path("ownerRef").asText());
          LeaseSpec spec = JSON.treeToValue(recorded.body().path("spec"), LeaseSpec.class);
          Lease lease = client(url, recorded.params().get("authorization")).create(agentId, spec);
          var answer = SYSTEM.json(QUEUED, CREATE.operationId());
          assertEquals(UUID.fromString(answer.path("id").asText()), lease.id());
          assertEquals(LeaseState.QUEUED, lease.leaseState());
          assertEquals("agent-" + agentId, recorded.body().path("workKey").asText());
        });
  }

  @Test
  void theSweepReadsTheLease() {
    PACT.run(
        GET,
        (url, recorded) -> {
          UUID leaseId = UUID.fromString(recorded.params().get("leaseId"));
          Optional<Lease> lease = client(url, recorded.params().get("authorization")).get(leaseId);
          assertTrue(lease.isPresent());
          assertEquals(leaseId, lease.get().id());
          assertEquals(LeaseState.RUNNING, lease.get().leaseState());
        });
  }

  @Test
  void anUnrecordedLeaseIsFoundByItsOwnerRef() {
    PACT.run(
        LIST,
        (url, recorded) -> {
          List<Lease> leases = client(url, recorded.params().get("authorization")).list();
          assertTrue(
              leases.stream()
                  .anyMatch(l -> recorded.params().get("ownerRef").equals(l.ownerRef())
                      && l.leaseState().live()),
              "the state's lease, by its owner ref: " + leases);
        });
  }

  /**
   * The roll, and the hash it is decided by: qits-system answers the hash of the spec it was sent,
   * and {@link AgentSpecs#hash} computes the same one.
   */
  @Test
  void theRollReplacesTheSpecAndQitsSystemHashesItAsAgentSpecsDoes() {
    PACT.run(
        ROLL,
        (url, recorded) -> {
          UUID leaseId = UUID.fromString(recorded.params().get("leaseId"));
          LeaseSpec spec = JSON.treeToValue(recorded.body().path("spec"), LeaseSpec.class);
          Lease lease =
              client(url, recorded.params().get("authorization")).replaceSpec(leaseId, spec);
          assertEquals(leaseId, lease.id());
          assertEquals(
              SYSTEM.json(RUNNING, ROLL.operationId()).path("specHash").asText(),
              AgentSpecs.hash(spec));
        });
  }

  @Test
  void aRetiredAgentsLeaseIsEnded() {
    PACT.run(
        END,
        (url, recorded) -> {
          UUID leaseId = UUID.fromString(recorded.params().get("leaseId"));
          Optional<Lease> lease = client(url, recorded.params().get("authorization")).end(leaseId);
          assertTrue(lease.isPresent());
          assertEquals(LeaseState.ENDING, lease.get().leaseState());
        });
  }

  @Test
  void theCommittedPactIsWhatTheRowsWrite() {
    PACT.compareOrWritePactFile();
  }

  @Test
  void everyInteractionCarriesBothReferences() {
    PACT.assertEveryInteractionCarriesBothReferences();
    assertEquals(PACT.rows().size(), PACT.recorded().size(), "every row is recorded: " + PACT.unrecorded());
  }

  private static SystemLeasesClient client(String url, String authorization) {
    SystemLeasesApi api =
        QuarkusRestClientBuilder.newBuilder().baseUri(URI.create(url)).build(SystemLeasesApi.class);
    return SystemLeasesClient.over(api, bearer(authorization));
  }

  private static ServiceBearer bearer(String authorization) {
    return new ServiceBearer() {
      @Override
      public Optional<String> authorization() {
        return Optional.of(authorization);
      }
    };
  }
}
