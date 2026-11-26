package eu.wohlben.qits.agents.service.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.agents.contract.Agent;
import eu.wohlben.qits.agents.contract.AgentDeclaration;
import eu.wohlben.qits.agents.contract.AgentRoster;
import eu.wohlben.qits.agents.contract.AgentRosterDocument;
import eu.wohlben.qits.agents.contract.InvalidAgentRosterException;
import eu.wohlben.qits.agents.service.entity.AgentClaim;
import eu.wohlben.qits.agents.service.entity.AgentTurn;
import eu.wohlben.qits.agents.service.entity.DesiredState;
import eu.wohlben.qits.agents.service.entity.ProjectAgent;
import eu.wohlben.qits.agents.service.entity.ReportedState;
import eu.wohlben.qits.agents.service.entity.TurnKind;
import eu.wohlben.qits.agents.service.events.ProjectAgentChanged;
import eu.wohlben.qits.agents.service.support.Fixtures;
import eu.wohlben.qits.agents.service.support.LedgerRows;
import eu.wohlben.qits.agents.service.support.StubCredentials;
import eu.wohlben.qits.agents.service.support.StubDirectory;
import eu.wohlben.qits.eventstream.QitsEvent;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The roster reconcile's rules, driven directly with rosters of the suite's own against a stubbed
 * qits-projects and qits-idp: first sight, an unchanged roster (no write at all), a changed
 * declaration (a generation and a RECOVERY turn), a removed agent (RETIRED, token revoked), one
 * declared again, an unknown project, an unreachable one, and a broken roster; and the Git refs
 * each token may push, stated at commission and replaced by every run (qits-2705), after the claims
 * of items the evaluation ledger saw closed or reopened are settled (qits-2718).
 */
@QuarkusTest
class RosterReconcileTest {

  record Roster(String project, List<AgentDeclaration> agents) implements AgentRoster {}

  static final AgentDeclaration DEVELOPER =
      Agent.builder()
          .description("Develops qits work items.")
          .brief("You develop.")
          .recoveryPrompt("You were restarted.")
          .build();

  static final AgentDeclaration REVIEWER =
      Agent.builder()
          .name("reviewer")
          .description("Reviews.")
          .brief("You review.")
          .recoveryPrompt("Review again.")
          .model("opus")
          .effort("high")
          .build();

  @Inject RosterReconcile reconcile;

  @Inject Fixtures fixtures;

  @Inject Agents agents;

  List<QitsEvent> events;

  StubDirectory projects;

  StubCredentials idp;

  @BeforeEach
  void arrange() {
    fixtures.clear();
    events = Fixtures.recordEvents();
    projects = Fixtures.directory(new StubDirectory().with("qits", Fixtures.PROJECT_ID));
    idp = Fixtures.credentials(new StubCredentials());
  }

  private static Roster qits(AgentDeclaration... agents) {
    return new Roster("qits", List.of(agents));
  }

  private List<ProjectAgentChanged> changed() {
    return Fixtures.of(events, ProjectAgentChanged.class);
  }

  private ProjectAgent row(String name) {
    return fixtures.agents().stream().filter(a -> a.name.equals(name)).findFirst().orElseThrow();
  }

  @Test
  void firstSightInsertsGenerationOneWithATokenAndNoTurn() {
    RosterReconcile.Outcome outcome = reconcile.reconcile(List.of(qits(DEVELOPER, REVIEWER)));

    assertTrue(outcome.complete());
    assertEquals(1, outcome.applied());
    ProjectAgent developer = row("developer");
    assertEquals(Fixtures.PROJECT_ID, developer.projectId);
    assertEquals("qits", developer.projectSlug);
    assertEquals(1, developer.generation);
    assertEquals(DesiredState.RUNNING, developer.desiredState);
    assertEquals(ReportedState.UNKNOWN, developer.reportedState);
    assertEquals(AgentRosterDocument.hash(DEVELOPER), developer.declarationHash);
    assertEquals("You develop.", developer.brief);
    assertNull(developer.model);
    assertEquals("tok-project-agent-qits-developer", developer.tokenSubject);
    assertNotNull(developer.tokenId);
    assertNotNull(developer.tokenValue);
    assertEquals("opus", row("reviewer").model);
    assertEquals("high", row("reviewer").effort);

    assertEquals(2, idp.commissions.size());
    assertEquals("qits-developer", idp.commissions.get(0).contextId());
    assertEquals(Fixtures.PROJECT_ID, idp.commissions.get(0).projectId());
    assertEquals(List.of(), idp.commissions.get(0).gitRefs(), "a new agent holds nothing yet");
    assertEquals(List.of(), idp.replaced, "a token commissioned this run already states its refs");
    assertEquals(List.of(), fixtures.turns(developer.id), "a first start has no recovery");
    assertEquals(2, changed().size());
    assertEquals("developer", changed().get(0).agentName());
    assertEquals(1, changed().get(0).generation());
    assertEquals("RUNNING", changed().get(0).desiredState());
  }

  @Test
  void anUnchangedRosterWritesNothingAtAll() {
    reconcile.reconcile(List.of(qits(DEVELOPER)));
    ProjectAgent before = row("developer");
    events.clear();

    RosterReconcile.Outcome outcome = reconcile.reconcile(List.of(qits(DEVELOPER)));

    assertTrue(outcome.complete());
    ProjectAgent after = row("developer");
    assertEquals(before.updatedAt, after.updatedAt, "not even updated_at moves");
    assertEquals(1, after.generation);
    assertEquals(before.tokenId, after.tokenId);
    assertEquals(1, idp.commissions.size(), "no second commission");
    assertEquals(List.of(), changed());
  }

  @Test
  void aMissingTokenIsCommissionedByTheNextRunWithoutAGeneration() {
    idp.down = true;
    assertFalse(reconcile.reconcile(List.of(qits(DEVELOPER))).complete());
    assertNull(row("developer").tokenId);

    idp.down = false;
    events.clear();
    assertTrue(reconcile.reconcile(List.of(qits(DEVELOPER))).complete());

    assertNotNull(row("developer").tokenId);
    assertEquals(1, row("developer").generation);
    assertEquals(List.of(), changed(), "a token is no generation");
  }

  static final Instant CLAIMED = Instant.parse("2026-11-26T09:00:00Z");

  @Test
  void aCommissionStatesTheRefsOfTheAgentsOpenClaims() {
    idp.down = true;
    reconcile.reconcile(List.of(qits(DEVELOPER)));
    UUID developer = row("developer").id;
    fixtures.claim(developer, UUID.randomUUID(), "qits-2705", "TICKET", CLAIMED, null);
    fixtures.claim(developer, UUID.randomUUID(), "qits-2518", "EPIC", CLAIMED, CLAIMED);
    fixtures.claim(developer, UUID.randomUUID(), "qits-2706", "TASK", CLAIMED, null);

    idp.down = false;
    assertTrue(reconcile.reconcile(List.of(qits(DEVELOPER))).complete());

    assertEquals(1, idp.commissions.size());
    assertEquals(
        List.of("refs/heads/ticket/qits-2705", "refs/heads/ticket/qits-2705-*"),
        idp.commissions.get(0).gitRefs(),
        "the open ticket's branches; neither the released epic nor the task");
    assertEquals(List.of(), idp.replaced);
  }

  @Test
  void everyRunStatesTheRefsOfEveryRunningAgentsExistingClaims() {
    reconcile.reconcile(List.of(qits(DEVELOPER, REVIEWER)));
    ProjectAgent before = row("developer");
    fixtures.claim(before.id, UUID.randomUUID(), "qits-2518", "EPIC", CLAIMED, null);
    events.clear();

    RosterReconcile.Outcome outcome = reconcile.reconcile(List.of(qits(DEVELOPER, REVIEWER)));

    assertTrue(outcome.complete());
    assertEquals(
        List.of("refs/heads/epic/qits-2518", "refs/heads/epic/qits-2518-*"),
        idp.refsOf(before.tokenId),
        "claims taken before the refs followed them are backfilled");
    assertEquals(List.of(), idp.refsOf(row("reviewer").tokenId));
    assertEquals(before.updatedAt, row("developer").updatedAt, "and no row is written for it");
    assertEquals(List.of(), changed());
    assertEquals(2, idp.commissions.size(), "no second commission");

    idp.down = true;
    assertFalse(
        reconcile.reconcile(List.of(qits(DEVELOPER, REVIEWER))).complete(),
        "a replace qits-idp did not take is retried by the next run");
  }

  @Test
  void aRetiredAgentsRefsAreNotReplaced() {
    reconcile.reconcile(List.of(qits(DEVELOPER, REVIEWER)));
    UUID reviewerToken = row("reviewer").tokenId;
    idp.replaced.clear();

    reconcile.reconcile(List.of(qits(DEVELOPER)));

    assertEquals(List.of(reviewerToken), idp.revoked);
    assertNull(idp.refsOf(reviewerToken), "a retired agent's token is revoked, not rescoped");
    assertNotNull(idp.refsOf(row("developer").tokenId));
  }


  // --- claims of closed items (qits-2718) -----------------------------------------------------

  @Test
  void aRunReleasesTheClaimOfAnItemTheLedgerSawDoneAndNarrowsTheToken() {
    reconcile.reconcile(List.of(qits(DEVELOPER)));
    ProjectAgent developer = row("developer");
    fixtures.claim(developer.id, LedgerRows.work("qits-2392"), "qits-2392", "TICKET", CLAIMED, null);
    fixtures.claim(developer.id, LedgerRows.work("qits-2565"), "qits-2565", "TICKET", CLAIMED, null);
    fixtures.claim(developer.id, LedgerRows.work("qits-2542"), "qits-2542", "EPIC", CLAIMED, null);
    Instant done = CLAIMED.plusSeconds(1200);
    fixtures.persist(
        LedgerRows.transition("qits-2392", "VERIFIED", CLAIMED.plusSeconds(600)),
        LedgerRows.transition("qits-2392", "DONE", done),
        LedgerRows.transition("qits-2565", "VERIFYING", done),
        LedgerRows.transition("qits-2542", "IMPLEMENTING", done));

    assertTrue(reconcile.reconcile(List.of(qits(DEVELOPER))).complete());

    AgentClaim released =
        agents.claims(developer.id, true).stream()
            .filter(c -> c.qualifiedId.equals("qits-2392"))
            .findFirst()
            .orElseThrow();
    assertEquals(done, released.releasedAt);
    assertEquals("DONE", released.releasedOn);
    assertEquals(ClaimClosures.RECONCILE, released.releasedBy);
    assertEquals(
        List.of(
            "refs/heads/epic/qits-2542",
            "refs/heads/epic/qits-2542-*",
            "refs/heads/ticket/qits-2565",
            "refs/heads/ticket/qits-2565-*"),
        idp.refsOf(developer.tokenId),
        "the done ticket leaves the token; the open items stay");

    int replaces = idp.replaced.size();
    List<AgentClaim> before = agents.claims(developer.id, true);
    reconcile.reconcile(List.of(qits(DEVELOPER)));
    assertEquals(
        before.stream().map(c -> c.id + "/" + c.releasedAt + "/" + c.releasedOn).sorted().toList(),
        agents.claims(developer.id, true).stream()
            .map(c -> c.id + "/" + c.releasedAt + "/" + c.releasedOn)
            .sorted()
            .toList(),
        "a second run writes nothing");
    assertEquals(idp.refsOf(developer.tokenId), idp.replaced.get(replaces).gitRefs());
  }

  @Test
  void aRunGivesBackTheClaimOfAnItemTheLedgerSawReopened() {
    reconcile.reconcile(List.of(qits(DEVELOPER)));
    ProjectAgent developer = row("developer");
    Instant dropped = CLAIMED.plusSeconds(600);
    AgentClaim closed =
        fixtures.claim(
            developer.id, LedgerRows.work("qits-2700"), "qits-2700", "TICKET", CLAIMED, dropped);
    fixtures.releasedOn(closed.id, "DROPPED");
    fixtures.persist(
        LedgerRows.transition("qits-2700", "DROPPED", dropped),
        LedgerRows.transition("qits-2700", "REPORTED", dropped.plusSeconds(60)));

    reconcile.reconcile(List.of(qits(DEVELOPER)));

    List<AgentClaim> claims = agents.claims(developer.id, true);
    assertEquals(2, claims.size());
    assertEquals(1, claims.stream().filter(c -> c.releasedAt == null).count());
    assertEquals(
        List.of("refs/heads/ticket/qits-2700", "refs/heads/ticket/qits-2700-*"),
        idp.refsOf(developer.tokenId));

    reconcile.reconcile(List.of(qits(DEVELOPER)));
    assertEquals(2, agents.claims(developer.id, true).size(), "given back once");
  }

  @Test
  void aClaimTakenAfterItsItemClosedIsLeft() {
    reconcile.reconcile(List.of(qits(DEVELOPER)));
    ProjectAgent developer = row("developer");
    fixtures.claim(developer.id, LedgerRows.work("qits-2701"), "qits-2701", "TICKET", CLAIMED, null);
    fixtures.persist(LedgerRows.transition("qits-2701", "DROPPED", CLAIMED.minusSeconds(60)));

    reconcile.reconcile(List.of(qits(DEVELOPER)));

    assertEquals(
        1,
        agents.claims(developer.id, true).stream().filter(c -> c.releasedAt == null).count(),
        "an assignment made while the item was closed is someone's deliberate act");
  }

  @Test
  void aChangedDeclarationIsANewGenerationWithItsRecoveryTurn() {
    reconcile.reconcile(List.of(qits(DEVELOPER)));
    UUID token = row("developer").tokenId;
    events.clear();
    AgentDeclaration rewritten =
        Agent.builder()
            .description("Develops qits work items.")
            .brief("You develop, and you name the assignee rule.")
            .recoveryPrompt("You were restarted; read your claims.")
            .build();

    reconcile.reconcile(List.of(qits(rewritten)));

    ProjectAgent developer = row("developer");
    assertEquals(2, developer.generation);
    assertEquals("You develop, and you name the assignee rule.", developer.brief);
    assertEquals(AgentRosterDocument.hash(rewritten), developer.declarationHash);
    assertEquals(token, developer.tokenId, "a new generation keeps the token");
    List<AgentTurn> turns = fixtures.turns(developer.id);
    assertEquals(1, turns.size());
    assertEquals(TurnKind.RECOVERY, turns.get(0).kind);
    assertEquals(2, turns.get(0).generation);
    assertEquals("You were restarted; read your claims.", turns.get(0).text);
    assertNull(turns.get(0).sourceEventId);
    assertEquals(1, changed().size());
    assertEquals(2, changed().get(0).generation());
  }

  @Test
  void anAgentNoLongerDeclaredIsRetiredAndItsTokenRevoked() {
    reconcile.reconcile(List.of(qits(DEVELOPER, REVIEWER)));
    UUID token = row("reviewer").tokenId;
    events.clear();

    reconcile.reconcile(List.of(qits(DEVELOPER)));

    ProjectAgent reviewer = row("reviewer");
    assertEquals(DesiredState.RETIRED, reviewer.desiredState);
    assertEquals(1, reviewer.generation);
    assertNull(reviewer.tokenId);
    assertNull(reviewer.tokenValue);
    assertEquals(List.of(token), idp.revoked);
    assertEquals(1, changed().size());
    assertEquals("RETIRED", changed().get(0).desiredState());
    assertEquals(DesiredState.RUNNING, row("developer").desiredState);
  }

  @Test
  void aFailedRevocationIsRetriedByTheNextRun() {
    reconcile.reconcile(List.of(qits(DEVELOPER, REVIEWER)));
    UUID token = row("reviewer").tokenId;
    idp.down = true;
    assertFalse(reconcile.reconcile(List.of(qits(DEVELOPER))).complete());
    assertEquals(token, row("reviewer").tokenId, "kept until qits-idp confirms");

    idp.down = false;
    assertTrue(reconcile.reconcile(List.of(qits(DEVELOPER))).complete());
    assertNull(row("reviewer").tokenId);
    assertEquals(List.of(token), idp.revoked);
  }

  @Test
  void aRetiredAgentDeclaredAgainRunsAgainWithANewGenerationAndToken() {
    reconcile.reconcile(List.of(qits(DEVELOPER, REVIEWER)));
    reconcile.reconcile(List.of(qits(DEVELOPER)));
    events.clear();

    reconcile.reconcile(List.of(qits(DEVELOPER, REVIEWER)));

    ProjectAgent reviewer = row("reviewer");
    assertEquals(DesiredState.RUNNING, reviewer.desiredState);
    assertEquals(2, reviewer.generation);
    assertNotNull(reviewer.tokenId);
    assertEquals(3, idp.commissions.size(), "developer, reviewer, reviewer again");
    assertEquals(reviewer.tokenId, idp.commissions.get(2).tokenId());
    List<AgentTurn> turns = fixtures.turns(reviewer.id);
    assertEquals(1, turns.size());
    assertEquals("Review again.", turns.get(0).text);
    assertEquals(1, changed().size());
    assertEquals("RUNNING", changed().get(0).desiredState());
  }

  @Test
  void aRosterOfAnUnknownProjectIsSkipped() {
    RosterReconcile.Outcome outcome =
        reconcile.reconcile(List.of(new Roster("elsewhere", List.of(DEVELOPER)), qits(REVIEWER)));

    assertEquals(1, outcome.applied());
    assertEquals(1, outcome.skipped());
    assertEquals(List.of("reviewer"), fixtures.agents().stream().map(a -> a.name).toList());
  }

  @Test
  void anUnreachableQitsProjectsChangesNothing() {
    projects.down = true;

    RosterReconcile.Outcome outcome = reconcile.reconcile(List.of(qits(DEVELOPER)));

    assertFalse(outcome.complete());
    assertEquals(List.of(), fixtures.agents());
    assertEquals(List.of(), idp.commissions);
  }

  @Test
  void aBrokenRosterIsRefusedBeforeAnythingIsWritten() {
    AgentDeclaration nameless =
        Agent.builder().name("Not A Name").description("x").brief("x").recoveryPrompt("x").build();

    assertThrows(
        InvalidAgentRosterException.class,
        () -> reconcile.reconcile(List.of(qits(DEVELOPER), qits(nameless))));
    assertEquals(List.of(), fixtures.agents());
    assertEquals(0, projects.asked);
  }

  @Test
  void theRostersOnTheClasspathAreValid() {
    List<String> projects = reconcile.declared().stream().map(AgentRoster::project).toList();
    assertTrue(projects.contains("qits"), "the qits roster rides this service: " + projects);
  }
}
