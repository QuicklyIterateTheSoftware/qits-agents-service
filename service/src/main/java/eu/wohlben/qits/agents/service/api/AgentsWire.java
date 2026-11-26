package eu.wohlben.qits.agents.service.api;

import eu.wohlben.qits.agents.service.entity.AgentClaim;
import eu.wohlben.qits.agents.service.entity.AgentTurn;
import eu.wohlben.qits.agents.service.entity.ProjectAgent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Every shape this API serves or reads, in one place, and each one registered for reflection in
 * {@link ApiWireReflection} (ApiWireReflectionTest walks this class's nested types).
 *
 * <p>The token value appears in {@link LaunchDto} and nowhere else.
 */
public final class AgentsWire {

  private AgentsWire() {}

  /** One agent as the list answers it. */
  public record AgentSummaryDto(
      String name,
      String description,
      int generation,
      String desiredState,
      String reportedState,
      Integer reportedGeneration,
      String sessionId,
      Instant reportedAt,
      String reportedDetail,
      String declarationHash,
      String tokenSubject,
      long openClaims) {

    static AgentSummaryDto of(ProjectAgent agent, long openClaims) {
      return new AgentSummaryDto(
          agent.name,
          agent.description,
          agent.generation,
          agent.desiredState.name(),
          agent.reportedState.name(),
          agent.reportedGeneration,
          agent.sessionId,
          agent.reportedAt,
          agent.reportedDetail,
          agent.declarationHash,
          agent.tokenSubject,
          openClaims);
    }
  }

  /** One agent in full: the list's fields plus its declaration's texts. */
  public record AgentDto(
      String name,
      String description,
      int generation,
      String desiredState,
      String reportedState,
      Integer reportedGeneration,
      String sessionId,
      Instant reportedAt,
      String reportedDetail,
      String declarationHash,
      String tokenSubject,
      long openClaims,
      String brief,
      String recoveryPrompt,
      String model,
      String effort) {

    static AgentDto of(ProjectAgent agent, long openClaims) {
      return new AgentDto(
          agent.name,
          agent.description,
          agent.generation,
          agent.desiredState.name(),
          agent.reportedState.name(),
          agent.reportedGeneration,
          agent.sessionId,
          agent.reportedAt,
          agent.reportedDetail,
          agent.declarationHash,
          agent.tokenSubject,
          openClaims,
          agent.brief,
          agent.recoveryPrompt,
          agent.model,
          agent.effort);
    }
  }

  /**
   * A work item the agent holds, or held. {@code releasedOn} is the closed status that released it
   * (qits-2718), absent when an assignee move did.
   */
  public record ClaimDto(
      UUID entityId,
      String qualifiedId,
      String archetype,
      String title,
      String claimedBy,
      Instant claimedAt,
      Instant releasedAt,
      String releasedBy,
      String releasedOn) {

    static ClaimDto of(AgentClaim claim) {
      return new ClaimDto(
          claim.entityId,
          claim.qualifiedId,
          claim.archetype,
          claim.title,
          claim.claimedBy,
          claim.claimedAt,
          claim.releasedAt,
          claim.releasedBy,
          claim.releasedOn);
    }
  }

  /** One turn of the agent's inbox. */
  public record TurnDto(
      UUID id, int generation, String kind, String text, Instant createdAt, Instant deliveredAt) {

    static TurnDto of(AgentTurn turn) {
      return new TurnDto(
          turn.id, turn.generation, turn.kind.name(), turn.text, turn.createdAt, turn.deliveredAt);
    }
  }

  /**
   * What the runtime starts an agent with: its declaration's brief and model, the generation to
   * run, the session to resume, and the credential — the one answer that carries the token.
   */
  public record LaunchDto(
      String project,
      UUID projectId,
      String name,
      int generation,
      String brief,
      String model,
      String effort,
      String sessionId,
      String token,
      String tokenSubject) {

    static LaunchDto of(ProjectAgent agent) {
      return new LaunchDto(
          agent.projectSlug,
          agent.projectId,
          agent.name,
          agent.generation,
          agent.brief,
          agent.model,
          agent.effort,
          agent.sessionId,
          agent.tokenValue,
          agent.tokenSubject);
    }
  }

  /**
   * {@code PUT …/report}: the generation the runtime runs, its state (STARTING, IDLE, BUSY, EXITED
   * or FAILED), and optionally the harness session and a line of detail.
   */
  public record ReportRequest(Integer generation, String state, String sessionId, String detail) {}

  /**
   * {@code POST …/usage}: a batch of the agent's transcript usage, at most 500 messages, each
   * counted once per {@code sessionId} by its {@code messageId} (qits-2521).
   */
  public record UsageReport(String sessionId, List<UsageMessage> messages) {}

  /**
   * One assistant message of the agent's transcript: the Claude model ({@code message.model}), the
   * four token counts of its {@code message.usage}, and the item its worktree names — null for the
   * overseer's own messages.
   */
  public record UsageMessage(
      String messageId,
      String workQualifiedId,
      String model,
      long inputTokens,
      long outputTokens,
      long cacheCreationTokens,
      long cacheReadTokens,
      Instant at) {}

  /** {@code GET /me}: which agent the caller's credential is. */
  public record MeDto(String project, UUID projectId, String name, int generation) {

    static MeDto of(ProjectAgent agent) {
      return new MeDto(agent.projectSlug, agent.projectId, agent.name, agent.generation);
    }
  }

  /** Every refusal's body. */
  public record ErrorBody(String error) {}
}
