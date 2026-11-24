package eu.wohlben.qits.agents.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.agents.runtime.SystemLeasesApi.Lease;
import eu.wohlben.qits.agents.runtime.SystemLeasesApi.LeaseRequest;
import eu.wohlben.qits.agents.runtime.SystemLeasesApi.LeaseSpec;
import eu.wohlben.qits.agents.runtime.SystemLeasesApi.SpecRequest;
import eu.wohlben.qits.agents.service.projects.ServiceBearer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.eclipse.microprofile.rest.client.inject.RestClient;

/**
 * qits-system's lease doors as the runtime uses them (qits-2519) — {@code
 * projects/targethost/HttpSystemLeases}' shape in qits-projects-service, over the MicroProfile REST
 * client {@link SystemLeasesApi} and this service's own bearer ({@link ServiceBearer}).
 *
 * <p>An AGENT lease is {@code slotType AGENT}, {@code ownerRef <agentId>}, {@code workKey
 * agent-<agentId>}, {@code priority 0}. qits-system knows this service as the AGENT tenant by the
 * client id of its bearer ({@code qits-agents}).
 *
 * <p><b>Every failure is a {@link LeaseRefused}</b>, with qits-system's status and {@code code}
 * where it answered ({@code ErrorBody{code, message}}) and -1 where nothing did. A 404 to a read
 * or an end is no lease and answers empty. Nothing here runs inside a transaction.
 */
@ApplicationScoped
public class SystemLeasesClient {

  /** The slot type of every lease of this service's. */
  public static final String SLOT_TYPE = "AGENT";

  /** The code qits-system refuses a second live lease for one work key with. */
  public static final String LEASE_EXISTS = "LEASE_EXISTS";

  private static final ObjectMapper JSON = new ObjectMapper();

  @Inject @RestClient SystemLeasesApi api;

  @Inject ServiceBearer bearer;

  /**
   * The client over a given REST client and bearer, outside CDI: the consumer pact drives the real
   * class through a client built against its mock server.
   */
  public static SystemLeasesClient over(SystemLeasesApi api, ServiceBearer bearer) {
    SystemLeasesClient client = new SystemLeasesClient();
    client.api = api;
    client.bearer = bearer;
    return client;
  }

  /** {@code agent-<agentId>}: the work key, and so the name of the agent's kept work volume. */
  public static String workKey(UUID agentId) {
    return "agent-" + agentId;
  }

  /** qits-system refused or did not answer a lease call. */
  public static final class LeaseRefused extends RuntimeException {
    private final int status;
    private final String code;

    public LeaseRefused(int status, String code, String message, Throwable cause) {
      super(message, cause);
      this.status = status;
      this.code = code;
    }

    /** The HTTP status, or -1 when nothing answered. */
    public int status() {
      return status;
    }

    /** The refusal's code ({@code LEASE_EXISTS}, {@code IMAGE_NOT_ALLOWED}, …), or null. */
    public String code() {
      return code;
    }
  }

  /** {@code POST /system/api/leases}: the agent's lease, QUEUED (or further). */
  public Lease create(UUID agentId, LeaseSpec spec) {
    LeaseRequest request =
        new LeaseRequest(SLOT_TYPE, agentId.toString(), workKey(agentId), 0, spec);
    return call(
        () -> api.createLease(authorization(), request),
        "ask for an AGENT lease for agent " + agentId);
  }

  /** {@code GET /system/api/leases/{id}}; empty when there is no such lease. */
  public Optional<Lease> get(UUID leaseId) {
    return absentOn404(() -> api.getLease(authorization(), leaseId), "read lease " + leaseId);
  }

  /** {@code GET /system/api/leases}: this tenant's own leases. */
  public List<Lease> list() {
    List<Lease> leases = call(() -> api.listLeases(authorization()), "list this service's leases");
    return leases == null ? List.of() : leases;
  }

  /** {@code PUT /system/api/leases/{id}/spec}: the lease with its new spec — a roll. */
  public Lease replaceSpec(UUID leaseId, LeaseSpec spec) {
    return call(
        () -> api.replaceLeaseSpec(authorization(), leaseId, new SpecRequest(spec)),
        "roll lease " + leaseId);
  }

  /**
   * {@code DELETE /system/api/leases/{id}}: ENDING or ENDED; empty when there is no such lease. The
   * work volume is never asked about: the AGENT policy keeps it for the next lease.
   */
  public Optional<Lease> end(UUID leaseId) {
    return absentOn404(() -> api.endLease(authorization(), leaseId), "end lease " + leaseId);
  }

  // --- internals ---------------------------------------------------------------------------------

  private String authorization() {
    try {
      return bearer.authorization().orElse(null);
    } catch (RuntimeException noToken) {
      throw new LeaseRefused(
          -1, null, "no qits bearer to ask qits-system with: " + noToken.getMessage(), noToken);
    }
  }

  private Optional<Lease> absentOn404(Call<Lease> call, String what) {
    try {
      return Optional.ofNullable(call(call, what));
    } catch (LeaseRefused refused) {
      if (refused.status() == 404) {
        return Optional.empty();
      }
      throw refused;
    }
  }

  private <T> T call(Call<T> call, String what) {
    try {
      return call.run();
    } catch (LeaseRefused refused) {
      throw refused;
    } catch (WebApplicationException answered) {
      int status = answered.getResponse().getStatus();
      String code = null;
      String message = null;
      try {
        String body = answered.getResponse().readEntity(String.class);
        JsonNode error = body == null || body.isBlank() ? null : JSON.readTree(body);
        code = text(error, "code");
        message = text(error, "message");
      } catch (RuntimeException | java.io.IOException unreadable) {
        // The status says enough.
      }
      throw new LeaseRefused(
          status,
          code,
          "qits-system answered " + status + " when asked to " + what
              + (message == null ? "" : ": " + message),
          null);
    } catch (ProcessingException unreachable) {
      throw new LeaseRefused(
          -1, null, "qits-system could not be asked to " + what + ": " + unreachable.getMessage(),
          unreachable);
    }
  }

  private static String text(JsonNode node, String field) {
    return node != null && node.path(field).isTextual() ? node.path(field).asText() : null;
  }

  @FunctionalInterface
  private interface Call<T> {
    T run();
  }
}
