package eu.wohlben.qits.agents.service.api;

import eu.wohlben.qits.agents.service.api.EvaluationWire.EvaluationDto;
import eu.wohlben.qits.agents.service.control.AgentsException;
import eu.wohlben.qits.agents.service.control.Evaluation;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * {@code GET /projects/{project}/evaluation} (epic qits-2521): the two delivery models, WORKSPACE
 * and PROJECT_AGENT, compared over a window, from the evaluation ledger ({@code
 * control.Evaluation}). {@code {project}} is the project's slug or id. The READ set of {@link
 * AgentsResource}'s reads, at class level.
 *
 * <p>JSON by default; {@code Accept: text/markdown} answers the same report as a document ({@link
 * EvaluationMarkdown}).
 */
@Path("/projects/{project}/evaluation")
@RolesAllowed({
  Roles.ADMIN,
  Roles.ADMIN_AGENT,
  Roles.DESK_ADMIN,
  Roles.DESK,
  Roles.AGENT,
  Roles.SYSTEM
})
public class EvaluationResource {

  @Inject Evaluation evaluation;

  @Context HttpHeaders headers;

  @GET
  @Produces({MediaType.APPLICATION_JSON, EvaluationMarkdown.MEDIA_TYPE})
  @Operation(
      operationId = "getEvaluation",
      summary = "The two delivery models compared over a window, as JSON or Markdown")
  @APIResponse(
      responseCode = "200",
      description = "The report",
      content = {
        @Content(
            mediaType = MediaType.APPLICATION_JSON,
            schema = @Schema(implementation = EvaluationDto.class)),
        @Content(
            mediaType = EvaluationMarkdown.MEDIA_TYPE,
            schema = @Schema(type = SchemaType.STRING))
      })
  @APIResponse(
      responseCode = "400",
      description = "since or until is no ISO-8601 instant, or until is not after since")
  @APIResponse(responseCode = "404", description = "No such project")
  public Response get(
      @PathParam("project") String project,
      @QueryParam("since") String since,
      @QueryParam("until") String until) {
    Instant from = since == null || since.isBlank() ? evaluation.since() : instant("since", since);
    Instant to = until == null || until.isBlank() ? Instant.now() : instant("until", until);
    if (!to.isAfter(from)) {
      throw AgentsException.badRequest("until " + to + " is not after since " + from);
    }
    Evaluation.Project found =
        evaluation
            .project(project)
            .orElseThrow(() -> AgentsException.notFound("no project " + project));
    EvaluationDto report = EvaluationDto.of(evaluation.report(found, from, to));
    if (markdown()) {
      return Response.ok(EvaluationMarkdown.render(report), EvaluationMarkdown.MEDIA_TYPE).build();
    }
    return Response.ok(report, MediaType.APPLICATION_JSON).build();
  }

  /** Whether the caller prefers Markdown: the first acceptable type that is either decides. */
  private boolean markdown() {
    MediaType markdown = MediaType.valueOf(EvaluationMarkdown.MEDIA_TYPE);
    for (MediaType accepted : headers.getAcceptableMediaTypes()) {
      if (accepted.isWildcardType()) {
        return false;
      }
      if (accepted.isCompatible(markdown) && !accepted.isWildcardSubtype()) {
        return true;
      }
      if (accepted.isCompatible(MediaType.APPLICATION_JSON_TYPE)) {
        return false;
      }
    }
    return false;
  }

  private static Instant instant(String name, String value) {
    try {
      return Instant.parse(value);
    } catch (DateTimeParseException unreadable) {
      throw AgentsException.badRequest(name + " is an ISO-8601 instant, not " + value);
    }
  }
}
