package eu.wohlben.qits.agents.service.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.agents.service.entity.AgentClaim;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * {@link AgentGitRefs#of}, in memory (qits-2705): which claims grant which branches, and the limits
 * qits-idp holds a list to.
 */
class AgentGitRefsTest {

  private static AgentClaim claim(String archetype, String qualifiedId) {
    AgentClaim claim = new AgentClaim();
    claim.archetype = archetype;
    claim.qualifiedId = qualifiedId;
    return claim;
  }

  private static AgentClaim released(String archetype, String qualifiedId) {
    AgentClaim claim = claim(archetype, qualifiedId);
    claim.releasedAt = Instant.parse("2026-11-26T10:00:00Z");
    return claim;
  }

  @Test
  void aTicketGrantsItsBranchAndItsSuffixedBranches() {
    assertEquals(
        List.of("refs/heads/ticket/qits-2705", "refs/heads/ticket/qits-2705-*"),
        AgentGitRefs.of(List.of(claim("TICKET", "qits-2705"))));
  }

  @Test
  void anEpicGrantsItsEpicBranches() {
    assertEquals(
        List.of("refs/heads/epic/qits-2518", "refs/heads/epic/qits-2518-*"),
        AgentGitRefs.of(List.of(claim("EPIC", "qits-2518"))));
  }

  @Test
  void featuresTasksAndUnknownArchetypesGrantNothing() {
    assertEquals(
        List.of(),
        AgentGitRefs.of(
            List.of(
                claim("FEATURE", "qits-1"),
                claim("TASK", "qits-2"),
                claim("UNKNOWN", "qits-3"),
                claim(null, "qits-4"))));
  }

  @Test
  void theArchetypeIsComparedIgnoringCase() {
    assertEquals(
        List.of(
            "refs/heads/epic/qits-2", "refs/heads/epic/qits-2-*",
            "refs/heads/ticket/qits-1", "refs/heads/ticket/qits-1-*"),
        AgentGitRefs.of(List.of(claim("ticket", "qits-1"), claim("Epic", "qits-2"))));
  }

  @Test
  void aReleasedClaimGrantsNothing() {
    assertEquals(
        List.of("refs/heads/ticket/qits-2", "refs/heads/ticket/qits-2-*"),
        AgentGitRefs.of(List.of(released("TICKET", "qits-1"), claim("TICKET", "qits-2"))));
  }

  @Test
  void anIdThatCannotBePartOfARefGrantsNothing() {
    List<AgentClaim> claims = new ArrayList<>();
    for (String bad :
        List.of(
            "", " ", "qits 1", "qits-1\n", "qits-\u00011", "qits-*", "qits~1", "qits^1",
            "qits:1", "qits?1", "qits[1", "qits\\1", "qits..1", "qits@{1", "qits\t1")) {
      claims.add(claim("TICKET", bad));
    }
    claims.add(claim("TICKET", null));
    assertEquals(List.of(), AgentGitRefs.of(claims));
  }

  @Test
  void theListIsSortedAndDistinct() {
    assertEquals(
        List.of(
            "refs/heads/epic/qits-9", "refs/heads/epic/qits-9-*",
            "refs/heads/ticket/qits-1", "refs/heads/ticket/qits-1-*",
            "refs/heads/ticket/qits-3", "refs/heads/ticket/qits-3-*"),
        AgentGitRefs.of(
            List.of(
                claim("TICKET", "qits-3"),
                claim("EPIC", "qits-9"),
                claim("TICKET", "qits-1"),
                claim("TICKET", "qits-3"))));
  }

  @Test
  void theListKeepsQitsIdpsLimits() {
    List<AgentClaim> many =
        IntStream.range(0, 400).mapToObj(i -> claim("TICKET", "qits-" + (1000 + i))).toList();
    List<String> refs = AgentGitRefs.of(many);
    assertEquals(AgentGitRefs.MAX_ENTRIES, refs.size());
    assertEquals(refs.stream().sorted().toList(), refs);

    String longest = "x".repeat(AgentGitRefs.MAX_LENGTH - "refs/heads/ticket/".length() - 2);
    assertEquals(2, AgentGitRefs.of(List.of(claim("TICKET", longest))).size());
    assertEquals(List.of(), AgentGitRefs.of(List.of(claim("TICKET", longest + "x"))));
    assertTrue(
        AgentGitRefs.of(List.of(claim("TICKET", longest))).stream()
            .allMatch(ref -> ref.length() <= AgentGitRefs.MAX_LENGTH));
  }
}
