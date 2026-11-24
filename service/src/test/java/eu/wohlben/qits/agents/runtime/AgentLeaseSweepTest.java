package eu.wohlben.qits.agents.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.agents.service.control.Agents;
import eu.wohlben.qits.agents.service.entity.ReportedState;
import eu.wohlben.qits.agents.service.support.FakeSystem;
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

  FakeSystem system;

  @BeforeEach
  void arrange() {
    fixtures.clear();
    Fixtures.recordEvents();
    system = Fixtures.system(new FakeSystem());
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
