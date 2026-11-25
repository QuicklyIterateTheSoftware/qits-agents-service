package eu.wohlben.qits.agents.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.agents.runtime.SystemLeasesApi.Lease;
import eu.wohlben.qits.agents.runtime.SystemLeasesApi.LeaseSpec;
import eu.wohlben.qits.agents.service.support.FakeSystem;
import eu.wohlben.qits.agents.service.support.FakeTrustAnchors;
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

  @Inject AgentLeases leases;

  @Inject Fixtures fixtures;

  FakeSystem system;

  FakeTrustAnchors anchors;

  @BeforeEach
  void arrange() {
    fixtures.clear();
    Fixtures.recordEvents();
    system = Fixtures.system(new FakeSystem());
    anchors = Fixtures.trustAnchors(new FakeTrustAnchors());
    fixtures.agent(DEVELOPER, "developer", 1);
  }

  /** A RUNNING lease the row names, running {@code spec}. */
  private Lease running(LeaseSpec spec) {
    UUID leaseId = system.hold(DEVELOPER, "RUNNING", AgentSpecs.hash(spec), spec.image());
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

  // --- never backwards (qits-2667) -------------------------------------------------------------

  /** The current spec with its image's tag replaced by {@code tag}. */
  private LeaseSpec taggedAt(String tag) {
    LeaseSpec current = specs.compose(fixtures.read(DEVELOPER));
    return new LeaseSpec(
        current.image().replace(":" + AgentImage.VERSION, ":" + tag),
        current.env(),
        current.user(),
        current.limits());
  }

  /**
   * The bug: during a rolling deploy the newer instance rolled the lease onto its newer image, and
   * this — the older — instance must not roll it back (the two ping-ponged, restarting it each time).
   */
  @Test
  void aLeaseOnANewerImageTagIsNotRolledBack() {
    Lease lease = running(taggedAt("9999.1231.235959"));
    assertTrue(lease.image().endsWith(":9999.1231.235959"));

    assertFalse(roll.roll(fixtures.read(DEVELOPER), lease));
    assertEquals(List.of(), system.writes());
  }

  @Test
  void aLeaseOnANewerImageTagIsNotRolledBackEvenForARotatedToken() {
    Lease lease = running(taggedAt("9999.1231.235959"));
    fixtures.token(DEVELOPER, "qits_tok_rotated");

    assertFalse(roll.roll(fixtures.read(DEVELOPER), lease));
    assertEquals(List.of(), system.writes());
  }

  @Test
  void aLeaseOnAnOlderShorterTagStillRolls() {
    Lease lease = running(taggedAt("2026.1"));

    assertTrue(roll.roll(fixtures.read(DEVELOPER), lease));
    assertEquals(1, system.writes("roll").size());
    assertTrue(system.writes("roll").getFirst().spec().image().endsWith(":" + AgentImage.VERSION));
  }

  @Test
  void theSameTagWithARotatedTokenStillRolls() {
    Lease lease = running(specs.compose(fixtures.read(DEVELOPER)));
    assertTrue(lease.image().endsWith(":" + AgentImage.VERSION));
    fixtures.token(DEVELOPER, "qits_tok_rotated");

    assertTrue(roll.roll(fixtures.read(DEVELOPER), lease));
    assertEquals(1, system.writes("roll").size());
  }

  @Test
  void aLeaseWithoutAnImageRollsAsBefore() {
    UUID leaseId = system.hold(DEVELOPER, "RUNNING", "stale");
    fixtures.lease(DEVELOPER, leaseId);
    Lease lease = system.leases.get(leaseId);
    assertEquals(null, lease.spec());

    assertTrue(roll.roll(fixtures.read(DEVELOPER), lease));
    assertEquals(1, system.writes("roll").size());
  }

  @Test
  void aLeaseOnANonNumericTagRollsAsBefore() {
    Lease lease = running(taggedAt("latest"));

    assertTrue(roll.roll(fixtures.read(DEVELOPER), lease));
    assertEquals(1, system.writes("roll").size());
  }

  @Test
  void tagsCompareNumericallySegmentBySegment() {
    assertTrue(AgentSpecRoll.newerAgentTag("2026.1125.201021", "2026.1125.93924"));
    assertFalse(AgentSpecRoll.newerAgentTag("2026.1125.93924", "2026.1125.201021"));
    assertFalse(AgentSpecRoll.newerAgentTag("2026.1125.201021", "2026.1125.201021"));
    assertFalse(AgentSpecRoll.newerAgentTag("2026.1", "2026.1125.1"));
    assertTrue(AgentSpecRoll.newerAgentTag("2026.1125.1", "2026.1"));
    assertFalse(AgentSpecRoll.newerAgentTag("2026.1", "2026.1.0"));
    assertFalse(AgentSpecRoll.newerAgentTag("latest", "2026.1125.1"));
    assertFalse(AgentSpecRoll.newerAgentTag("2026.1125.1", "latest"));
    assertFalse(AgentSpecRoll.newerAgentTag("2026..1", "2026.1"));
    assertFalse(AgentSpecRoll.newerAgentTag(null, "2026.1"));
  }

  @Test
  void onlyAQitsAgentImageHasATag() {
    assertEquals(
        "2026.1", AgentSpecRoll.agentTag("registry.qits.wohlben.eu/qits/agent:2026.1"));
    assertEquals("2026.1", AgentSpecRoll.agentTag("qits/agent:2026.1"));
    assertEquals(null, AgentSpecRoll.agentTag("registry.qits.wohlben.eu/qits/other:2026.1"));
    assertEquals(null, AgentSpecRoll.agentTag("registry.qits.wohlben.eu/notqits/agent:2026.1"));
    assertEquals(null, AgentSpecRoll.agentTag("registry:5000/qits/agent"));
    assertEquals(null, AgentSpecRoll.agentTag(null));
  }

  @Test
  void aLeaseTheRowDoesNotNameIsNotRolled() {
    UUID other = system.hold(DEVELOPER, "RUNNING", "stale");

    assertFalse(roll.roll(fixtures.read(DEVELOPER), system.leases.get(other)));
    assertEquals(List.of(), system.writes());
  }

  // --- the platform CA's anchors (qits-2636) --------------------------------------------------

  @Test
  void aBundleFrozenOnTheRowSendsNothingWhileItStaysThere() {
    fixtures.trustAnchors(DEVELOPER, "pem-v1");
    Lease lease = running(specs.compose(fixtures.read(DEVELOPER)));

    assertFalse(roll.roll(fixtures.read(DEVELOPER), lease));
    assertEquals(List.of(), system.writes());
  }

  @Test
  void aRollForAnotherReasonCarriesTheFrozenBundleUnchanged() {
    fixtures.trustAnchors(DEVELOPER, "pem-v1");
    Lease lease = running(specs.compose(fixtures.read(DEVELOPER)));
    fixtures.token(DEVELOPER, "qits_tok_rotated");

    assertTrue(roll.roll(fixtures.read(DEVELOPER), lease));

    assertEquals(
        "pem-v1", system.writes("roll").getFirst().spec().env().get(AgentSpecs.TRUST_ANCHORS_ENV));
  }

  /**
   * The rule the ticket is about: a CA rotation ALONE, between an agent's lease being created and
   * this reconcile pass, must never roll it. The lease is created while the bundle reads
   * {@code pem-v1} (frozen on the row); the live bundle then rotates to {@code pem-v2}, and nothing
   * else about the agent changes.
   */
  @Test
  void aLiveBundleChangeAfterTheLeaseWasCreatedNeverRolls() {
    anchors.set("pem-v1");
    UUID leaseId = leases.apply(fixtures.read(DEVELOPER)).orElseThrow();
    assertEquals("pem-v1", fixtures.read(DEVELOPER).trustAnchors);
    Lease lease = system.leases.get(leaseId);
    system.calls.clear();

    anchors.set("pem-v2"); // the CA rotated; nothing else about the agent changed

    assertFalse(roll.roll(fixtures.read(DEVELOPER), lease));
    assertEquals(List.of(), system.writes());
    assertEquals(
        "pem-v1",
        fixtures.read(DEVELOPER).trustAnchors,
        "the row still holds the bundle the lease was created with");
  }
}
