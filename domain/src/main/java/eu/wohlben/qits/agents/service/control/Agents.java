package eu.wohlben.qits.agents.service.control;

import eu.wohlben.qits.agents.contract.AgentDeclaration;
import eu.wohlben.qits.agents.service.entity.AgentClaim;
import eu.wohlben.qits.agents.service.entity.AgentTurn;
import eu.wohlben.qits.agents.service.entity.DesiredState;
import eu.wohlben.qits.agents.service.entity.ProjectAgent;
import eu.wohlben.qits.agents.service.entity.ReportedState;
import eu.wohlben.qits.agents.service.entity.TurnKind;
import eu.wohlben.qits.agents.service.events.ProjectAgentChanged;
import eu.wohlben.qits.eventstream.QitsEventBus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jboss.logging.Logger;

/**
 * <b>The agent rows, read and written in one place.</b> Every reader of an agent — the doors, the
 * roster reconcile, and the runtime qits-2519 adds in process ({@code
 * eu.wohlben.qits.agents.runtime}) — goes through this class, and every write that moves an agent's
 * generation, desired state or reported state publishes {@link ProjectAgentChanged} from {@link
 * #changed}, in the transaction that writes the row, after its flush. Nothing else publishes it.
 *
 * <p>The token value is read here only by {@code launch}; every other answer is built from the row
 * by the REST layer, which never copies it.
 */
@ApplicationScoped
public class Agents {

  private static final Logger LOG = Logger.getLogger(Agents.class);

  private static final Pattern CANONICAL_UUID =
      Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

  @Inject QitsEventBus bus;

  // --- reads -----------------------------------------------------------------------------------

  /** Every agent the runtime should keep running, by project slug and name. */
  @Transactional
  public List<ProjectAgent> running() {
    return ProjectAgent.list(
        "desiredState = ?1 order by projectSlug, name", DesiredState.RUNNING);
  }

  @Transactional
  public Optional<ProjectAgent> byId(UUID id) {
    return ProjectAgent.findByIdOptional(id);
  }

  /** A project's agents by name; {@code project} is the project's slug or its id. */
  @Transactional
  public List<ProjectAgent> ofProject(String project) {
    UUID id = uuid(project);
    if (id != null) {
      return ProjectAgent.list("projectId = ?1 order by name", id);
    }
    return ProjectAgent.list("projectSlug = ?1 order by name", project);
  }

  /** One agent of a project; {@code project} is the project's slug or its id. */
  @Transactional
  public Optional<ProjectAgent> find(String project, String name) {
    UUID id = uuid(project);
    if (id != null) {
      return ProjectAgent.find("projectId = ?1 and name = ?2", id, name).firstResultOptional();
    }
    return ProjectAgent.find("projectSlug = ?1 and name = ?2", project, name)
        .firstResultOptional();
  }

  /** The agent whose credential has this subject — the caller of {@code GET /me}. */
  @Transactional
  public Optional<ProjectAgent> bySubject(String subject) {
    if (subject == null || subject.isBlank()) {
      return Optional.empty();
    }
    return ProjectAgent.find("tokenSubject = ?1", subject).firstResultOptional();
  }

  /** How many items the agent holds open. */
  @Transactional
  public long openClaims(UUID agentId) {
    return AgentClaim.count("agentId = ?1 and releasedAt is null", agentId);
  }

  /** The agent's claims, newest first; only the open ones unless {@code all}. */
  @Transactional
  public List<AgentClaim> claims(UUID agentId, boolean all) {
    if (all) {
      return AgentClaim.list("agentId = ?1 order by claimedAt desc, id", agentId);
    }
    return AgentClaim.list(
        "agentId = ?1 and releasedAt is null order by claimedAt desc, id", agentId);
  }

  /** The agent's turns, oldest first; only the undelivered ones when {@code pending}. */
  @Transactional
  public List<AgentTurn> turns(UUID agentId, boolean pending) {
    if (pending) {
      return AgentTurn.list(
          "agentId = ?1 and deliveredAt is null order by createdAt, id", agentId);
    }
    return AgentTurn.list("agentId = ?1 order by createdAt, id", agentId);
  }

  // --- the runtime's writes --------------------------------------------------------------------

  /**
   * Marks one of the agent's turns delivered. Idempotent: a turn already delivered keeps its first
   * {@code delivered_at}.
   *
   * @throws AgentsException 404 when the turn is not this agent's
   */
  @Transactional
  public void delivered(UUID agentId, UUID turnId) {
    AgentTurn turn = AgentTurn.findById(turnId);
    if (turn == null || !turn.agentId.equals(agentId)) {
      throw AgentsException.notFound("no turn " + turnId + " for this agent");
    }
    if (turn.deliveredAt == null) {
      turn.deliveredAt = now();
    }
  }

  /**
   * What the runtime says the agent is doing. Publishes {@link ProjectAgentChanged} when the
   * reported state moves.
   *
   * @throws AgentsException 409 for a generation newer than the row's
   */
  @Transactional
  public ProjectAgent report(
      UUID agentId, int generation, ReportedState state, String sessionId, String detail) {
    ProjectAgent agent = ProjectAgent.findById(agentId);
    if (agent == null) {
      throw AgentsException.notFound("no such agent");
    }
    if (generation > agent.generation) {
      throw AgentsException.conflict(
          "generation "
              + generation
              + " is newer than the agent's generation "
              + agent.generation);
    }
    boolean moved = agent.reportedState != state;
    Instant now = now();
    agent.reportedState = state;
    agent.reportedGeneration = generation;
    agent.sessionId = sessionId;
    agent.reportedDetail = detail;
    agent.reportedAt = now;
    agent.updatedAt = now;
    if (moved) {
      changed(agent, now);
    }
    return agent;
  }

  // --- the roster reconcile's writes -----------------------------------------------------------

  /** What {@link #declare} did to the row. */
  public enum Declared {
    /** A new row, generation 1, and no turn. */
    INSERTED,
    /** The hash is the row's and the agent is RUNNING: nothing was written at all. */
    UNCHANGED,
    /** The hash moved: the declaration copied, generation + 1, a RECOVERY turn queued. */
    NEW_GENERATION,
    /** A RETIRED agent declared again: as {@link #NEW_GENERATION}, RUNNING again. */
    REDECLARED
  }

  /** The outcome of one declaration: the row's id, and whether it still needs a token. */
  public record Declaration(UUID agentId, Declared declared, boolean needsToken) {}

  /**
   * Applies one declared agent to its row, as the roster reconcile's rules say. Publishes {@link
   * ProjectAgentChanged} for every outcome but {@link Declared#UNCHANGED}.
   */
  @Transactional
  public Declaration declare(
      UUID projectId, String projectSlug, AgentDeclaration declaration, String hash) {
    ProjectAgent agent =
        ProjectAgent.<ProjectAgent>find("projectId = ?1 and name = ?2", projectId, declaration.name())
            .firstResult();
    Instant now = now();
    if (agent == null) {
      agent = new ProjectAgent();
      agent.id = UUID.randomUUID();
      agent.projectId = projectId;
      agent.name = declaration.name();
      agent.generation = 1;
      agent.reportedState = ReportedState.UNKNOWN;
      agent.createdAt = now;
      copy(agent, projectSlug, declaration, hash, now);
      agent.persist();
      LOG.infof(
          "agent %s/%s declared, generation 1 (%s)", projectSlug, agent.name, hash);
      changed(agent, now);
      return new Declaration(agent.id, Declared.INSERTED, true);
    }
    boolean retired = agent.desiredState == DesiredState.RETIRED;
    if (!retired && hash.equals(agent.declarationHash)) {
      return new Declaration(agent.id, Declared.UNCHANGED, agent.tokenId == null);
    }
    agent.generation = agent.generation + 1;
    copy(agent, projectSlug, declaration, hash, now);
    AgentTurn recovery = new AgentTurn();
    recovery.id = UUID.randomUUID();
    recovery.agentId = agent.id;
    recovery.generation = agent.generation;
    recovery.kind = TurnKind.RECOVERY;
    recovery.text = declaration.recoveryPrompt();
    recovery.createdAt = now;
    recovery.persist();
    LOG.infof(
        "agent %s/%s %s, generation %d (%s)",
        projectSlug,
        agent.name,
        retired ? "declared again" : "changed",
        agent.generation,
        hash);
    changed(agent, now);
    return new Declaration(
        agent.id,
        retired ? Declared.REDECLARED : Declared.NEW_GENERATION,
        // A re-declared agent is commissioned afresh, which rotates any value still held.
        retired || agent.tokenId == null);
  }

  /**
   * RETIRES every RUNNING agent of the project its roster no longer declares, and answers them.
   * Their tokens are revoked by the caller, outside this transaction ({@link #clearToken}).
   */
  @Transactional
  public List<ProjectAgent> retireUndeclared(UUID projectId, Set<String> declared) {
    List<ProjectAgent> retired = new ArrayList<>();
    List<ProjectAgent> running =
        ProjectAgent.list(
            "projectId = ?1 and desiredState = ?2", projectId, DesiredState.RUNNING);
    Instant now = now();
    for (ProjectAgent agent : running) {
      if (declared.contains(agent.name)) {
        continue;
      }
      agent.desiredState = DesiredState.RETIRED;
      agent.updatedAt = now;
      LOG.infof("agent %s/%s retired: its roster no longer declares it", agent.projectSlug, agent.name);
      changed(agent, now);
      retired.add(agent);
    }
    return retired;
  }

  /** The project's RETIRED agents still holding a token, which the reconcile revokes. */
  @Transactional
  public List<ProjectAgent> retiredHoldingTokens(UUID projectId) {
    return ProjectAgent.list(
        "projectId = ?1 and desiredState = ?2 and tokenId is not null",
        projectId,
        DesiredState.RETIRED);
  }

  /** Stores a commissioned token on the row. */
  @Transactional
  public void storeToken(UUID agentId, AgentCredentials.Issued issued) {
    ProjectAgent agent = ProjectAgent.findById(agentId);
    if (agent == null) {
      return;
    }
    agent.tokenId = issued.tokenId();
    agent.tokenSubject = issued.subject();
    agent.tokenValue = issued.token();
    agent.updatedAt = now();
  }

  /**
   * Forgets a revoked token — only when the row still holds that token, so a token commissioned
   * in between is kept. The subject stays: it is the agent's fixed identity, and a re-declared
   * agent is commissioned under the same one.
   */
  @Transactional
  public void clearToken(UUID agentId, UUID tokenId) {
    ProjectAgent agent = ProjectAgent.findById(agentId);
    if (agent == null || !tokenId.equals(agent.tokenId)) {
      return;
    }
    agent.tokenId = null;
    agent.tokenValue = null;
    agent.updatedAt = now();
  }

  // --- the one publisher -----------------------------------------------------------------------

  /** Publishes the row's state, after its flush, in the caller's transaction. */
  private void changed(ProjectAgent agent, Instant at) {
    ProjectAgent.flush();
    bus.publish(
        new ProjectAgentChanged(
            UUID.randomUUID(),
            agent.projectId,
            agent.projectSlug,
            agent.name,
            agent.generation,
            agent.desiredState.name(),
            agent.reportedState.name(),
            agent.declarationHash,
            at,
            at));
  }

  private static void copy(
      ProjectAgent agent,
      String projectSlug,
      AgentDeclaration declaration,
      String hash,
      Instant now) {
    agent.projectSlug = projectSlug;
    agent.description = declaration.description();
    agent.brief = declaration.brief();
    agent.recoveryPrompt = declaration.recoveryPrompt();
    agent.model = declaration.model().orElse(null);
    agent.effort = declaration.effort().orElse(null);
    agent.declarationHash = hash;
    agent.desiredState = DesiredState.RUNNING;
    agent.updatedAt = now;
  }

  /** Microseconds, as postgres stores them, so a read-back equals what was written. */
  private static Instant now() {
    return Instant.now().truncatedTo(ChronoUnit.MICROS);
  }

  /** The value as a UUID when it is one in canonical form; a project slug never is. */
  private static UUID uuid(String value) {
    if (value == null || !CANONICAL_UUID.matcher(value).matches()) {
      return null;
    }
    return UUID.fromString(value);
  }
}
