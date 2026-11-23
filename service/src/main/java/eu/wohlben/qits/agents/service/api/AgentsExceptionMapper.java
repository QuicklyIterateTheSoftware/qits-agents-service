package eu.wohlben.qits.agents.service.api;

import eu.wohlben.qits.agents.service.api.AgentsWire.ErrorBody;
import eu.wohlben.qits.agents.service.control.AgentsException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/** The domain's refusals as 400, 403, 404 and 409, each with an {@link ErrorBody}. */
@Provider
public class AgentsExceptionMapper implements ExceptionMapper<AgentsException> {

  @Override
  public Response toResponse(AgentsException refusal) {
    Response.Status status =
        switch (refusal.kind()) {
          case BAD_REQUEST -> Response.Status.BAD_REQUEST;
          case FORBIDDEN -> Response.Status.FORBIDDEN;
          case NOT_FOUND -> Response.Status.NOT_FOUND;
          case CONFLICT -> Response.Status.CONFLICT;
        };
    return Response.status(status)
        .type(MediaType.APPLICATION_JSON)
        .entity(new ErrorBody(refusal.getMessage()))
        .build();
  }
}
