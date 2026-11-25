package eu.wohlben.qits.agents.service.control;

import static eu.wohlben.qits.agents.service.support.LedgerRows.activity;
import static eu.wohlben.qits.agents.service.support.LedgerRows.block;
import static eu.wohlben.qits.agents.service.support.LedgerRows.claim;
import static eu.wohlben.qits.agents.service.support.LedgerRows.exit;
import static eu.wohlben.qits.agents.service.support.LedgerRows.item;
import static eu.wohlben.qits.agents.service.support.LedgerRows.message;
import static eu.wohlben.qits.agents.service.support.LedgerRows.request;
import static eu.wohlben.qits.agents.service.support.LedgerRows.requestState;
import static eu.wohlben.qits.agents.service.support.LedgerRows.run;
import static eu.wohlben.qits.agents.service.support.LedgerRows.transition;
import static eu.wohlben.qits.agents.service.support.LedgerRows.usage;
import static eu.wohlben.qits.agents.service.support.LedgerRows.waitClosed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.wohlben.qits.agents.service.control.Evaluation.ItemFigures;
import eu.wohlben.qits.agents.service.control.Evaluation.ModelFigures;
import eu.wohlben.qits.agents.service.control.Evaluation.Percentiles;
import eu.wohlben.qits.agents.service.control.Evaluation.PerVerified;
import eu.wohlben.qits.agents.service.control.Evaluation.Report;
import eu.wohlben.qits.agents.service.control.Evaluation.Tokens;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@link Evaluation#compute} over one seeded ledger, every figure of the design page "Comparison
 * criteria" asserted: attribution (BOTH left out of both models, an item taken before the window or
 * after it, a TASK), throughput and nearest-rank percentiles, tokens (the project agent's overhead
 * included per verified), busy/idle/down (a project agent idle only while it holds a claim), waits,
 * blocks, requests, session exits and the CI runs shared 1/n across a request's branches.
 */
class EvaluationReportTest {

  static final Instant T = Instant.parse("2026-11-25T00:00:00Z");

  static final Instant UNTIL = T.plus(Duration.ofDays(2));

  static final UUID DEVELOPER = UUID.fromString("d0000000-0000-4000-8000-0000000000aa");

  static final String OPUS = "claude-opus-5-5";

  static final String SONNET = "claude-sonnet-5";

  static Report report;

  static Instant h(double hours) {
    return T.plusSeconds((long) (hours * 3600));
  }

  @BeforeAll
  static void compute() {
    Evaluation.Ledger ledger =
        new Evaluation.Ledger(
            List.of(
                // WORKSPACE
                item("qits-1", "TICKET", h(1), null),
                item("qits-2", "EPIC", h(2), null),
                item("qits-3", "TICKET", h(3), null),
                item("qits-8", "TICKET", T.minusSeconds(3600), null), // taken before the window
                item("qits-9", "TASK", h(1), null), // not evaluated
                // PROJECT_AGENT
                item("qits-4", "TICKET", null, h(2)),
                item("qits-5", "TICKET", null, h(30)),
                item("qits-7", "TICKET", null, h(50)), // taken after the window
                // BOTH
                item("qits-6", "TICKET", h(1), h(2))),
            List.of(
                transition("qits-1", "IMPLEMENTING", h(1)),
                transition("qits-1", "VERIFIED", h(5)),
                transition("qits-1", "DONE", h(6)),
                transition("qits-2", "REFINED", h(3)),
                transition("qits-2", "IMPLEMENTING", h(4)),
                transition("qits-2", "DONE", h(12)),
                transition("qits-3", "DROPPED", h(4)),
                transition("qits-4", "IMPLEMENTING", h(2)),
                transition("qits-4", "VERIFIED", h(8)),
                transition("qits-5", "VERIFIED", h(60)), // after the window
                transition("qits-6", "VERIFIED", h(3))),
            List.of(block("qits-1", h(2)), block("qits-4", h(3))),
            List.of(
                request("rr-1", "FINALIZED", h(6), "ticket/qits-1", "ticket/qits-3", "maintenance/bump"),
                request("rr-2", "REJECTED", h(6), "ticket/qits-4-second")),
            List.of(
                requestState("rr-1", "CONFLICTED", h(3)),
                requestState("rr-1", "FINALIZED", h(6)),
                requestState("rr-2", "WITHDRAWN", h(5)),
                requestState("rr-2", "REJECTED", h(6)),
                requestState("rr-2", "APPROVED", h(5.5))),
            List.of(
                run("run-1", "rr-1", h(2), h(2.5), "SUCCESS"),
                run("run-2", "rr-1", h(3), h(3.25), "FAILED"),
                run("run-3", "rr-2", h(4), h(4).plusSeconds(600), "CANCELLED"),
                run("run-4", "rr-2", h(5), null, null)), // still running: not counted
            List.of(
                activity("WORKSPACE", "ws-a", "BUSY", h(1)),
                activity("WORKSPACE", "ws-a", "IDLE", h(2)),
                activity("WORKSPACE", "ws-a", "WAITING", h(3)),
                activity("WORKSPACE", "ws-a", "BUSY", h(3.5)),
                activity("WORKSPACE", "ws-a", "ENDED", h(5)),
                activity("WORKSPACE", "ws-b", "BUSY", h(-1)), // clipped to the window
                activity("WORKSPACE", "ws-b", "ENDED", h(1)),
                activity("PROJECT_AGENT", "developer", "STARTING", h(0)),
                activity("PROJECT_AGENT", "developer", "IDLE", h(1)), // no claim: not idle
                activity("PROJECT_AGENT", "developer", "BUSY", h(2)),
                activity("PROJECT_AGENT", "developer", "IDLE", h(4)),
                activity("PROJECT_AGENT", "developer", "EXITED", h(6)),
                activity("PROJECT_AGENT", "developer", "EXITED", h(6.25)), // the same exit again
                activity("PROJECT_AGENT", "developer", "STARTING", h(6.5)),
                activity("PROJECT_AGENT", "developer", "IDLE", h(7))), // open to the window's end
            List.of(exit("qits-1", h(3))),
            List.of(
                waitClosed("WORKSPACE", "qits-1", "MATCHED", h(2)),
                waitClosed("WORKSPACE", "qits-1", "BROKEN", h(3)),
                waitClosed("WORKSPACE", "qits-3", "TIMED_OUT", h(4)),
                waitClosed("PROJECT_AGENT", "qits-4", "CANCELLED", h(4))),
            List.of(
                usage("qits-1", OPUS, 2, 100, 10, 5, 1000, h(2)),
                usage("qits-2", SONNET, 1, 50, 5, 0, 0, h(3))),
            List.of(
                message(DEVELOPER, "qits-4", OPUS, 10, 20, 30, 40, h(3)),
                message(DEVELOPER, "qits-6", OPUS, 1000, 0, 0, 0, h(2)),
                message(DEVELOPER, null, OPUS, 5, 6, 7, 8, h(1)), // overhead
                message(DEVELOPER, null, OPUS, 900, 900, 900, 900, h(60))), // after the window
            List.of(
                claim(DEVELOPER, "qits-4", h(2), h(8)), claim(DEVELOPER, "qits-5", h(30), null)),
            Map.of(DEVELOPER, "developer"));
    report = Evaluation.compute("qits", ledger, T, UNTIL, UNTIL.plusSeconds(86400));
  }

  static ModelFigures model(String name) {
    return report.models().stream().filter(m -> m.model().equals(name)).findFirst().orElseThrow();
  }

  @Test
  void bothIsListedAsDoubleWorkedAndLeftOutOfBothModels() {
    assertEquals(List.of("qits-6"), report.doubleWorked());
    assertEquals(
        List.of("qits-1", "qits-6", "qits-2", "qits-4", "qits-3", "qits-5"),
        report.items().stream().map(ItemFigures::qualifiedId).toList());
    ItemFigures both = report.items().get(1);
    assertEquals("BOTH", both.model());
    assertEquals(1000, both.inputTokens());
    assertEquals(7200, both.leadTimeSeconds(), "taken at the earlier of its two takes");
  }

  @Test
  void theWorkspaceModelsThroughputLeadTimeAndTokens() {
    ModelFigures ws = model("WORKSPACE");
    assertEquals(3, ws.taken());
    assertEquals(1, ws.refined());
    assertEquals(2, ws.verified(), "qits-1 VERIFIED, qits-2 DONE without VERIFIED");
    assertEquals(1, ws.dropped());
    assertEquals(1.0, ws.verifiedPerDay(), 1e-9);
    assertEquals(new Percentiles(14400L, 36000L), ws.leadTimeSeconds());
    assertEquals(new Percentiles(14400L, 28800L), ws.implementSeconds());
    assertEquals(
        List.of(new Tokens(OPUS, 2, 100, 10, 5, 1000), new Tokens(SONNET, 1, 50, 5, 0, 0)),
        ws.tokens());
    assertEquals(List.of(), ws.overheadTokens());
    assertEquals(new PerVerified(75, 7.5, 2.5, 500), ws.tokensPerVerified());
  }

  @Test
  void theWorkspaceModelsTimeAndFailureModes() {
    ModelFigures ws = model("WORKSPACE");
    assertEquals(3600 + 5400 + 3600, ws.busySeconds());
    assertEquals(3600 + 1800, ws.idleSeconds());
    assertEquals(0, ws.downSeconds());
    assertEquals(12600.0 / 18000, ws.busyShare(), 1e-9);
    assertEquals(Map.of("MATCHED", 1L, "TIMED_OUT", 1L, "BROKEN", 1L, "CANCELLED", 0L), ws.waits());
    assertEquals(1, ws.blocks());
    assertEquals(
        Map.of("FINALIZED", 2L, "REJECTED", 0L, "CONFLICTED", 2L, "FAILED", 0L, "WITHDRAWN", 0L),
        ws.requests());
    assertEquals(1, ws.sessionExits());
  }

  @Test
  void aRunIsSharedOneNthAcrossItsRequestsBranches() {
    ModelFigures ws = model("WORKSPACE");
    // rr-1 has three branches (one is no item's): qits-1 and qits-3 each get 1/3 of two runs.
    assertEquals(4.0 / 3, ws.ciRuns(), 1e-9);
    assertEquals(2.0 / 3, ws.ciRunsRed(), 1e-9);
    assertEquals((1800 + 900) * 2 / 3.0, ws.ciRunSeconds(), 1e-9);
    assertEquals(900.0, ws.ciRunSecondsPerVerified(), 1e-9);
    ItemFigures first = report.items().get(0);
    assertEquals(2.0 / 3, first.ciRuns(), 1e-9);
    assertEquals(900.0, first.ciRunSeconds(), 1e-9);
    assertEquals(Map.of("CONFLICTED", 1L, "FINALIZED", 1L), first.requests());
  }

  @Test
  void theProjectAgentModelCountsItsOverheadAsCost() {
    ModelFigures pa = model("PROJECT_AGENT");
    assertEquals(2, pa.taken());
    assertEquals(0, pa.refined());
    assertEquals(1, pa.verified(), "qits-5 is verified after the window");
    assertEquals(0, pa.dropped());
    assertEquals(0.5, pa.verifiedPerDay(), 1e-9);
    assertEquals(new Percentiles(21600L, 21600L), pa.leadTimeSeconds());
    assertEquals(new Percentiles(21600L, 21600L), pa.implementSeconds());
    assertEquals(List.of(new Tokens(OPUS, 1, 10, 20, 30, 40)), pa.tokens());
    assertEquals(List.of(new Tokens(OPUS, 1, 5, 6, 7, 8)), pa.overheadTokens());
    assertEquals(new PerVerified(15, 26, 37, 48), pa.tokensPerVerified());
  }

  @Test
  void theProjectAgentIsIdleOnlyWhileItHoldsAClaim() {
    ModelFigures pa = model("PROJECT_AGENT");
    assertEquals(7200, pa.busySeconds());
    // [4h,6h) inside qits-4's claim; [7h,8h) until it is released; [30h,48h) on qits-5.
    assertEquals(7200 + 3600 + 64800, pa.idleSeconds());
    assertEquals(3600 + 1800 + 1800, pa.downSeconds());
    assertEquals(7200.0 / (7200 + 75600), pa.busyShare(), 1e-9);
    assertEquals(1, pa.sessionExits(), "two EXITED reports in a row are one exit");
  }

  @Test
  void theProjectAgentModelsFailureModesAndRuns() {
    ModelFigures pa = model("PROJECT_AGENT");
    assertEquals(Map.of("MATCHED", 0L, "TIMED_OUT", 0L, "BROKEN", 0L, "CANCELLED", 1L), pa.waits());
    assertEquals(1, pa.blocks());
    assertEquals(
        Map.of("FINALIZED", 0L, "REJECTED", 1L, "CONFLICTED", 0L, "FAILED", 0L, "WITHDRAWN", 1L),
        pa.requests());
    assertEquals(1.0, pa.ciRuns(), 1e-9, "ticket/<qid>-<name> attributes; a running run is none");
    assertEquals(1.0, pa.ciRunsRed(), 1e-9);
    assertEquals(600.0, pa.ciRunSeconds(), 1e-9);
    assertEquals(600.0, pa.ciRunSecondsPerVerified(), 1e-9);
  }

  @Test
  void percentilesAreNearestRankAndNullWithoutData() {
    assertEquals(new Percentiles(null, null), Evaluation.percentiles(List.of()));
    assertEquals(
        new Percentiles(5L, 9L),
        Evaluation.percentiles(List.of(10L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L)));
    assertEquals(new Percentiles(7L, 7L), Evaluation.percentiles(List.of(7L)));
    List<Long> seventy = java.util.stream.LongStream.rangeClosed(1, 70).boxed().toList();
    assertEquals(new Percentiles(35L, 63L), Evaluation.percentiles(seventy));
  }

  @Test
  void anEmptyModelHasNoPerVerifiedFigures() {
    Report empty =
        Evaluation.compute(
            "qits",
            new Evaluation.Ledger(
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), Map.of()),
            T,
            UNTIL,
            UNTIL);
    ModelFigures ws = empty.models().get(0);
    assertEquals(0, ws.taken());
    assertNull(ws.tokensPerVerified());
    assertNull(ws.ciRunSecondsPerVerified());
    assertNull(ws.leadTimeSeconds().p50());
    assertEquals(0.0, ws.busyShare());
  }
}
