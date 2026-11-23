package eu.wohlben.qits.agents.service.api;

import eu.wohlben.qits.agents.service.api.AgentsWire.AgentDto;
import eu.wohlben.qits.agents.service.api.AgentsWire.AgentSummaryDto;
import eu.wohlben.qits.agents.service.api.AgentsWire.ClaimDto;
import eu.wohlben.qits.agents.service.api.AgentsWire.LaunchDto;
import eu.wohlben.qits.agents.service.api.AgentsWire.ReportRequest;
import eu.wohlben.qits.agents.service.api.AgentsWire.TurnDto;
import eu.wohlben.qits.agents.service.control.Agents;
import eu.wohlben.qits.agents.service.control.AgentsException;
import eu.wohlben.qits.agents.service.entity.DesiredState;
import eu.wohlben.qits.agents.service.entity.ProjectAgent;
import eu.wohlben.qits.agents.service.entity.ReportedState;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.jboss.logging.Logger;

/**
 * A project's agents, {@code /projects/{project}/agents}, where {@code {project}} is the project's
 * slug or id and {@code {name}} the agent's name.
 *
 * <p>The three reads (the list, one agent, its claims) carry the READ set at class level. The four
 * runner doors (turns, delivered, launch, report) carry their own set, which REPLACES it: the
 * operators and {@code qits:agent}, the latter held to <b>self</b> ({@link SelfOrOperator}).
 */
@Path("/projects/{project}/agents")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({
  Roles.ADMIN,
  Roles.ADMIN_AGENT,
  Roles.DESK_ADMIN,
  Roles.DESK,
  Roles.AGENT,
  Roles.SYSTEM
})
public class AgentsResource {

  private static final Logger LOG = Logger.getLogger(AgentsResource.class);

  @Inject Agents agents;

  @Inject SecurityIdentity identity;

  @GET
  @Operation(operationId = "listAgents", summary = "The project's agents, by name")
  @APIResponse(responseCode = "200", description = "The agents; empty for a project with none")
  public List<AgentSummaryDto> list(@PathParam("project") String project) {
    return agents.ofProject(project).stream()
        .map(agent -> AgentSummaryDto.of(agent, agents.openClaims(agent.id)))
        .toList();
  }

  @GET
  @Path("/{name}")
  @Operation(operationId = "getAgent", summary = "One agent, with its declaration's texts")
  @APIResponse(responseCode = "200", description = "The agent")
  @APIResponse(responseCode = "404", description = "The project declares no such agent")
  public AgentDto get(@PathParam("project") String project, @PathParam("name") String name) {
    ProjectAgent agent = agent(project, name);
    return AgentDto.of(agent, agents.openClaims(agent.id));
  }

  @GET
  @Path("/{name}/claims")
  @Operation(operationId = "listAgentClaims", summary = "The items the agent holds, newest first")
  @APIResponse(responseCode = "200", description = "The claims")
  @APIResponse(responseCode = "400", description = "state is neither open nor all")
  @APIResponse(responseCode = "404", description = "The project declares no such agent")
  public List<ClaimDto> claims(
      @PathParam("project") String project,
      @PathParam("name") String name,
      @QueryParam("state") @DefaultValue("open") String state) {
    boolean all =
        switch (state.toLowerCase(Locale.ROOT)) {
          case "open" -> false;
          case "all" -> true;
          default -> throw AgentsException.badRequest("state is open or all, not " + state);
        };
    ProjectAgent agent = agent(project, name);
    return agents.claims(agent.id, all).stream().map(ClaimDto::of).toList();
  }

  // --- the runner doors: operators, or the agent itself -----------------------------------------

  @GET
  @Path("/{name}/turns")
  @RolesAllowed({Roles.ADMIN, Roles.DESK_ADMIN, Roles.SYSTEM, Roles.AGENT})
  @Operation(operationId = "listAgentTurns", summary = "The agent's inbox, oldest first")
  @APIResponse(responseCode = "200", description = "The turns; only the undelivered by default")
  @APIResponse(responseCode = "403", description = "Another agent's credential")
  @APIResponse(responseCode = "404", description = "The project declares no such agent")
  public List<TurnDto> turns(
      @PathParam("project") String project,
      @PathParam("name") String name,
      @QueryParam("pending") @DefaultValue("true") boolean pending) {
    ProjectAgent agent = self(project, name);
    return agents.turns(agent.id, pending).stream().map(TurnDto::of).toList();
  }

  @POST
  @Path("/{name}/turns/{turnId}/delivered")
  @RolesAllowed({Roles.ADMIN, Roles.DESK_ADMIN, Roles.SYSTEM, Roles.AGENT})
  @Operation(operationId = "markAgentTurnDelivered", summary = "Acknowledge a turn; idempotent")
  @APIResponse(responseCode = "204", description = "The turn is delivered")
  @APIResponse(responseCode = "403", description = "Another agent's credential")
  @APIResponse(responseCode = "404", description = "No such agent, or a turn of another agent")
  public void delivered(
      @PathParam("project") String project,
      @PathParam("name") String name,
      @PathParam("turnId") UUID turnId) {
    ProjectAgent agent = self(project, name);
    agents.delivered(agent.id, turnId);
  }

  @GET
  @Path("/{name}/launch")
  @RolesAllowed({Roles.ADMIN, Roles.DESK_ADMIN, Roles.SYSTEM, Roles.AGENT})
  @Operation(
      operationId = "getAgentLaunch",
      summary = "What the runtime starts the agent with, its credential included")
  @APIResponse(responseCode = "200", description = "The launch")
  @APIResponse(responseCode = "403", description = "Another agent's credential")
  @APIResponse(responseCode = "404", description = "The project declares no such agent")
  @APIResponse(
      responseCode = "409",
      description = "The agent is RETIRED, or holds no credential yet")
  public LaunchDto launch(@PathParam("project") String project, @PathParam("name") String name) {
    ProjectAgent agent = self(project, name);
    if (agent.desiredState == DesiredState.RETIRED) {
      throw AgentsException.conflict("agent " + name + " is RETIRED");
    }
    if (agent.tokenValue == null) {
      throw AgentsException.conflict(
          "agent " + name + " holds no credential yet; the roster reconcile commissions one");
    }
    LOG.infof(
        "launch of %s/%s generation %d read by %s",
        agent.projectSlug, agent.name, agent.generation, Callers.subject(identity));
    return LaunchDto.of(agent);
  }

  @PUT
  @Path("/{name}/report")
  @Consumes(MediaType.APPLICATION_JSON)
  @RolesAllowed({Roles.ADMIN, Roles.DESK_ADMIN, Roles.SYSTEM, Roles.AGENT})
  @Operation(operationId = "reportAgent", summary = "What the runtime says the agent is doing")
  @APIResponse(responseCode = "204", description = "Recorded")
  @APIResponse(responseCode = "400", description = "No generation, or a state that is not reportable")
  @APIResponse(responseCode = "403", description = "Another agent's credential")
  @APIResponse(responseCode = "404", description = "The project declares no such agent")
  @APIResponse(responseCode = "409", description = "A generation newer than the agent's")
  public void report(
      @PathParam("project") String project,
      @PathParam("name") String name,
      ReportRequest report) {
    ProjectAgent agent = self(project, name);
    if (report == null || report.generation() == null) {
      throw AgentsException.badRequest("a JSON body with generation and state is required");
    }
    ReportedState state = reportable(report.state());
    agents.report(agent.id, report.generation(), state, report.sessionId(), report.detail());
    LOG.infof(
        "agent %s/%s reported %s at generation %d by %s",
        agent.projectSlug, agent.name, state, report.generation(), Callers.subject(identity));
  }

  // --- helpers ---------------------------------------------------------------------------------

  private ProjectAgent agent(String project, String name) {
    return agents
        .find(project, name)
        .orElseThrow(
            () -> AgentsException.notFound("project " + project + " declares no agent " + name));
  }

  /** The agent, when the caller is an operator or the agent itself. */
  private ProjectAgent self(String project, String name) {
    ProjectAgent agent = agent(project, name);
    SelfOrOperator.check(identity, agent);
    return agent;
  }

  private static ReportedState reportable(String state) {
    if (state != null) {
      try {
        ReportedState parsed = ReportedState.valueOf(state.toUpperCase(Locale.ROOT));
        if (parsed.reportable()) {
          return parsed;
        }
      } catch (IllegalArgumentException unknown) {
        // answered below
      }
    }
    throw AgentsException.badRequest(
        "state is STARTING, IDLE, BUSY, EXITED or FAILED, not " + state);
  }
}
