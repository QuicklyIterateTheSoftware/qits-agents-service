package eu.wohlben.qits.agents.runtime;

import eu.wohlben.qits.agents.service.control.Agents;
import eu.wohlben.qits.agents.service.entity.ProjectAgent;
import eu.wohlben.qits.agents.service.events.ProjectAgentChanged;
import io.quarkus.scheduler.Scheduled;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.TransactionPhase;
import jakarta.inject.Inject;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The runtime's reconcile (qits-2519) — qits-projects-service's {@code deskhost/FrontDeskSweep} for
 * the agents. Every {@code qits.agents.runtime.sweep-interval} (30 s), and at once after every
 * committed {@code ProjectAgentChanged} (observed where {@code control/Agents} publishes it):
 *
 * <ul>
 *   <li>every RUNNING agent without a lease gets one ({@link AgentLeases#apply});
 *   <li>every RUNNING agent with one has it read ({@link AgentLeases#observe}: EXITED, FAILED and
 *       ENDED re-lease) and, while it is live, rolled onto the current spec ({@link AgentSpecRoll});
 *   <li>every RETIRED agent with a lease has it ended ({@link AgentLeases#end}).
 * </ul>
 *
 * <p><b>Idempotent</b>: a pass over agents whose leases are live and current sends nothing but the
 * reads. Rows are read through {@link Agents} only. Off with {@code qits.agents.runtime.enabled=false}
 * (the suites drive {@link #sweep} directly).
 */
@ApplicationScoped
public class AgentLeaseSweep {

  private static final Logger LOG = Logger.getLogger(AgentLeaseSweep.class);

  @Inject Agents agents;

  @Inject AgentLeases leases;

  @Inject AgentSpecRoll roll;

  @ConfigProperty(name = "qits.agents.runtime.enabled")
  boolean enabled;

  /** A suite switching the triggers on without restarting the application; null reads the config. */
  private volatile Boolean enabledOverride;

  /** One sweep owed by a change, coalesced: changes arriving while it is owed add nothing. */
  private final AtomicBoolean owed = new AtomicBoolean();

  private final ExecutorService onChange =
      Executors.newSingleThreadExecutor(
          runnable -> {
            Thread thread = new Thread(runnable, "agent-lease-sweep");
            thread.setDaemon(true);
            return thread;
          });

  /** What one pass did. */
  public record Pass(int applied, int observed, int rolled, int ended) {
    public boolean quiet() {
      return applied == 0 && rolled == 0 && ended == 0;
    }
  }

  public void enabledOverride(Boolean enabled) {
    this.enabledOverride = enabled;
  }

  private boolean enabled() {
    Boolean override = enabledOverride;
    return override != null ? override : enabled;
  }

  @PreDestroy
  void close() {
    onChange.shutdownNow();
  }

  @Scheduled(
      every = "${qits.agents.runtime.sweep-interval}",
      concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
  void onInterval() {
    if (enabled()) {
      sweepQuietly();
    }
  }

  /** After the write that published it commits: the sweep runs on its own thread, never the writer's. */
  void onChanged(@Observes(during = TransactionPhase.AFTER_SUCCESS) ProjectAgentChanged changed) {
    if (!enabled() || !owed.compareAndSet(false, true)) {
      return;
    }
    try {
      onChange.execute(
          () -> {
            owed.set(false);
            sweepQuietly();
          });
    } catch (RuntimeException rejected) {
      owed.set(false);
    }
  }

  private void sweepQuietly() {
    try {
      sweep();
    } catch (RuntimeException e) {
      LOG.error("The agent lease sweep failed; the next one tries again", e);
    }
  }

  /** One pass; see the class javadoc. Never two at once. */
  public synchronized Pass sweep() {
    int applied = 0;
    int observed = 0;
    int rolled = 0;
    int ended = 0;
    for (ProjectAgent agent : agents.running()) {
      try {
        if (agent.leaseId == null) {
          if (leases.apply(agent).isPresent()) {
            applied++;
          }
          continue;
        }
        observed++;
        var live = leases.observe(agent);
        if (live.isPresent() && roll.roll(agent, live.get())) {
          rolled++;
        }
      } catch (RuntimeException e) {
        LOG.warnf(e, "Could not bring the lease of agent %s/%s in line", agent.projectSlug, agent.name);
      }
    }
    for (ProjectAgent agent : agents.retiredWithLease()) {
      try {
        if (leases.end(agent)) {
          ended++;
        }
      } catch (RuntimeException e) {
        LOG.warnf(e, "Could not end the lease of retired agent %s/%s", agent.projectSlug, agent.name);
      }
    }
    return new Pass(applied, observed, rolled, ended);
  }
}
