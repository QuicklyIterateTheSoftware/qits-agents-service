package eu.wohlben.qits.agents.runtime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

/**
 * qits-system's lease doors, as this service — the {@code AGENT} tenant, by its {@code qits} client
 * id {@code qits-agents} — asks them (qits-2519). Each method is named after the operation it calls
 * and is a row of {@code pacts/qits-agents-service_qits-system-service.json} ({@code
 * contracts/consumer/SystemPactTest}). The bearer is this service's own ({@code
 * projects/ServiceBearer}: {@code qits:system}, audience {@code qits-platform}).
 *
 * <p>The address is {@code qits.agents.system.url} (scheme, host and port, no path); qits-system
 * serves its own {@code /system/api} segment. {@link SystemLeasesClient} is the one caller.
 */
@Path("/system/api")
@RegisterRestClient(configKey = "qits-system")
public interface SystemLeasesApi {

  @POST
  @Path("/leases")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  Lease createLease(@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization, LeaseRequest request);

  @GET
  @Path("/leases/{leaseId}")
  @Produces(MediaType.APPLICATION_JSON)
  Lease getLease(
      @HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
      @PathParam("leaseId") UUID leaseId);

  /** This tenant's own leases: read only to adopt a lease a crash left unrecorded. */
  @GET
  @Path("/leases")
  @Produces(MediaType.APPLICATION_JSON)
  List<Lease> listLeases(@HeaderParam(HttpHeaders.AUTHORIZATION) String authorization);

  @PUT
  @Path("/leases/{leaseId}/spec")
  @Consumes(MediaType.APPLICATION_JSON)
  @Produces(MediaType.APPLICATION_JSON)
  Lease replaceLeaseSpec(
      @HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
      @PathParam("leaseId") UUID leaseId,
      SpecRequest request);

  @DELETE
  @Path("/leases/{leaseId}")
  @Produces(MediaType.APPLICATION_JSON)
  Lease endLease(
      @HeaderParam(HttpHeaders.AUTHORIZATION) String authorization,
      @PathParam("leaseId") UUID leaseId);

  /** {@code POST /leases}'s body. No {@code projectId}: the owner ref names the agent. */
  record LeaseRequest(
      String slotType, String ownerRef, String workKey, int priority, LeaseSpec spec) {}

  /** {@code PUT /leases/{id}/spec}'s body. */
  record SpecRequest(LeaseSpec spec) {}

  /**
   * {@code LeaseSpec{image, env, user, limits}}. The environment carries the agent's token, so the
   * record never prints it.
   */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record LeaseSpec(String image, Map<String, String> env, String user, Limits limits) {
    @Override
    public String toString() {
      return "LeaseSpec[image=" + image + ", env=" + (env == null ? null : env.keySet())
          + ", user=" + user + ", limits=" + limits + "]";
    }
  }

  /** {@code LeaseSpec.limits}; an unset limit is left out. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  record Limits(String memory, String memorySwap, Long pidsLimit, String cpus, Integer oomScoreAdj) {}

  /**
   * One lease as qits-system answers it, narrowed to what the runtime reads. {@code specHash} is
   * qits-system's sha256 of the canonical spec it holds — the same {@link AgentSpecs#hash} computes,
   * which is how a roll is decided without a stored copy of the spec (env values are redacted on
   * every answer).
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  record Lease(
      UUID id,
      String slotType,
      String ownerRef,
      String workKey,
      String state,
      String specHash,
      Integer exitCode,
      String detail) {

    public LeaseState leaseState() {
      return LeaseState.parse(state);
    }
  }

  /** A lease's life, as qits-system spells it; {@link #UNKNOWN} for a word this build does not know. */
  enum LeaseState {
    QUEUED,
    GRANTED,
    RUNNING,
    EXITED,
    FAILED,
    ENDING,
    ENDED,
    UNKNOWN;

    public static LeaseState parse(String value) {
      if (value == null) {
        return UNKNOWN;
      }
      try {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException unknown) {
        return UNKNOWN;
      }
    }

    /** Every state but ENDED holds the work key: qits-system refuses a second lease for it. */
    public boolean live() {
      return this != ENDED;
    }
  }
}
