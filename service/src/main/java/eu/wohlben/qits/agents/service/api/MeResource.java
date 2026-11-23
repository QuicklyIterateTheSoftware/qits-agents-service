package eu.wohlben.qits.agents.service.api;

import eu.wohlben.qits.agents.service.api.AgentsWire.MeDto;
import eu.wohlben.qits.agents.service.control.Agents;
import eu.wohlben.qits.agents.service.control.AgentsException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;

/**
 * {@code GET /me}: which agent the caller's credential is — the row whose {@code token_subject} is
 * the caller's {@code sub}. qits-2520's {@code --assigned-to-me} reads it to learn its own name. Any
 * caller that is no agent's credential is 404.
 */
@Path("/me")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({Roles.AGENT, Roles.ADMIN_AGENT, Roles.DESK_ADMIN, Roles.ADMIN, Roles.SYSTEM})
public class MeResource {

  @Inject Agents agents;

  @Inject SecurityIdentity identity;

  @GET
  @Operation(operationId = "getMe", summary = "The agent the caller's credential is")
  @APIResponse(responseCode = "200", description = "The caller's agent")
  @APIResponse(responseCode = "404", description = "The caller is no agent's credential")
  public MeDto me() {
    return agents
        .bySubject(Callers.subject(identity))
        .map(MeDto::of)
        .orElseThrow(() -> AgentsException.notFound("the caller is no agent's credential"));
  }
}
