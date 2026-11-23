package eu.wohlben.qits.agents.service.control;

import java.util.Map;
import java.util.UUID;

/**
 * Where the roster reconcile learns a project's id from its slug: qits-projects' project list
 * ({@code GET /projects/api/projects}), asked with this service's own {@code qits} bearer. An
 * interface so the reconcile can be driven in a test without a qits-projects; the deployable's
 * implementation is {@code projects.ProjectsDirectory}.
 */
public interface ProjectDirectory {

  /**
   * Every project qits-projects knows, slug to id.
   *
   * @throws Unavailable when qits-projects cannot be asked or answers anything but the list
   */
  Map<String, UUID> projectsBySlug();

  /** qits-projects could not be asked: nothing was decided, so the reconcile tries again later. */
  final class Unavailable extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public Unavailable(String message) {
      super(message);
    }

    public Unavailable(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
