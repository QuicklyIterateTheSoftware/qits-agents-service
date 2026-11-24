package eu.wohlben.qits.agents.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.agents.runtime.SystemLeasesApi.Lease;
import eu.wohlben.qits.agents.runtime.SystemLeasesApi.LeaseSpec;
import eu.wohlben.qits.agents.service.support.FakeSystem;
import eu.wohlben.qits.agents.service.support.Fixtures;
import eu.wohlben.qits.agentsdaemon.protocol.AgentImage;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A live lease rolled onto the agent's current spec exactly when the spec moved: a rotated token, a
 * new image tag (a pin bump) — one {@code PUT …/spec} each — and none for an unchanged spec.
 */
@QuarkusTest
class AgentSpecRollTest {

  static final UUID DEVELOPER = UUID.fromString("22222222-2222-4222-8222-222222222222");

  @Inject AgentSpecRoll roll;

  @Inject AgentSpecs specs;

  @Inject Fixtures fixtures;

  FakeSystem system;

  @BeforeEach
  void arrange() {
    fixtures.clear();
    Fixtures.recordEvents();
    system = Fixtures.system(new FakeSystem());
    fixtures.agent(DEVELOPER, "developer", 1);
  }

  /** A RUNNING lease the row names, running {@code spec}. */
  private Lease running(LeaseSpec spec) {
    UUID leaseId = system.hold(DEVELOPER, "RUNNING", AgentSpecs.hash(spec));
    fixtures.lease(DEVELOPER, leaseId);
    return system.leases.get(leaseId);
  }

  @Test
  void anUnchangedSpecSendsNothing() {
    Lease lease = running(specs.compose(fixtures.read(DEVELOPER)));

    assertFalse(roll.roll(fixtures.read(DEVELOPER), lease));
    assertEquals(List.of(), system.writes());
  }

  @Test
  void aRotatedTokenIsOneSpecPut() {
    Lease lease = running(specs.compose(fixtures.read(DEVELOPER)));
    fixtures.token(DEVELOPER, "qits_tok_rotated");

    assertTrue(roll.roll(fixtures.read(DEVELOPER), lease));

    assertEquals(1, system.writes().size());
    FakeSystem.Call put = system.writes("roll").getFirst();
    assertEquals(lease.id(), put.leaseId());
    assertEquals("qits_tok_rotated", put.spec().env().get("QITS_TOKEN"));
    // Rolled: the next look sends nothing.
    assertFalse(roll.roll(fixtures.read(DEVELOPER), system.leases.get(lease.id())));
    assertEquals(1, system.writes().size());
  }

  @Test
  void aNewImageTagIsOneSpecPut() {
    LeaseSpec current = specs.compose(fixtures.read(DEVELOPER));
    LeaseSpec older =
        new LeaseSpec(
            current.image().replace(":" + AgentImage.VERSION, ":2026.1101.1"),
            current.env(),
            current.user(),
            current.limits());
    Lease lease = running(older);

    assertTrue(roll.roll(fixtures.read(DEVELOPER), lease));

    assertEquals(1, system.writes("roll").size());
    assertTrue(system.writes("roll").getFirst().spec().image().endsWith(":" + AgentImage.VERSION));
  }

  @Test
  void aLeaseTheRowDoesNotNameIsNotRolled() {
    UUID other = system.hold(DEVELOPER, "RUNNING", "stale");

    assertFalse(roll.roll(fixtures.read(DEVELOPER), system.leases.get(other)));
    assertEquals(List.of(), system.writes());
  }
}
