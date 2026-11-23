package eu.wohlben.qits.agents.service.support;

import eu.wohlben.qits.agents.service.projects.ProjectsDirectory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * qits-projects as a map; {@link #down} makes every ask {@code ProjectDirectory.Unavailable}. It
 * extends the deployable's bean because QuarkusMock replaces a bean only with an instance of its
 * own class.
 */
public class StubDirectory extends ProjectsDirectory {

  public final Map<String, UUID> projects = new LinkedHashMap<>();

  public boolean down;

  public int asked;

  public StubDirectory with(String slug, UUID id) {
    projects.put(slug, id);
    return this;
  }

  @Override
  public Map<String, UUID> projectsBySlug() {
    asked++;
    if (down) {
      throw new Unavailable("stub qits-projects is down");
    }
    return Map.copyOf(projects);
  }
}
