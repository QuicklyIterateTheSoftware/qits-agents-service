package eu.wohlben.qits.agents.service.projects;

import eu.wohlben.qits.agents.service.control.ProjectDirectory;
import eu.wohlben.qits.agents.service.projects.ProjectsApi.Entry;
import eu.wohlben.qits.agents.service.projects.ProjectsApi.ProjectList;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * The roster reconcile's {@link ProjectDirectory}: qits-projects' project list, asked with this
 * service's own bearer. Any answer but the list — 401, 403, a 5xx, no connection, no bearer — is
 * {@link ProjectDirectory.Unavailable}: nothing was decided, and the reconcile tries again later.
 */
@ApplicationScoped
public class ProjectsDirectory implements ProjectDirectory {

  @Inject @RestClient ProjectsApi projects;

  @Inject ServiceBearer bearer;

  /**
   * The directory over a given client and bearer, outside CDI: the consumer pact drives the real
   * class through a client built against its mock server.
   */
  public static ProjectsDirectory over(ProjectsApi projects, ServiceBearer bearer) {
    ProjectsDirectory directory = new ProjectsDirectory();
    directory.projects = projects;
    directory.bearer = bearer;
    return directory;
  }

  @Override
  public Map<String, UUID> projectsBySlug() {
    String authorization;
    try {
      authorization = bearer.authorization().orElse(null);
    } catch (RuntimeException noToken) {
      throw new Unavailable("no qits bearer to ask qits-projects with: " + noToken.getMessage());
    }
    ProjectList list;
    try {
      list = projects.listProjects(authorization);
    } catch (WebApplicationException refused) {
      throw new Unavailable(
          "qits-projects answered " + refused.getResponse().getStatus() + " to the project list");
    } catch (ProcessingException unreachable) {
      throw new Unavailable("qits-projects cannot be reached: " + unreachable.getMessage());
    }
    Map<String, UUID> bySlug = new LinkedHashMap<>();
    if (list == null || list.entries() == null) {
      throw new Unavailable("qits-projects answered no project list");
    }
    for (Entry entry : list.entries()) {
      if (entry == null || entry.project() == null) {
        continue;
      }
      try {
        bySlug.put(entry.project().slug(), UUID.fromString(entry.project().id()));
      } catch (IllegalArgumentException | NullPointerException unusable) {
        // A project without a usable id cannot own an agent row; it is simply not in the map.
      }
    }
    return bySlug;
  }
}
