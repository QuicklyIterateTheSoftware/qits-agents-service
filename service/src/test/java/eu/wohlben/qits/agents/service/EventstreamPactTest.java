package eu.wohlben.qits.agents.service;

import eu.wohlben.qits.agents.service.bus.AssignmentListener;
import eu.wohlben.qits.agents.service.bus.ClaimClosureListener;
import eu.wohlben.qits.agents.service.bus.CommentListener;
import eu.wohlben.qits.agents.service.bus.EvalActivityListener;
import eu.wohlben.qits.agents.service.bus.EvalAssignmentsListener;
import eu.wohlben.qits.agents.service.bus.EvalBlocksListener;
import eu.wohlben.qits.agents.service.bus.EvalBuildsListener;
import eu.wohlben.qits.agents.service.bus.EvalExitsListener;
import eu.wohlben.qits.agents.service.bus.EvalPlacementsListener;
import eu.wohlben.qits.agents.service.bus.EvalProjectAgentsListener;
import eu.wohlben.qits.agents.service.bus.EvalRequestsListener;
import eu.wohlben.qits.agents.service.bus.EvalTransitionsListener;
import eu.wohlben.qits.agents.service.bus.EvalUsageListener;
import eu.wohlben.qits.agents.service.bus.EvalWaitsListener;
import eu.wohlben.qits.agents.service.bus.WaitClosedListener;
import eu.wohlben.qits.eventstream.contracts.EventstreamPact;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

/**
 * <b>What qits-eventstream asks qits-events on this service's behalf</b>: the outbox publish of
 * ProjectAgentChanged ({@code control.Agents}) and the catch-up paging of the durable listeners
 * ({@code bus.AssignmentListener}, {@code bus.CommentListener}, {@code bus.WaitClosedListener}, {@code bus.ClaimClosureListener} and
 * the evaluation ledger's eleven {@code bus.Eval*Listener}s, qits-2521), pacted against the library version this repository pins and committed
 * as {@code pacts/qits-agents-service_qits-eventstream-javalib_qits-events-service.json}. The
 * guard fails naming any listener or {@code QitsEventBus} user in either module's classes left out
 * here.
 */
class EventstreamPactTest {

  static final EventstreamPact PACT =
      EventstreamPact.forConsumer("qits-agents-service")
          .listeners(
              new AssignmentListener(),
              new CommentListener(),
              new WaitClosedListener(),
              new ClaimClosureListener(),
              new EvalTransitionsListener(),
              new EvalAssignmentsListener(),
              new EvalBlocksListener(),
              new EvalRequestsListener(),
              new EvalBuildsListener(),
              new EvalPlacementsListener(),
              new EvalActivityListener(),
              new EvalExitsListener(),
              new EvalWaitsListener(),
              new EvalUsageListener(),
              new EvalProjectAgentsListener())
          .publishes(true)
          .guard(Path.of("target/classes"), Path.of("../domain/target/classes"));

  @TestFactory
  Stream<DynamicTest> rows() {
    return PACT.tests();
  }

  @Test
  void pactFile() {
    PACT.compareOrWritePactFile();
  }
}
