package eu.wohlben.qits.agents.service.support;

import eu.wohlben.qits.agents.service.control.AgentCredentials;
import eu.wohlben.qits.agents.service.control.ProjectDirectory;
import eu.wohlben.qits.agents.service.entity.AgentClaim;
import eu.wohlben.qits.agents.service.entity.AgentTurn;
import eu.wohlben.qits.agents.service.entity.DesiredState;
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
import eu.wohlben.qits.agents.service.entity.ProjectAgent;
import eu.wohlben.qits.agents.service.entity.ReportedState;
import eu.wohlben.qits.agents.service.entity.TurnKind;
import eu.wohlben.qits.eventstream.QitsEvent;
import eu.wohlben.qits.eventstream.QitsEventBus;
import io.quarkus.test.junit.QuarkusMock;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Rows the suites arrange directly, and the fakes they read the effects off: the bus, the project
 * directory and the credentials. Every suite starts from {@link #clear()}: the store is shared by
 * every class of the surefire JVM.
 */
@ApplicationScoped
public class Fixtures {

  /** The project every suite's agents belong to. */
  public static final UUID PROJECT_ID = UUID.fromString("6f1c2d3e-4a5b-4c6d-8e7f-9a0b1c2d3e4f");

  public static final String PROJECT = "qits";

  /** The fixed subject qits-idp gives an agent's token. */
  public static String subject(String project, String name) {
    return "tok-project-agent-" + project + "-" + name;
  }

  /** Deletes every row of the three tables and of the evaluation ledger. */
  @Transactional
  public void clear() {
    AgentTurn.deleteAll();
    AgentClaim.deleteAll();
    ProjectAgent.deleteAll();
    clearLedger();
  }

  /** Deletes every row of the evaluation ledger (V4, qits-2521). */
  @Transactional
  public void clearLedger() {
    EvalItem.deleteAll();
    EvalTransition.deleteAll();
    EvalBlock.deleteAll();
    EvalRequest.deleteAll();
    EvalRequestState.deleteAll();
    EvalRun.deleteAll();
    EvalActivity.deleteAll();
    EvalExit.deleteAll();
    EvalWait.deleteAll();
    EvalUsage.deleteAll();
    EvalUsageMessage.deleteAll();
  }

  /** The project agents' recorded transcript usage, by session and message id. */
  @Transactional
  public List<EvalUsageMessage> usageMessages() {
    return EvalUsageMessage.list("order by sessionId, messageId");
  }

  /** A RUNNING agent of {@link #PROJECT}, holding a token, inserted directly (no event). */
  @Transactional
  public ProjectAgent agent(UUID id, String name, int generation) {
    ProjectAgent agent = new ProjectAgent();
    agent.id = id;
    agent.projectId = PROJECT_ID;
    agent.projectSlug = PROJECT;
    agent.name = name;
    agent.description = "Develops qits work items end to end.";
    agent.brief = "You are the " + name + " of the qits project.";
    agent.recoveryPrompt = "You were restarted; read your claims and carry on.";
    agent.model = null;
    agent.effort = null;
    agent.declarationHash = "a".repeat(64);
    agent.generation = generation;
    agent.desiredState = DesiredState.RUNNING;
    agent.reportedState = ReportedState.UNKNOWN;
    agent.tokenId = UUID.nameUUIDFromBytes(("token-" + name).getBytes());
    agent.tokenSubject = subject(PROJECT, name);
    agent.tokenValue = "qits_tok_contract-" + name;
    agent.createdAt = Instant.parse("2026-11-20T08:00:00Z");
    agent.updatedAt = Instant.parse("2026-11-20T08:00:00Z");
    agent.persist();
    return agent;
  }

  /** The runtime's last report on the agent. */
  @Transactional
  public void reported(UUID agentId, ReportedState state, int generation, String sessionId) {
    ProjectAgent agent = ProjectAgent.findById(agentId);
    agent.reportedState = state;
    agent.reportedGeneration = generation;
    agent.sessionId = sessionId;
    agent.reportedAt = Instant.parse("2026-11-20T09:00:00Z");
  }

  @Transactional
  public void retire(UUID agentId) {
    ProjectAgent agent = ProjectAgent.findById(agentId);
    agent.desiredState = DesiredState.RETIRED;
  }

  /** A claim of the agent on an item; released when {@code releasedAt} is given. */
  @Transactional
  public AgentClaim claim(
      UUID agentId, UUID entityId, String qualifiedId, Instant claimedAt, Instant releasedAt) {
    AgentClaim claim = new AgentClaim();
    claim.id = UUID.randomUUID();
    claim.agentId = agentId;
    claim.entityId = entityId;
    claim.qualifiedId = qualifiedId;
    claim.archetype = "TASK";
    claim.title = "Work item " + qualifiedId;
    claim.claimedBy = "a-person";
    claim.claimedAt = claimedAt;
    claim.releasedAt = releasedAt;
    claim.releasedBy = releasedAt == null ? null : "a-person";
    claim.persist();
    return claim;
  }

  /** A turn in the agent's inbox; delivered when {@code deliveredAt} is given. */
  @Transactional
  public AgentTurn turn(
      UUID id, UUID agentId, int generation, TurnKind kind, String text, Instant createdAt,
      Instant deliveredAt) {
    AgentTurn turn = new AgentTurn();
    turn.id = id;
    turn.agentId = agentId;
    turn.generation = generation;
    turn.kind = kind;
    turn.text = text;
    turn.createdAt = createdAt;
    turn.deliveredAt = deliveredAt;
    turn.persist();
    return turn;
  }

  /** The lease the runtime last recorded for the agent, written directly. */
  @Transactional
  public void lease(UUID agentId, UUID leaseId) {
    ProjectAgent agent = ProjectAgent.findById(agentId);
    agent.leaseId = leaseId;
  }

  /** Puts a token on the agent, as a commission after a re-declaration does. */
  @Transactional
  public void token(UUID agentId, String value) {
    ProjectAgent agent = ProjectAgent.findById(agentId);
    agent.tokenValue = value;
  }

  /**
   * Freezes a trust-anchor bundle on the agent's row, as {@code Agents.storeLease} does when a
   * lease is asked for (qits-2636). Null clears it.
   */
  @Transactional
  public void trustAnchors(UUID agentId, String pem) {
    ProjectAgent agent = ProjectAgent.findById(agentId);
    agent.trustAnchors = pem;
  }

  @Transactional
  public ProjectAgent read(UUID agentId) {
    return ProjectAgent.findById(agentId);
  }

  @Transactional
  public List<ProjectAgent> agents() {
    return ProjectAgent.list("order by name");
  }

  @Transactional
  public List<AgentTurn> turns(UUID agentId) {
    return AgentTurn.list("agentId = ?1 order by createdAt, id", agentId);
  }

  // --- the fakes ----------------------------------------------------------------------------

  /** Installs a bus that records instead of publishing, for the current test; returns its log. */
  public static List<QitsEvent> recordEvents() {
    List<QitsEvent> published = Collections.synchronizedList(new ArrayList<>());
    QuarkusMock.installMockForType(
        new QitsEventBus() {
          @Override
          public void publish(QitsEvent event) {
            published.add(event);
          }

          @Override
          public void publish(QitsEvent event, UUID parentEventId) {
            published.add(event);
          }
        },
        QitsEventBus.class);
    return published;
  }

  /** Installs {@code directory} as qits-projects, for the current test. */
  public static StubDirectory directory(StubDirectory directory) {
    QuarkusMock.installMockForType(directory, ProjectDirectory.class);
    return directory;
  }

  /** Installs {@code credentials} as qits-idp, for the current test. */
  public static StubCredentials credentials(StubCredentials credentials) {
    QuarkusMock.installMockForType(credentials, AgentCredentials.class);
    return credentials;
  }

  /** Installs {@code system} as qits-system's lease doors, for the current test. */
  public static FakeSystem system(FakeSystem system) {
    QuarkusMock.installMockForType(system, eu.wohlben.qits.agents.runtime.SystemLeasesClient.class);
    return system;
  }

  /** Installs {@code trustAnchors} as the platform CA's bundle (qits-2636), for the current test. */
  public static FakeTrustAnchors trustAnchors(FakeTrustAnchors trustAnchors) {
    QuarkusMock.installMockForType(trustAnchors, eu.wohlben.qits.agents.runtime.TrustAnchorSource.class);
    return trustAnchors;
  }

  /** The recorded events of one type. */
  public static <E> List<E> of(List<QitsEvent> events, Class<E> type) {
    synchronized (events) {
      return events.stream().filter(type::isInstance).map(type::cast).toList();
    }
  }
}
