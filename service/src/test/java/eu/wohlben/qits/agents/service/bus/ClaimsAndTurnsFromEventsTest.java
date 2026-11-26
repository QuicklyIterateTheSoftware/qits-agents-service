package eu.wohlben.qits.agents.service.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.agents.service.control.Agents;
import eu.wohlben.qits.agents.service.entity.AgentClaim;
import eu.wohlben.qits.agents.service.entity.AgentTurn;
import eu.wohlben.qits.agents.service.entity.TurnKind;
import eu.wohlben.qits.agents.service.support.Fixtures;
import eu.wohlben.qits.agents.service.support.StubCredentials;
import eu.wohlben.qits.eventstream.control.CanonicalJson;
import eu.wohlben.qits.eventstream.control.EventFrame;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Claims and turns from qits-projects' events, driven through the two durable listeners with frames
 * shaped as qits-projects publishes them (plus a field this copy does not know). The listeners are
 * called directly, without the library's claim on the event, so a redelivery here proves the
 * appliers' own idempotency. qits-idp is {@link StubCredentials}: the Git refs an agent's token may
 * push follow its claims (qits-2705), and the claims follow their items' status (qits-2718).
 */
@QuarkusTest
class ClaimsAndTurnsFromEventsTest {

  static final UUID DEVELOPER = UUID.fromString("d0000000-0000-4000-8000-000000000001");
  static final UUID REVIEWER = UUID.fromString("d0000000-0000-4000-8000-000000000002");
  static final UUID EPIC = UUID.fromString("e0000000-0000-4000-8000-000000000001");
  static final UUID TASK = UUID.fromString("e0000000-0000-4000-8000-000000000002");
  static final UUID TICKET = UUID.fromString("e0000000-0000-4000-8000-000000000003");
  static final Instant AT = Instant.parse("2026-11-23T10:00:00.123456Z");
  static final String DEVELOPER_SUBJECT = Fixtures.subject(Fixtures.PROJECT, "developer");

  @Inject AssignmentListener assignments;

  @Inject CommentListener comments;

  @Inject ClaimClosureListener closures;

  @Inject Agents agents;

  @Inject Fixtures fixtures;

  StubCredentials idp;

  @Inject
  @DataSource("eventstream")
  AgroalDataSource eventstream;

  @BeforeEach
  void arrange() {
    fixtures.clear();
    Fixtures.recordEvents();
    fixtures.agent(DEVELOPER, "developer", 3);
    fixtures.agent(REVIEWER, "reviewer", 1);
    idp = Fixtures.credentials(new StubCredentials());
  }

  // --- frames ----------------------------------------------------------------------------------

  static EventFrame assigned(
      UUID eventId, String assignee, String previous, String changedBy, String changedByName) {
    return assigned(
        eventId, EPIC, "EPIC", 2518, assignee, previous, changedBy, changedByName);
  }

  /** An assignment of any item of the project: {@code qits-<number>}, of {@code archetype}. */
  static EventFrame assigned(
      UUID eventId,
      UUID entityId,
      String archetype,
      int number,
      String assignee,
      String previous,
      String changedBy,
      String changedByName) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("eventId", eventId.toString());
    p.put("entityId", entityId.toString());
    p.put("projectId", Fixtures.PROJECT_ID.toString());
    p.put("archetype", archetype);
    p.put("number", number);
    p.put("qualifiedId", "qits-" + number);
    p.put("title", "Project agents");
    p.put("status", "IMPLEMENTING");
    p.put("assignee", assignee);
    p.put("previousAssignee", previous);
    p.put("claimed", assignee != null);
    p.put("changedBy", changedBy);
    p.put("changedByName", changedByName);
    p.put("assignedAt", AT.toString());
    p.put("aFieldFromTheFuture", "ignored");
    return frame(eventId, "EntityAssigned", p);
  }

  static EventFrame commented(
      UUID eventId, String author, String authorName, String authorAgentOf, String changedBy) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("eventId", eventId.toString());
    p.put("commentId", UUID.randomUUID().toString());
    p.put("entityId", TASK.toString());
    p.put("rootEntityId", EPIC.toString());
    p.put("projectId", Fixtures.PROJECT_ID.toString());
    p.put("archetype", "TASK");
    p.put("number", 2579);
    p.put("title", "Claims and turns");
    p.put("author", author);
    p.put("authorName", authorName);
    p.put("authorAgentOf", authorAgentOf);
    p.put("changedBy", changedBy);
    p.put("mentions", List.of());
    p.put("commentedAt", AT.plusSeconds(60).toString());
    p.put("aFieldFromTheFuture", 7);
    return frame(eventId, "EntityCommented", p);
  }

  static EventFrame frame(UUID eventId, String name, Map<String, Object> payload) {
    return new EventFrame(
        eventId.toString(), name, AT, CanonicalJson.canonicalize(payload), null, null, "dev");
  }

  List<AgentClaim> claims(UUID agent) {
    return agents.claims(agent, true);
  }

  List<AgentTurn> turns(UUID agent) {
    return fixtures.turns(agent);
  }

  // --- EntityAssigned --------------------------------------------------------------------------

  /**
   * The way qits-eventstream really calls a durable listener: inside its claiming transaction, with
   * the eventstream outbox datasource already enlisted. 2026.1124.52642 joined that transaction from
   * the agents datasource and failed every event; the frames-only tests above could not see it.
   */
  @Test
  void aListenerCalledInsideTheClaimingTransactionStillWrites() {
    UUID assignedEvent = UUID.randomUUID();
    UUID commentedEvent = UUID.randomUUID();
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              enlistEventstream();
              assignments.onFrame(assigned(assignedEvent, "developer", null, "person-sub", "Ben"));
            });
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              enlistEventstream();
              comments.onFrame(commented(commentedEvent, "ada", "Ada", null, "ada"));
            });

    assertEquals(1, claims(DEVELOPER).size());
    assertEquals(
        List.of(TurnKind.ASSIGNED, TurnKind.COMMENTED),
        turns(DEVELOPER).stream().map(t -> t.kind).toList());
  }

  private void enlistEventstream() {
    try (Connection connection = eventstream.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("select 1");
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }


  @Test
  void anAssignmentByAPersonOpensAClaimAndQueuesTheAssignedText() {
    UUID event = UUID.randomUUID();
    assignments.onFrame(assigned(event, "developer", null, "person-sub", "Ben"));

    List<AgentClaim> claims = claims(DEVELOPER);
    assertEquals(1, claims.size());
    AgentClaim claim = claims.get(0);
    assertEquals(EPIC, claim.entityId);
    assertEquals("qits-2518", claim.qualifiedId);
    assertEquals("EPIC", claim.archetype);
    assertEquals("Project agents", claim.title);
    assertEquals("person-sub", claim.claimedBy);
    assertEquals(AT, claim.claimedAt);
    assertNull(claim.releasedAt);

    List<AgentTurn> turns = turns(DEVELOPER);
    assertEquals(1, turns.size());
    assertEquals(TurnKind.ASSIGNED, turns.get(0).kind);
    assertEquals("(Ben has assigned qits-2518 to you)", turns.get(0).text);
    assertEquals(event, turns.get(0).sourceEventId);
    assertEquals(3, turns.get(0).generation);
    assertEquals(List.of(), claims(REVIEWER));
  }

  @Test
  void aSelfAssignmentOpensAClaimWithNoTurn() {
    assignments.onFrame(
        assigned(UUID.randomUUID(), "developer", null, DEVELOPER_SUBJECT, "developer"));

    assertEquals(1, claims(DEVELOPER).size());
    assertEquals(DEVELOPER_SUBJECT, claims(DEVELOPER).get(0).claimedBy);
    assertEquals(List.of(), turns(DEVELOPER));
  }

  @Test
  void theTokenSubjectAsAssigneeAlsoMatches() {
    assignments.onFrame(
        assigned(UUID.randomUUID(), DEVELOPER_SUBJECT, null, "person-sub", "Ben"));

    assertEquals(1, claims(DEVELOPER).size());
    assertEquals(1, turns(DEVELOPER).size());
  }

  @Test
  void aReassignmentReleasesTheOldAgentAndOpensTheNew() {
    assignments.onFrame(assigned(UUID.randomUUID(), "developer", null, "person-sub", "Ben"));
    UUID move = UUID.randomUUID();
    Instant later = AT.plusSeconds(3600);
    EventFrame frame = assigned(move, "reviewer", "developer", "person-two", "Anna");
    frame =
        new EventFrame(
            frame.id(),
            frame.name(),
            later,
            frame.payload().replace(AT.toString(), later.toString()),
            null,
            null,
            "dev");
    assignments.onFrame(frame);

    AgentClaim released = claims(DEVELOPER).get(0);
    assertEquals(later, released.releasedAt);
    assertEquals("person-two", released.releasedBy);
    assertEquals(1, turns(DEVELOPER).size(), "a release queues nothing");

    assertEquals(1, claims(REVIEWER).size());
    assertNull(claims(REVIEWER).get(0).releasedAt);
    assertEquals(later, claims(REVIEWER).get(0).claimedAt);
    assertEquals("(Anna has assigned qits-2518 to you)", turns(REVIEWER).get(0).text);
  }

  @Test
  void clearingTheAssigneeReleases() {
    assignments.onFrame(assigned(UUID.randomUUID(), "developer", null, "person-sub", "Ben"));
    assignments.onFrame(assigned(UUID.randomUUID(), null, "developer", "person-sub", "Ben"));

    assertNotNull(claims(DEVELOPER).get(0).releasedAt);
    assertEquals("person-sub", claims(DEVELOPER).get(0).releasedBy);
    assertEquals(0, agents.openClaims(DEVELOPER));
  }

  @Test
  void aRedeliveredEventChangesNothing() {
    EventFrame frame = assigned(UUID.randomUUID(), "developer", null, "person-sub", "Ben");
    assignments.onFrame(frame);
    assignments.onFrame(frame);

    assertEquals(1, claims(DEVELOPER).size());
    assertEquals(1, turns(DEVELOPER).size());

    // Even after a later release, the old assignment arriving again does not reopen the claim.
    assignments.onFrame(assigned(UUID.randomUUID(), null, "developer", "person-sub", "Ben"));
    assignments.onFrame(frame);
    assertEquals(1, claims(DEVELOPER).size());
    assertEquals(0, agents.openClaims(DEVELOPER));
    assertEquals(1, turns(DEVELOPER).size());
  }

  @Test
  void aRetiredAgentGetsNothing() {
    fixtures.retire(DEVELOPER);
    assignments.onFrame(assigned(UUID.randomUUID(), "developer", null, "person-sub", "Ben"));

    assertEquals(List.of(), claims(DEVELOPER));
    assertEquals(List.of(), turns(DEVELOPER));
  }

  @Test
  void anUnreadablePayloadIsSettledUnhandled() {
    assignments.onFrame(
        new EventFrame(UUID.randomUUID().toString(), "EntityAssigned", AT, "not json", null, null,
            "dev"));
    comments.onFrame(
        new EventFrame(UUID.randomUUID().toString(), "EntityCommented", AT, "[1,2]", null, null,
            "dev"));

    assertEquals(List.of(), claims(DEVELOPER));
  }

  // --- the Git refs that follow the claims (qits-2705) ---------------------------------------

  static final UUID DEVELOPER_TOKEN = UUID.nameUUIDFromBytes("token-developer".getBytes());
  static final UUID REVIEWER_TOKEN = UUID.nameUUIDFromBytes("token-reviewer".getBytes());

  static final List<String> TICKET_REFS =
      List.of("refs/heads/ticket/qits-2705", "refs/heads/ticket/qits-2705-*");

  @Test
  void anAssignedTicketLetsTheAgentsTokenPushItsBranches() {
    assignments.onFrame(assigned(UUID.randomUUID(), "developer", null, "person-sub", "Ben"));
    EventFrame ticket =
        assigned(UUID.randomUUID(), TICKET, "TICKET", 2705, "developer", null, "person-sub", "Ben");
    assignments.onFrame(ticket);

    assertEquals(
        List.of(
            "refs/heads/epic/qits-2518",
            "refs/heads/epic/qits-2518-*",
            "refs/heads/ticket/qits-2705",
            "refs/heads/ticket/qits-2705-*"),
        idp.refsOf(DEVELOPER_TOKEN),
        "every open claim's branches, the epic's and the ticket's");
    assertNull(idp.refsOf(REVIEWER_TOKEN), "the reviewer's claims did not move");

    int replaces = idp.replaced.size();
    assignments.onFrame(ticket);
    assertEquals(replaces, idp.replaced.size(), "a redelivery moves no claim and replaces nothing");
  }

  @Test
  void aTicketReassignedAwayLeavesTheOldAgentsToken() {
    assignments.onFrame(
        assigned(UUID.randomUUID(), TICKET, "TICKET", 2705, "developer", null, "person-sub", "Ben"));
    assertEquals(TICKET_REFS, idp.refsOf(DEVELOPER_TOKEN));

    assignments.onFrame(
        assigned(
            UUID.randomUUID(), TICKET, "TICKET", 2705, "reviewer", "developer", "person-sub", "Ben"));

    assertEquals(List.of(), idp.refsOf(DEVELOPER_TOKEN), "the developer may push nothing again");
    assertEquals(TICKET_REFS, idp.refsOf(REVIEWER_TOKEN));
  }

  @Test
  void anAssignedEpicLetsTheTokenPushItsEpicBranches() {
    assignments.onFrame(assigned(UUID.randomUUID(), "developer", null, "person-sub", "Ben"));

    assertEquals(
        List.of("refs/heads/epic/qits-2518", "refs/heads/epic/qits-2518-*"),
        idp.refsOf(DEVELOPER_TOKEN));
  }

  @Test
  void anAssignedTaskGrantsNoBranch() {
    assignments.onFrame(
        assigned(UUID.randomUUID(), TASK, "TASK", 2579, "developer", null, "person-sub", "Ben"));

    assertEquals(1, claims(DEVELOPER).size(), "the task is claimed");
    assertEquals(List.of(), idp.refsOf(DEVELOPER_TOKEN), "but its work rides its ticket's branch");
  }

  @Test
  void aRefusingQitsIdpDoesNotFailTheEvent() {
    idp.down = true;
    assignments.onFrame(
        assigned(UUID.randomUUID(), TICKET, "TICKET", 2705, "developer", null, "person-sub", "Ben"));
    idp.down = false;
    idp.replaceThrows = true;
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              enlistEventstream();
              assignments.onFrame(
                  assigned(UUID.randomUUID(), "developer", null, "person-sub", "Ben"));
            });

    assertEquals(2, claims(DEVELOPER).size(), "both claims committed regardless");
    assertEquals(2, turns(DEVELOPER).size());
    assertTrue(idp.replaced.isEmpty(), "qits-idp took nothing; the reconcile states it later");
  }

  // --- EntityTransitioned: claims close with their items (qits-2718) -------------------------

  /** One entity moved from {@code before} to {@code status} at {@code at}. */
  static EventFrame transitioned(
      UUID eventId,
      UUID entityId,
      String archetype,
      int number,
      String before,
      String status,
      Instant at) {
    return transitioned(
        eventId, at, List.of(entity(entityId, archetype, number, before, status)));
  }

  static Map<String, Object> entity(
      UUID entityId, String archetype, int number, String before, String status) {
    Map<String, Object> e = new LinkedHashMap<>();
    e.put("entityId", entityId.toString());
    e.put("projectId", Fixtures.PROJECT_ID.toString());
    e.put("archetype", archetype);
    e.put("parentId", null);
    e.put("position", null);
    e.put("slug", "qits-" + number);
    e.put("title", "Work item qits-" + number);
    e.put("status", status);
    e.put("statusBefore", before);
    e.put("changedBy", "qits-projects");
    e.put("number", number);
    e.put("aFieldFromTheFuture", true);
    return e;
  }

  static EventFrame transitioned(UUID eventId, Instant at, List<Map<String, Object>> entities) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("eventId", eventId.toString());
    p.put("entities", entities);
    p.put("transitionedAt", at.toString());
    return new EventFrame(
        eventId.toString(), "EntityTransitioned", at, CanonicalJson.canonicalize(p), null, null,
        "dev");
  }

  private void assignTicket() {
    assignments.onFrame(
        assigned(UUID.randomUUID(), TICKET, "TICKET", 2705, "developer", null, "person-sub", "Ben"));
  }

  @Test
  void aTicketReachingDoneReleasesItsClaimAndItsRefs() {
    assignments.onFrame(assigned(UUID.randomUUID(), "developer", null, "person-sub", "Ben"));
    assignTicket();
    Instant done = AT.plusSeconds(600);

    closures.onFrame(
        transitioned(UUID.randomUUID(), TICKET, "TICKET", 2705, "VERIFIED", "DONE", done));

    AgentClaim released =
        claims(DEVELOPER).stream().filter(c -> c.entityId.equals(TICKET)).findFirst().orElseThrow();
    assertEquals(done, released.releasedAt);
    assertEquals("qits-projects", released.releasedBy);
    assertEquals("DONE", released.releasedOn);
    assertEquals(1, agents.openClaims(DEVELOPER), "the epic is still held");
    assertEquals(
        List.of("refs/heads/epic/qits-2518", "refs/heads/epic/qits-2518-*"),
        idp.refsOf(DEVELOPER_TOKEN),
        "the done ticket's branches leave the token at once");
  }

  @Test
  void aDroppedItemReleasesItsClaim() {
    assignTicket();
    closures.onFrame(
        transitioned(
            UUID.randomUUID(), TICKET, "TICKET", 2705, "IMPLEMENTING", "DROPPED",
            AT.plusSeconds(60)));

    assertEquals("DROPPED", claims(DEVELOPER).get(0).releasedOn);
    assertEquals(List.of(), idp.refsOf(DEVELOPER_TOKEN));
  }

  @Test
  void aVerifiedTicketKeepsItsClaimAndItsRefs() {
    assignTicket();
    int replaces = idp.replaced.size();
    closures.onFrame(
        transitioned(
            UUID.randomUUID(), TICKET, "TICKET", 2705, "VERIFYING", "VERIFIED", AT.plusSeconds(60)));

    assertNull(claims(DEVELOPER).get(0).releasedAt, "its wrapper delivery still needs the refs");
    assertEquals(replaces, idp.replaced.size(), "nothing moved, nothing replaced");
    assertEquals(TICKET_REFS, idp.refsOf(DEVELOPER_TOKEN));
  }

  @Test
  void anEpicClosedWithItsPiecesReleasesEveryClaimInTheBatch() {
    assignments.onFrame(assigned(UUID.randomUUID(), "developer", null, "person-sub", "Ben"));
    assignments.onFrame(
        assigned(UUID.randomUUID(), TASK, "TASK", 2579, "reviewer", null, "person-sub", "Ben"));

    closures.onFrame(
        transitioned(
            UUID.randomUUID(),
            AT.plusSeconds(60),
            List.of(
                entity(EPIC, "EPIC", 2518, "IMPLEMENTING", "DONE"),
                entity(TASK, "TASK", 2579, "IMPLEMENTING", "DONE"))));

    assertEquals(0, agents.openClaims(DEVELOPER));
    assertEquals(0, agents.openClaims(REVIEWER));
    assertEquals(List.of(), idp.refsOf(DEVELOPER_TOKEN));
  }

  @Test
  void aCloseOlderThanTheClaimReleasesNothing() {
    assignTicket();
    closures.onFrame(
        transitioned(
            UUID.randomUUID(), TICKET, "TICKET", 2705, "IMPLEMENTING", "DROPPED",
            AT.minusSeconds(60)));

    assertNull(claims(DEVELOPER).get(0).releasedAt, "a late catch-up cannot close a newer claim");
  }

  @Test
  void aReopenedItemGivesTheClaimAndItsRefsBack() {
    assignTicket();
    Instant dropped = AT.plusSeconds(60);
    Instant reopened = AT.plusSeconds(120);
    closures.onFrame(
        transitioned(UUID.randomUUID(), TICKET, "TICKET", 2705, "IMPLEMENTING", "DROPPED", dropped));
    assertEquals(List.of(), idp.refsOf(DEVELOPER_TOKEN));

    EventFrame reopen =
        transitioned(UUID.randomUUID(), TICKET, "TICKET", 2705, "DROPPED", "REPORTED", reopened);
    closures.onFrame(reopen);

    List<AgentClaim> claims = claims(DEVELOPER);
    assertEquals(2, claims.size(), "the closed claim stays in the history");
    assertEquals(reopened, claims.get(0).claimedAt);
    assertNull(claims.get(0).releasedAt);
    assertEquals("qits-2705", claims.get(0).qualifiedId);
    assertEquals("TICKET", claims.get(0).archetype);
    assertEquals("qits-projects", claims.get(0).claimedBy);
    assertEquals(TICKET_REFS, idp.refsOf(DEVELOPER_TOKEN));
    assertEquals(1, turns(DEVELOPER).size(), "the reopen queues no turn");

    closures.onFrame(reopen);
    assertEquals(2, claims(DEVELOPER).size(), "a redelivered reopen changes nothing");
  }

  @Test
  void aRedeliveredCloseChangesNothing() {
    assignTicket();
    EventFrame done =
        transitioned(
            UUID.randomUUID(), TICKET, "TICKET", 2705, "VERIFIED", "DONE", AT.plusSeconds(60));
    closures.onFrame(done);
    int replaces = idp.replaced.size();
    closures.onFrame(done);

    assertEquals(1, claims(DEVELOPER).size());
    assertEquals(AT.plusSeconds(60), claims(DEVELOPER).get(0).releasedAt);
    assertEquals(replaces, idp.replaced.size());
  }

  @Test
  void anItemAssignedAwayWhileClosedIsNotGivenBackOnAReopen() {
    assignTicket();
    closures.onFrame(
        transitioned(
            UUID.randomUUID(), TICKET, "TICKET", 2705, "IMPLEMENTING", "DROPPED",
            AT.plusSeconds(60)));
    EventFrame cleared =
        assigned(UUID.randomUUID(), TICKET, "TICKET", 2705, null, "developer", "person-sub", "Ben");
    Instant later = AT.plusSeconds(120);
    assignments.onFrame(
        new EventFrame(
            cleared.id(), cleared.name(), later,
            cleared.payload().replace(AT.toString(), later.toString()), null, null, "dev"));

    closures.onFrame(
        transitioned(
            UUID.randomUUID(), TICKET, "TICKET", 2705, "DROPPED", "REPORTED", AT.plusSeconds(180)));

    assertEquals(1, claims(DEVELOPER).size());
    assertEquals(0, agents.openClaims(DEVELOPER));
    assertNull(claims(DEVELOPER).get(0).releasedOn);
  }

  @Test
  void aClosureWithQitsIdpDownStillConsumesTheEvent() {
    assignTicket();
    idp.replaceThrows = true;
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              enlistEventstream();
              closures.onFrame(
                  transitioned(
                      UUID.randomUUID(), TICKET, "TICKET", 2705, "VERIFIED", "DONE",
                      AT.plusSeconds(60)));
            });

    assertEquals("DONE", claims(DEVELOPER).get(0).releasedOn, "the release committed regardless");
  }

  @Test
  void anUnreadableTransitionIsSettledUnhandled() {
    assignTicket();
    closures.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(), "EntityTransitioned", AT, "not json", null, null, "dev"));

    assertEquals(1, agents.openClaims(DEVELOPER));
  }

  // --- EntityCommented -------------------------------------------------------------------------

  @Test
  void aCommentOnATaskUnderAClaimedEpicQueuesCommentedWithTheEpicsId() {
    assignments.onFrame(
        assigned(UUID.randomUUID(), "developer", null, DEVELOPER_SUBJECT, "developer"));
    UUID event = UUID.randomUUID();
    comments.onFrame(commented(event, "person-sub", "Ben", null, "person-sub"));
    comments.onFrame(commented(event, "person-sub", "Ben", null, "person-sub"));

    List<AgentTurn> turns = turns(DEVELOPER);
    assertEquals(1, turns.size(), "once, redelivered or not");
    assertEquals(TurnKind.COMMENTED, turns.get(0).kind);
    assertEquals("(Ben just commented on qits-2518)", turns.get(0).text);
    assertEquals(event, turns.get(0).sourceEventId);
    assertEquals(3, turns.get(0).generation);
    assertEquals(List.of(), turns(REVIEWER), "the reviewer holds nothing");
  }

  @Test
  void theAgentsOwnCommentQueuesNothing() {
    assignments.onFrame(
        assigned(UUID.randomUUID(), "developer", null, DEVELOPER_SUBJECT, "developer"));
    comments.onFrame(
        commented(UUID.randomUUID(), DEVELOPER.toString(), "developer", "qits-2518",
            DEVELOPER_SUBJECT));

    assertEquals(List.of(), turns(DEVELOPER));
  }

  @Test
  void authorAgentOfWinsOverAuthorName() {
    assignments.onFrame(
        assigned(UUID.randomUUID(), "developer", null, DEVELOPER_SUBJECT, "developer"));
    comments.onFrame(
        commented(UUID.randomUUID(), REVIEWER.toString(), "reviewer", "qits-2600",
            Fixtures.subject(Fixtures.PROJECT, "reviewer")));
    comments.onFrame(commented(UUID.randomUUID(), "person-sub", null, null, "person-sub"));

    List<String> texts = turns(DEVELOPER).stream().map(t -> t.text).sorted().toList();
    assertEquals(
        List.of(
            "(person-sub just commented on qits-2518)",
            "(qits-2600's agent just commented on qits-2518)"),
        texts,
        "the agent's work item over its name; the principal when there is no name");
  }

  @Test
  void aCommentOnAReleasedItemQueuesNothing() {
    assignments.onFrame(
        assigned(UUID.randomUUID(), "developer", null, DEVELOPER_SUBJECT, "developer"));
    assignments.onFrame(assigned(UUID.randomUUID(), null, "developer", "person-sub", "Ben"));
    comments.onFrame(commented(UUID.randomUUID(), "person-sub", "Ben", null, "person-sub"));

    assertEquals(List.of(), turns(DEVELOPER));
  }
}
