package eu.wohlben.qits.agents.service.reconcile;

import eu.wohlben.qits.agents.service.control.RosterReconcile;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Re-runs the roster reconcile every {@code qits.agents.roster-reconcile-interval}. The boot run is
 * the one that matters — a roster changes only with a release of this service — and this one heals
 * a boot that could not reach qits-projects or qits-idp. The reconcile is idempotent, so a run over
 * an unchanged roster writes nothing. Off with the boot run ({@code
 * qits.agents.roster-reconcile-at-boot=false}).
 */
@ApplicationScoped
public class RosterReconcileTrigger {

  @Inject RosterReconcile reconcile;

  @ConfigProperty(name = "qits.agents.roster-reconcile-at-boot")
  boolean enabled;

  @Scheduled(
      every = "${qits.agents.roster-reconcile-interval}",
      delayed = "${qits.agents.roster-reconcile-interval}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void rerun() {
    if (enabled) {
      reconcile.reconcile();
    }
  }
}
