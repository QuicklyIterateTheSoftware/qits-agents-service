package eu.wohlben.qits.agents.service.support;

import eu.wohlben.qits.agents.service.entity.AgentClaim;
import eu.wohlben.qits.agents.service.entity.EvalActivity;
import eu.wohlben.qits.agents.service.entity.EvalBlock;
import eu.wohlben.qits.agents.service.entity.EvalExit;
import eu.wohlben.qits.agents.service.entity.EvalItem;
import eu.wohlben.qits.agents.service.entity.EvalRequest;
import eu.wohlben.qits.agents.service.entity.EvalRequestState;
import eu.wohlben.qits.agents.service.entity.EvalRun;
import eu.wohlben.qits.agents.service.entity.EvalTransition;
import eu.wohlben.qits.agents.service.entity.EvalUsage;
import eu.wohlben.qits.agents.service.entity.EvalUsageMessage;
import eu.wohlben.qits.agents.service.entity.EvalWait;
import java.time.Instant;
import java.util.UUID;

/**
 * Rows of the evaluation ledger (qits-2521) as the suites seed them: in memory for {@code
 * control.Evaluation.compute}, persisted ({@link Fixtures#persist}) for the door. Every row of
 * {@link Fixtures#PROJECT_ID}.
 */
public final class LedgerRows {

  private LedgerRows() {}

  /** A work id derived from the qualified id, so a suite never has to carry both. */
  public static UUID work(String qualifiedId) {
    return UUID.nameUUIDFromBytes(("work-" + qualifiedId).getBytes());
  }

  public static EvalItem item(
      String qualifiedId, String archetype, Instant workspaceTaken, Instant agentTaken) {
    EvalItem item = new EvalItem();
    item.workId = work(qualifiedId);
    item.projectId = Fixtures.PROJECT_ID;
    item.qualifiedId = qualifiedId;
    item.archetype = archetype;
    item.workspaceTakenAt = workspaceTaken;
    item.agentTakenAt = agentTaken;
    item.agentName = agentTaken == null ? null : "developer";
    return item;
  }

  public static EvalTransition transition(String qualifiedId, String status, Instant at) {
    EvalTransition row = new EvalTransition();
    row.workId = work(qualifiedId);
    row.status = status;
    row.at = at;
    return row;
  }

  public static EvalBlock block(String qualifiedId, Instant at) {
    EvalBlock row = new EvalBlock();
    row.sourceEventId = UUID.randomUUID();
    row.workId = work(qualifiedId);
    row.at = at;
    return row;
  }

  public static EvalRequest request(String requestId, String state, Instant at, String... branches) {
    EvalRequest row = new EvalRequest();
    row.requestId = requestId;
    row.projectId = Fixtures.PROJECT_ID;
    row.qualifiedId = "qits-rr-" + requestId;
    row.repoName = "qits-agents-service";
    row.branches = branches;
    row.state = state;
    row.changedAt = at;
    return row;
  }

  public static EvalRequestState requestState(String requestId, String state, Instant at) {
    EvalRequestState row = new EvalRequestState();
    row.requestId = requestId;
    row.state = state;
    row.at = at;
    return row;
  }

  public static EvalRun run(
      String runId, String requestId, Instant first, Instant terminal, String status) {
    EvalRun row = new EvalRun();
    row.runId = runId;
    row.projectId = Fixtures.PROJECT_ID;
    row.requestId = requestId;
    row.firstAt = first;
    row.terminalAt = terminal;
    row.terminalStatus = status;
    return row;
  }

  public static EvalActivity activity(String model, String subject, String state, Instant at) {
    EvalActivity row = new EvalActivity();
    row.sourceEventId = UUID.randomUUID();
    row.model = model;
    row.projectId = Fixtures.PROJECT_ID;
    row.subject = subject;
    row.state = state;
    row.at = at;
    return row;
  }

  public static EvalExit exit(String qualifiedId, Instant at) {
    EvalExit row = new EvalExit();
    row.sourceEventId = UUID.randomUUID();
    row.model = "WORKSPACE";
    row.subject = "ws-agent-" + qualifiedId;
    row.workId = work(qualifiedId);
    row.at = at;
    row.detail = "SELF EXITED exit 1";
    return row;
  }

  public static EvalWait waitClosed(String model, String qualifiedId, String state, Instant at) {
    EvalWait row = new EvalWait();
    row.waitId = UUID.randomUUID().toString();
    row.model = model;
    row.workId = work(qualifiedId);
    row.state = state;
    row.closedAt = at;
    return row;
  }

  public static EvalUsage usage(
      String qualifiedId, String claudeModel, int messages, long input, long output, long creation,
      long read, Instant at) {
    EvalUsage row = new EvalUsage();
    row.sourceEventId = UUID.randomUUID();
    row.model = "WORKSPACE";
    row.projectId = Fixtures.PROJECT_ID;
    row.workId = work(qualifiedId);
    row.sessionId = "ws-session-" + qualifiedId;
    row.claudeModel = claudeModel;
    row.messages = messages;
    row.inputTokens = input;
    row.outputTokens = output;
    row.cacheCreationTokens = creation;
    row.cacheReadTokens = read;
    row.at = at;
    return row;
  }

  public static EvalUsageMessage message(
      UUID agentId, String qualifiedId, String claudeModel, long input, long output, long creation,
      long read, Instant at) {
    EvalUsageMessage row = new EvalUsageMessage();
    row.sessionId = "pa-session";
    row.messageId = "msg_" + UUID.randomUUID();
    row.agentId = agentId;
    row.workQualifiedId = qualifiedId;
    row.claudeModel = claudeModel;
    row.inputTokens = input;
    row.outputTokens = output;
    row.cacheCreationTokens = creation;
    row.cacheReadTokens = read;
    row.at = at;
    return row;
  }

  /** An agent's claim, as {@code Fixtures.claim} writes one, in memory. */
  public static AgentClaim claim(UUID agentId, String qualifiedId, Instant from, Instant to) {
    AgentClaim claim = new AgentClaim();
    claim.id = UUID.randomUUID();
    claim.agentId = agentId;
    claim.entityId = work(qualifiedId);
    claim.qualifiedId = qualifiedId;
    claim.archetype = "TICKET";
    claim.title = "Work item " + qualifiedId;
    claim.claimedBy = "a-person";
    claim.claimedAt = from;
    claim.releasedAt = to;
    return claim;
  }
}
