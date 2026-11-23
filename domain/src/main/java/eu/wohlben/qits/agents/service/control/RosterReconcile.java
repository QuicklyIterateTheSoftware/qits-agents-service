package eu.wohlben.qits.agents.service.control;

import eu.wohlben.qits.agents.contract.AgentDeclaration;
import eu.wohlben.qits.agents.contract.AgentRoster;
import eu.wohlben.qits.agents.contract.AgentRosterDocument;
import eu.wohlben.qits.agents.contract.AgentRosters;
import eu.wohlben.qits.agents.contract.InvalidAgentRosterException;
import eu.wohlben.qits.agents.service.entity.ProjectAgent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * <b>The rosters on this service's classpath, made rows.</b> Every project's roster jar ({@code
 * META-INF/services/eu.wohlben.qits.agents.contract.AgentRoster}) is loaded and validated at boot;
 * a broken roster throws {@link InvalidAgentRosterException} out of {@link #onStart} and the
 * process does not start, so it never deploys.
 *
 * <p>For each roster: the project's id from qits-projects by slug ({@link ProjectDirectory}; an
 * unknown slug is a WARN and the roster is skipped), then every declared agent through {@link
 * Agents#declare} — insert at generation 1; an equal hash writes nothing; a moved hash, or a
 * RETIRED agent declared again, is generation + 1 with a RECOVERY turn — and every RUNNING agent
 * the roster no longer declares RETIRED. Each agent's token is commissioned when its row holds
 * none, and a RETIRED agent's token is revoked; both calls to qits-idp happen outside the row's
 * transaction, and a failed one is retried by the next run.
 *
 * <p><b>Idempotent</b>: a second run over the same rosters writes nothing at all. That is what lets
 * {@code RosterReconcileTrigger} re-run it periodically, healing a boot that could not reach
 * qits-projects or qits-idp. {@code qits.agents.roster-reconcile-at-boot} turns the boot run off
 * ({@code %test}, where suites call {@link #reconcile(List)} with rosters of their own); the
 * validation still runs.
 */
@ApplicationScoped
public class RosterReconcile {

  private static final Logger LOG = Logger.getLogger(RosterReconcile.class);

  @Inject Agents agents;

  @Inject ProjectDirectory directory;

  @Inject AgentCredentials credentials;

  @ConfigProperty(name = "qits.agents.roster-reconcile-at-boot")
  boolean atBoot;

  /** What one run did: how many rosters it applied, and whether any part has to be retried. */
  public record Outcome(int applied, int skipped, boolean complete) {}

  /** Validates every roster, failing the boot on a broken one, then reconciles when switched on. */
  void onStart(@Observes StartupEvent start) {
    List<AgentRoster> rosters = declared();
    LOG.infof(
        "agent rosters on the classpath: %s",
        rosters.stream().map(AgentRoster::project).sorted().toList());
    if (atBoot) {
      reconcile(rosters);
    }
  }

  /**
   * Every roster on this service's classpath, validated.
   *
   * @throws InvalidAgentRosterException naming the first rule a roster breaks
   */
  public List<AgentRoster> declared() {
    return AgentRosters.load(Thread.currentThread().getContextClassLoader());
  }

  /** Reconciles the rosters on this service's classpath. */
  public Outcome reconcile() {
    return reconcile(declared());
  }

  /**
   * Reconciles the given rosters: the entry point a suite drives. Each roster is validated first,
   * so a broken one changes nothing.
   *
   * @throws InvalidAgentRosterException naming the first rule a roster breaks
   */
  public Outcome reconcile(List<AgentRoster> rosters) {
    rosters.forEach(AgentRosters::validate);
    if (rosters.isEmpty()) {
      return new Outcome(0, 0, true);
    }
    Map<String, UUID> projects;
    try {
      projects = directory.projectsBySlug();
    } catch (ProjectDirectory.Unavailable unavailable) {
      LOG.warnf(
          "the agent rosters are not reconciled: qits-projects cannot be asked (%s); the next run"
              + " retries",
          unavailable.getMessage());
      return new Outcome(0, rosters.size(), false);
    }
    int applied = 0;
    int skipped = 0;
    boolean complete = true;
    for (AgentRoster roster : rosters) {
      UUID projectId = projects.get(roster.project());
      if (projectId == null) {
        LOG.warnf(
            "the agent roster of project %s is skipped: qits-projects knows no such project",
            roster.project());
        skipped++;
        continue;
      }
      complete &= apply(roster, projectId);
      applied++;
    }
    return new Outcome(applied, skipped, complete);
  }

  /** One roster of a known project; false when a token call has to be retried. */
  private boolean apply(AgentRoster roster, UUID projectId) {
    String slug = roster.project();
    boolean complete = true;
    for (AgentDeclaration declaration : roster.agents()) {
      String hash = AgentRosterDocument.hash(declaration);
      Agents.Declaration declared;
      try {
        declared = agents.declare(projectId, slug, declaration, hash);
      } catch (RuntimeException failed) {
        LOG.warnf(
            failed,
            "agent %s/%s could not be reconciled; the next run retries",
            slug,
            declaration.name());
        complete = false;
        continue;
      }
      if (declared.needsToken()) {
        complete &= commission(declared.agentId(), slug, declaration.name(), projectId);
      }
    }
    Set<String> names =
        roster.agents().stream().map(AgentDeclaration::name).collect(Collectors.toSet());
    agents.retireUndeclared(projectId, names);
    for (ProjectAgent retired : agents.retiredHoldingTokens(projectId)) {
      if (credentials.revoke(retired.tokenId)) {
        agents.clearToken(retired.id, retired.tokenId);
        LOG.infof("agent %s/%s: token %s revoked", slug, retired.name, retired.tokenId);
      } else {
        complete = false;
      }
    }
    return complete;
  }

  private boolean commission(UUID agentId, String slug, String name, UUID projectId) {
    String contextId = slug + "-" + name;
    try {
      AgentCredentials.Issued issued = credentials.commission(contextId, projectId);
      agents.storeToken(agentId, issued);
      LOG.infof(
          "agent %s/%s: token %s commissioned as %s", slug, name, issued.tokenId(), issued.subject());
      return true;
    } catch (AgentCredentials.Refused refused) {
      LOG.warnf(
          "agent %s/%s has no token: %s; the next run retries", slug, name, refused.getMessage());
      return false;
    }
  }
}
