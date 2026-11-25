package eu.wohlben.qits.agents.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.agents.service.entity.ProjectAgent;
import eu.wohlben.qits.agents.service.support.FakeSystem;
import eu.wohlben.qits.agents.service.support.FakeTrustAnchors;
import eu.wohlben.qits.agents.service.support.Fixtures;
import eu.wohlben.qits.eventstream.QitsEvent;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * An agent's AGENT lease against a fake qits-system: asked for with the agent's own work key and
 * recorded, re-leased when it exits, adopted when a crash left it unrecorded, ended and forgotten
 * when asked — and never a generation moved, a turn queued or an event published.
 */
@QuarkusTest
class AgentLeasesTest {

  static final UUID DEVELOPER = UUID.fromString("11111111-1111-4111-8111-111111111111");

  @Inject AgentLeases leases;

  @Inject Fixtures fixtures;

  FakeSystem system;

  FakeTrustAnchors anchors;

  List<QitsEvent> events;

  @BeforeEach
  void arrange() {
    fixtures.clear();
    events = Fixtures.recordEvents();
    system = Fixtures.system(new FakeSystem());
    anchors = Fixtures.trustAnchors(new FakeTrustAnchors());
    fixtures.agent(DEVELOPER, "developer", 3);
  }

  @Test
  void applySendsAnAgentLeaseWithTheAgentsWorkKeyAndStoresItsId() {
    Optional<UUID> leaseId = leases.apply(fixtures.read(DEVELOPER));

    assertTrue(leaseId.isPresent());
    assertEquals(1, system.writes("create").size());
    FakeSystem.Call create = system.writes("create").getFirst();
    assertEquals(DEVELOPER, create.agentId());
    var lease = system.leases.get(leaseId.get());
    assertEquals("AGENT", lease.slotType());
    assertEquals(DEVELOPER.toString(), lease.ownerRef());
    assertEquals("agent-" + DEVELOPER, lease.workKey());
    assertEquals("qits_tok_contract-developer", create.spec().env().get("QITS_TOKEN"));
    ProjectAgent row = fixtures.read(DEVELOPER);
    assertEquals(leaseId.get(), row.leaseId);
    assertEquals(3, row.generation, "the runtime never moves a generation");
    assertEquals(List.of(), fixtures.turns(DEVELOPER), "nor queues a turn");
    assertEquals(List.of(), events, "nor publishes");
  }

  @Test
  void applyFreezesTodaysTrustAnchorBundleOntoTheRowAndTheSpec() {
    String pem = "-----BEGIN CERTIFICATE-----\npem\n-----END CERTIFICATE-----";
    anchors.set(pem);

    leases.apply(fixtures.read(DEVELOPER)).orElseThrow();

    assertEquals(pem, fixtures.read(DEVELOPER).trustAnchors);
    FakeSystem.Call create = system.writes("create").getFirst();
    assertEquals(pem, create.spec().env().get(AgentSpecs.TRUST_ANCHORS_ENV));
  }

  @Test
  void applyLeavesTheBundleOutWhenNoneIsHeldYet() {
    leases.apply(fixtures.read(DEVELOPER)).orElseThrow();

    assertNull(fixtures.read(DEVELOPER).trustAnchors);
    FakeSystem.Call create = system.writes("create").getFirst();
    assertTrue(!create.spec().env().containsKey(AgentSpecs.TRUST_ANCHORS_ENV));
  }

  @Test
  void anAgentWithALeaseOrNoTokenIsNotLeased() {
    fixtures.lease(DEVELOPER, UUID.randomUUID());
    assertTrue(leases.apply(fixtures.read(DEVELOPER)).isEmpty());

    fixtures.lease(DEVELOPER, null);
    fixtures.token(DEVELOPER, null);
    assertTrue(leases.apply(fixtures.read(DEVELOPER)).isEmpty());
    assertEquals(List.of(), system.writes());
  }

  @Test
  void anExitedLeaseIsEndedAndLeasedAgain() {
    UUID first = leases.apply(fixtures.read(DEVELOPER)).orElseThrow();
    system.move(first, "EXITED", 1);

    assertTrue(leases.observe(fixtures.read(DEVELOPER)).isEmpty());

    assertEquals(1, system.writes("end").size());
    assertEquals(first, system.writes("end").getFirst().leaseId());
    assertEquals(2, system.writes("create").size());
    UUID second = fixtures.read(DEVELOPER).leaseId;
    assertTrue(second != null && !second.equals(first), "a new lease is recorded: " + second);
    assertEquals(3, fixtures.read(DEVELOPER).generation);
  }

  @Test
  void aFailedLeaseIsEndedAndLeasedAgain() {
    UUID first = leases.apply(fixtures.read(DEVELOPER)).orElseThrow();
    system.move(first, "FAILED", null);

    leases.observe(fixtures.read(DEVELOPER));

    assertEquals(first, system.writes("end").getFirst().leaseId());
    assertEquals(2, system.writes("create").size());
  }

  @Test
  void anEndedOrVanishedLeaseIsForgottenAndLeasedAgain() {
    UUID gone = UUID.randomUUID();
    fixtures.lease(DEVELOPER, gone);

    leases.observe(fixtures.read(DEVELOPER));

    assertEquals(List.of(), system.writes("end"), "nothing to end");
    assertEquals(1, system.writes("create").size());
    assertTrue(!gone.equals(fixtures.read(DEVELOPER).leaseId));
  }

  @Test
  void aLiveLeaseIsAnsweredAndNothingIsSent() {
    UUID leaseId = leases.apply(fixtures.read(DEVELOPER)).orElseThrow();
    system.calls.clear();
    system.move(leaseId, "RUNNING", null);

    assertEquals(leaseId, leases.observe(fixtures.read(DEVELOPER)).orElseThrow().id());
    assertEquals(List.of(), system.writes());
  }

  @Test
  void aLeaseACrashLeftUnrecordedIsAdopted() {
    UUID orphan = system.hold(DEVELOPER, "RUNNING", "x");

    assertEquals(Optional.of(orphan), leases.apply(fixtures.read(DEVELOPER)));
    assertEquals(orphan, fixtures.read(DEVELOPER).leaseId);
  }

  @Test
  void endClearsTheLeaseIdAndNeverTouchesAVolume() {
    UUID leaseId = leases.apply(fixtures.read(DEVELOPER)).orElseThrow();
    system.calls.clear();

    assertTrue(leases.end(fixtures.read(DEVELOPER)));

    assertNull(fixtures.read(DEVELOPER).leaseId);
    assertEquals(List.of(new FakeSystem.Call("end", leaseId, null, null)), system.writes());
    assertTrue(
        java.util.Arrays.stream(SystemLeasesClient.class.getDeclaredMethods())
            .noneMatch(m -> m.getName().toLowerCase(java.util.Locale.ROOT).contains("volume")),
        "the runtime has no way to purge a work volume at all");
  }
}
