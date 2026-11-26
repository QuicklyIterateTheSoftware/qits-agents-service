package eu.wohlben.qits.agents.service.idp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * qits-idp's commissioned-token doors this service calls ({@code IdpTokensController}):
 * {@code commissionToken}, {@code POST /idp/api/tokens}, {@code replaceTokenGitRefs}, {@code PUT
 * /idp/api/tokens/{tokenId}/git-refs} (qits-2705), and {@code deleteToken}, {@code DELETE
 * /idp/api/tokens/{tokenId}}, each with HTTP Basic as this service's own client.
 *
 * <p>The address is {@code qits.agents.idp.url}, which ends in {@code /idp}, so the paths here start
 * at {@code /api}. The pact is {@code pacts/qits-agents-service_qits-idp-service.json} ({@code
 * IdpPactTest}).
 */
@Path("/api/tokens")
@RegisterRestClient(configKey = "qits-idp")
public interface IdpTokensApi {

  @POST
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  Commissioned commissionToken(
      @HeaderParam(HttpHeaders.AUTHORIZATION) String authorization, Commission commission);

  /** Replaces the token's list; the 200's {@code TokenView} is not read. */
  @PUT
  @Path("/{tokenId}/git-refs")
  @Consumes(MediaType.APPLICATION_JSON)
  void replaceTokenGitRefs(
      @HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
      @PathParam("tokenId") String tokenId,
      GitRefsReplacement replacement);

  @DELETE
  @Path("/{tokenId}")
  void deleteToken(
      @HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
      @PathParam("tokenId") String tokenId);

  /** The body: the kind, the context, the claims and the refs the token may push. */
  record Commission(
      String contextKind, String contextId, Map<String, String> claims, List<String> gitRefs) {}

  /** The replace's body: the whole new list ({@code []} pushes nothing). */
  record GitRefsReplacement(List<String> gitRefs) {}

  /**
   * The 201 answer, narrowed to what the reconcile stores. {@code token} is the value, answered
   * once; {@link #toString} leaves it out.
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record Commissioned(String tokenId, String token, String subject) {
    @Override
    public String toString() {
      return "Commissioned[tokenId=" + tokenId + ", subject=" + subject + "]";
    }
  }
}
