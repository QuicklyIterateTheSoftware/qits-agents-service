package eu.wohlben.qits.agents.service.reconcile;

import eu.wohlben.qits.agents.contract.AgentRoster;
import eu.wohlben.qits.agents.service.control.RosterReconcile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/**
 * Not ready while no agent roster is on the classpath. This service exists to run the rosters it
 * ships with, so an empty set is a broken build, never a quiet state: 2026.1123.194720's native
 * image lost its roster to the service loader ("agent rosters on the classpath: []") and still
 * deployed healthy. Readiness is what both the packaged native IT and the deployer ask.
 */
@Readiness
@ApplicationScoped
public class RosterReadiness implements HealthCheck {

  @Inject RosterReconcile reconcile;

  @Override
  public HealthCheckResponse call() {
    List<String> projects =
        reconcile.declared().stream().map(AgentRoster::project).sorted().toList();
    return HealthCheckResponse.named("agent-rosters")
        .status(!projects.isEmpty())
        .withData("projects", String.join(",", projects))
        .build();
  }
}
