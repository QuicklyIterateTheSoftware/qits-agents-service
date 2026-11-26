package eu.wohlben.qits.agents.service.control;

import eu.wohlben.qits.agents.service.entity.AgentClaim;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.UUID;

/**
 * <b>The Git refs an agent's token may push follow its claims</b> (qits-2705): a pure function from
 * an agent's claims to the {@code gitRefs} qits-idp states on its token.
 *
 * <p>Each OPEN claim ({@code released_at} null) on a TICKET grants {@code
 * refs/heads/ticket/<qualifiedId>} and {@code refs/heads/ticket/<qualifiedId>-*}; on an EPIC, the
 * same under {@code epic/}. Those are the branches a release request may name for the item (the
 * evaluation's attribution rule) and the ones qits-projects checks a request's sources against. A
 * FEATURE, a TASK or an archetype this copy does not know grants nothing: their work rides their
 * ticket's or epic's branch. The archetype is compared ignoring case.
 *
 * <p>A qualified id that is blank, or that holds what a ref may not (whitespace, a control
 * character, {@code *}, {@code ~^:?[\}, {@code ..} or {@code @{}), grants nothing: it could
 * otherwise widen a pattern or be refused by qits-idp, which would refuse the whole list. The
 * answer is sorted, distinct and at most {@value #MAX_ENTRIES} entries of at most {@value
 * #MAX_LENGTH} characters, qits-idp's own limits ({@code GitRefs.stated}).
 */
public final class AgentGitRefs {

  /** qits-idp's limit on a list. */
  public static final int MAX_ENTRIES = 500;

  /** qits-idp's limit on one entry. */
  public static final int MAX_LENGTH = 255;

  private static final String HEADS = "refs/heads/";

  /** What one agent's token should state: the token to change and the refs it may push. */
  public record Scope(UUID agentId, UUID tokenId, List<String> gitRefs) {}

  private AgentGitRefs() {}

  /** The refs the open claims among {@code claims} grant; released ones are ignored. */
  public static List<String> of(Collection<AgentClaim> claims) {
    TreeSet<String> refs = new TreeSet<>();
    for (AgentClaim claim : claims) {
      if (claim == null || claim.releasedAt != null) {
        continue;
      }
      String space = space(claim.archetype);
      if (space == null || !usable(claim.qualifiedId)) {
        continue;
      }
      String exact = HEADS + space + "/" + claim.qualifiedId;
      String pattern = exact + "-*";
      if (pattern.length() > MAX_LENGTH) {
        continue;
      }
      refs.add(exact);
      refs.add(pattern);
    }
    return refs.stream().limit(MAX_ENTRIES).toList();
  }

  /** The branch space an archetype's work is pushed under, or null for none. */
  private static String space(String archetype) {
    if (archetype == null) {
      return null;
    }
    return switch (archetype.trim().toUpperCase(Locale.ROOT)) {
      case "TICKET" -> "ticket";
      case "EPIC" -> "epic";
      default -> null;
    };
  }

  /** Whether a qualified id can be part of a ref without widening or breaking it. */
  static boolean usable(String qualifiedId) {
    if (qualifiedId == null || qualifiedId.isBlank()) {
      return false;
    }
    if (qualifiedId.contains("..") || qualifiedId.contains("@{")) {
      return false;
    }
    for (int i = 0; i < qualifiedId.length(); i++) {
      char c = qualifiedId.charAt(i);
      if (c < 0x20 || c == 0x7f || Character.isWhitespace(c) || "*~^:?[\\".indexOf(c) >= 0) {
        return false;
      }
    }
    return true;
  }
}
