package eu.wohlben.qits.agents.service.projects;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * qits-projects' one door this service calls: {@code listProjects}, {@code GET
 * /projects/api/projects}, the list {@code qits projects list} reads, answered {@code {entries:
 * [{project: {id, slug, …}}]}}. The caller holds {@code qits:system}: the bearer is this service's
 * own {@code qits} oidc client's ({@link ServiceBearer}).
 *
 * <p>The address is {@code qits.agents.projects.url} (scheme, host and port, no path); qits-projects
 * serves its own {@code /projects/api} segment. The pact is {@code
 * pacts/qits-agents-service_qits-projects-service.json} ({@code ProjectsPactTest}).
 */
@Path("/projects/api")
@RegisterRestClient(configKey = "qits-projects")
public interface ProjectsApi {

  @GET
  @Path("/projects")
  @Produces(MediaType.APPLICATION_JSON)
  ProjectList listProjects(@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization);

  /** The answer, narrowed to what the reconcile reads; anything else qits-projects adds is ignored. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record ProjectList(List<Entry> entries) {}

  /** One entry of the list. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record Entry(Project project) {}

  /** A project, narrowed to its id and slug. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record Project(String id, String slug) {}
}
