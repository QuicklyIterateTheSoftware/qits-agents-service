package eu.wohlben.qits.agents.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.agents.service.control.Agents;
import eu.wohlben.qits.agents.service.entity.ReportedState;
import eu.wohlben.qits.agents.service.support.FakeSystem;
import eu.wohlben.qits.agents.service.support.FakeTrustAnchors;
import eu.wohlben.qits.agents.service.support.Fixtures;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The runtime's reconcile against a fake qits-system: a RUNNING agent without a lease is leased, a
 * RETIRED one with a lease has it ended, a second pass sends nothing — and a committed {@code
 * ProjectAgentChanged} runs a pass at once.
 */
@QuarkusTest
class AgentLeaseSweepTest {

  static final UUID DEVELOPER = UUID.fromString("33333333-3333-4333-8333-333333333333");

  static final UUID REVIEWER = UUID.fromString("44444444-4444-4444-8444-444444444444");

  @Inject AgentLeaseSweep sweep;

  @Inject Agents agents;

  @Inject Fixtures fixtures;

  @Inject AgentSpecs specs;

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

  @AfterEach
  void restore() {
    sweep.enabledOverride(null);
  }

  @Test
  void runningWithoutALeaseIsLeasedRetiredWithOneIsEndedAndASecondPassDoesNothing() {
    fixtures.agent(REVIEWER, "reviewer", 2);
    UUID reviewerLease = system.hold(REVIEWER, "RUNNING", "h");
    fixtures.lease(REVIEWER, reviewerLease);
    fixtures.retire(REVIEWER);

    AgentLeaseSweep.Pass first = sweep.sweep();

    assertEquals(1, first.applied());
    assertEquals(1, first.ended());
    assertNotNull(fixtures.read(DEVELOPER).leaseId);
    assertNull(fixtures.read(REVIEWER).leaseId);
    assertEquals(1, system.writes("create").size());
    assertEquals(DEVELOPER, system.writes("create").getFirst().agentId());
    assertEquals(reviewerLease, system.writes("end").getFirst().leaseId());

    int writes = system.writes().size();
    AgentLeaseSweep.Pass second = sweep.sweep();

    assertTrue(second.quiet(), "second pass: " + second);
    assertEquals(1, second.observed(), "the live lease is read, and that is all");
    assertEquals(writes, system.writes().size());
  }

  @Test
  void aLiveLeaseOnAStaleSpecIsRolledInThePass() {
    UUID leaseId = system.hold(DEVELOPER, "RUNNING", "an older spec");
    fixtures.lease(DEVELOPER, leaseId);

    assertEquals(1, sweep.sweep().rolled());
    assertEquals(leaseId, system.writes("roll").getFirst().leaseId());
    assertEquals(0, sweep.sweep().rolled());
  }

  // --- a lease asked for without the platform CA's anchors (qits-2525) ---------------------------

  static final String PEM = "-----BEGIN CERTIFICATE-----\nanchor\n-----END CERTIFICATE-----";

  /**
   * The live finding: lease 7cb8266d was asked for on 2026-11-24, before qits-2636 added {@code
   * trust_anchors}, so its row holds null and every roll since recomposed without the variable.
   * Once a bundle is held, the pass freezes it onto the row and rolls the lease exactly once.
   */
  @Test
  void aLiveLeaseWithoutFrozenAnchorsTakesTodaysBundleAndRollsOnce() {
    UUID leaseId =
        system.hold(DEVELOPER, "RUNNING", AgentSpecs.hash(specs.compose(fixtures.read(DEVELOPER))));
    fixtures.lease(DEVELOPER, leaseId);
    anchors.set(PEM);

    assertEquals(1, sweep.sweep().rolled());

    assertEquals(PEM, fixtures.read(DEVELOPER).trustAnchors);
    assertEquals(1, system.writes().size());
    FakeSystem.Call put = system.writes("roll").getFirst();
    assertEquals(leaseId, put.leaseId());
    assertEquals(PEM, put.spec().env().get(AgentSpecs.TRUST_ANCHORS_ENV));
    assertEquals(0, sweep.sweep().rolled(), "one roll, never a loop");
    assertEquals(1, system.writes().size());
  }

  /** The CA unreachable (no bundle held): nothing stored, nothing rolled, and the next pass retries. */
  @Test
  void whileNoBundleIsHeldNothingRollsAndALaterPassStillBackfills() {
    sweep.sweep(); // leased while no bundle is held: the row freezes none
    assertNull(fixtures.read(DEVELOPER).trustAnchors);
    int writes = system.writes().size();

    for (int i = 0; i < 3; i++) {
      assertTrue(sweep.sweep().quiet());
    }
    assertEquals(writes, system.writes().size(), "no roll while the CA cannot be read");
    assertNull(fixtures.read(DEVELOPER).trustAnchors);

    anchors.set(PEM);
    assertEquals(1, sweep.sweep().rolled());
    assertEquals(PEM, fixtures.read(DEVELOPER).trustAnchors);
    assertEquals(
        PEM, system.writes("roll").getFirst().spec().env().get(AgentSpecs.TRUST_ANCHORS_ENV));
    assertEquals(0, sweep.sweep().rolled());
  }

  /** A row holding a bundle is never backfilled: a CA rotation alone still rolls nobody. */
  @Test
  void aFrozenBundleIsKeptThroughARotation() {
    anchors.set("pem-v1");
    sweep.sweep();
    assertEquals("pem-v1", fixtures.read(DEVELOPER).trustAnchors);
    int writes = system.writes().size();

    anchors.set("pem-v2");

    assertTrue(sweep.sweep().quiet());
    assertEquals(writes, system.writes().size());
    assertEquals("pem-v1", fixtures.read(DEVELOPER).trustAnchors);
  }

  @Test
  void aCommittedChangeRunsAPassAtOnce() throws InterruptedException {
    sweep.enabledOverride(true);

    // A report that moves the reported state publishes ProjectAgentChanged from control/Agents.
    agents.report(DEVELOPER, 1, ReportedState.STARTING, null, null);

    Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
    while (system.writes("create").isEmpty() && Instant.now().isBefore(deadline)) {
      Thread.sleep(50);
    }
    assertEquals(List.of(DEVELOPER), system.writes("create").stream().map(FakeSystem.Call::agentId).toList());
    deadline = Instant.now().plus(Duration.ofSeconds(10));
    while (fixtures.read(DEVELOPER).leaseId == null && Instant.now().isBefore(deadline)) {
      Thread.sleep(50);
    }
    assertNotNull(fixtures.read(DEVELOPER).leaseId);
  }
}
