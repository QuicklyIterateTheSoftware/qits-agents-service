package eu.wohlben.qits.agents.service.api;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.agents.runtime.SystemLeasesApi;
import eu.wohlben.qits.agents.service.events.AgentActivityChanged;
import eu.wohlben.qits.agents.service.events.AgentHarnessExited;
import eu.wohlben.qits.agents.service.events.AgentPlaced;
import eu.wohlben.qits.agents.service.events.AgentUsageRecorded;
import eu.wohlben.qits.agents.service.events.AgentWaitClosed;
import eu.wohlben.qits.agents.service.events.BuildStatusChanged;
import eu.wohlben.qits.agents.service.events.EntityAssigned;
import eu.wohlben.qits.agents.service.events.EntityBlockChanged;
import eu.wohlben.qits.agents.service.events.EntityTransitioned;
import eu.wohlben.qits.agents.service.events.ReleaseRequestStateChanged;
import eu.wohlben.qits.agents.service.events.EntityCommented;
import eu.wohlben.qits.agents.service.events.ProjectAgentChanged;
import eu.wohlben.qits.agents.service.events.ProjectAgentWaitClosed;
import eu.wohlben.qits.agents.service.idp.IdpTokensApi;
import eu.wohlben.qits.agents.service.projects.ProjectsApi;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * {@link ApiWireReflection} is complete: every nested type of {@link AgentsWire}, the events, the
 * consumed events, and the outbound clients' records. On a JVM everything binds regardless; the failure this guards is a
 * 500 in the native binary, so the only way to catch it early is the commit that forgets the list.
 */
class ApiWireReflectionTest {

  @Test
  void everyWireTypeIsRegistered() {
    RegisterForReflection registration =
        ApiWireReflection.class.getAnnotation(RegisterForReflection.class);
    assertNotNull(registration, "the annotation IS the class");
    Set<Class<?>> registered = Set.of(registration.targets());
    Class<?>[] nested = AgentsWire.class.getDeclaredClasses();
    assertTrue(nested.length >= 8, "the sweep found only " + nested.length + " wire types");
    for (Class<?> type : nested) {
      assertTrue(
          registered.contains(type),
          type.getSimpleName() + " is on the wire and not in ApiWireReflection");
    }
    for (Class<?> type : EvaluationWire.class.getDeclaredClasses()) {
      assertTrue(
          registered.contains(type),
          type.getSimpleName() + " is on the wire and not in ApiWireReflection");
    }
    for (Class<?> consumed :
        List.of(
            EntityTransitioned.class,
            EntityTransitioned.Entity.class,
            EntityBlockChanged.class,
            ReleaseRequestStateChanged.class,
            BuildStatusChanged.class,
            AgentPlaced.class,
            AgentActivityChanged.class,
            AgentHarnessExited.class,
            AgentWaitClosed.class,
            AgentUsageRecorded.class)) {
      assertTrue(
          registered.contains(consumed),
          "the consumed " + consumed.getSimpleName() + " is registered");
    }
    assertTrue(registered.contains(ProjectAgentChanged.class), "the event is registered");
    assertTrue(registered.contains(EntityAssigned.class), "the consumed EntityAssigned is registered");
    assertTrue(
        registered.contains(EntityCommented.class), "the consumed EntityCommented is registered");
    assertTrue(
        registered.contains(ProjectAgentWaitClosed.class),
        "the consumed ProjectAgentWaitClosed is registered");
    for (Class<?> owner : List.of(ProjectsApi.class, IdpTokensApi.class, SystemLeasesApi.class)) {
      for (Class<?> type : owner.getDeclaredClasses()) {
        if (type.isRecord()) {
          assertTrue(
              registered.contains(type),
              owner.getSimpleName()
                  + "."
                  + type.getSimpleName()
                  + " is bound by reflection and not in ApiWireReflection");
        }
      }
    }
  }
}
