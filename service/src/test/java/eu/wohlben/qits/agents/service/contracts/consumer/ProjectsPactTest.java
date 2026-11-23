package eu.wohlben.qits.agents.service.contracts.consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import eu.wohlben.qits.agents.service.projects.ProjectsApi;
import eu.wohlben.qits.agents.service.projects.ProjectsDirectory;
import eu.wohlben.qits.agents.service.projects.ServiceBearer;
import eu.wohlben.qits.pact.consumer.ConsumerPact;
import eu.wohlben.qits.pact.consumer.GoldenInteraction;
import eu.wohlben.qits.pact.consumer.GoldenMasters;
import eu.wohlben.qits.pact.consumer.Trigger;
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import io.quarkus.test.junit.QuarkusTest;
import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * <b>What this service asks qits-projects-service, and why</b>: the roster reconcile's project list,
 * {@code listProjects} — {@code GET /projects/api/projects}, the door {@code qits projects list}
 * reads — answered {@code {entries: [{project: {id, slug, …}}]}} in qits-projects' state {@value
 * #STATE}. The reconcile reads only each project's {@code id} and {@code slug}. Driven through the
 * real {@link ProjectsDirectory} over the real generated {@link ProjectsApi} client, against a pact
 * mock server.
 *
 * <p>The pact is {@code pacts/qits-agents-service_qits-projects-service.json}; {@code
 * -Dgolden.update=true} rewrites it. {@code @QuarkusTest} because the reactive REST client is built
 * only inside a running application.
 */
@QuarkusTest
class ProjectsPactTest {

  static final GoldenMasters PROJECTS = GoldenMasters.of("qits-projects-service", "qits-projects");

  static final String STATE = "a project exists";

  /** The list, read by the roster reconcile to name each roster's project. */
  static final GoldenInteraction LIST =
      GoldenInteraction.of(
              Trigger.event("StartupEvent").from("RosterReconcile"), STATE, "listProjects")
          .consumes("entries[].project.id", "entries[].project.slug");

  static final ConsumerPact PACT = ConsumerPact.of(IdpPactTest.CONSUMER, PROJECTS, LIST);

  @Test
  void theReconcileReadsEachProjectsIdBySlug() {
    PACT.run(
        LIST,
        (url, recorded) -> {
          ProjectsApi client =
              QuarkusRestClientBuilder.newBuilder().baseUri(URI.create(url)).build(ProjectsApi.class);
          Map<String, UUID> bySlug =
              ProjectsDirectory.over(client, noBearer()).projectsBySlug();
          var entry = PROJECTS.json(STATE, LIST.operationId()).path("entries").path(0).path("project");
          assertEquals(
              Map.of(entry.path("slug").asText(), UUID.fromString(entry.path("id").asText())),
              bySlug);
        });
  }

  @Test
  void theCommittedPactIsWhatTheRowsWrite() {
    PACT.compareOrWritePactFile();
  }

  @Test
  void everyInteractionCarriesBothReferences() {
    PACT.assertEveryInteractionCarriesBothReferences();
    assertEquals(1, PACT.recorded().size(), "the row is recorded: " + PACT.unrecorded());
  }

  /** The state records no Authorization: the bearer is the edge's business, not the pact's. */
  private static ServiceBearer noBearer() {
    return new ServiceBearer() {
      @Override
      public Optional<String> authorization() {
        return Optional.empty();
      }
    };
  }
}
