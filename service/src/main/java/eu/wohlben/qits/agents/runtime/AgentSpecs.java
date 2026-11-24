package eu.wohlben.qits.agents.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.wohlben.qits.agents.runtime.SystemLeasesApi.LeaseSpec;
import eu.wohlben.qits.agents.runtime.SystemLeasesApi.Limits;
import eu.wohlben.qits.agents.service.entity.ProjectAgent;
import eu.wohlben.qits.agentsdaemon.protocol.AgentImage;
import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * What an agent's container is (qits-2519): {@link #compose} turns an agent row into the {@code
 * LeaseSpec{image, env, user, limits}} of its AGENT lease — qits-projects-service's {@code
 * deskhost/FrontDeskSpecs}, for one agent rather than a project's desk.
 *
 * <ul>
 *   <li><b>The image</b> is {@code registry.qits.<domain>/qits/agent:<AgentImage.VERSION>}: the pinned
 *       {@code qits-agents-daemon-protocol} jar decides the tag, so a pin bump is a roll.
 *   <li><b>The environment</b> is exactly {@link #ENV}, the table of qits-agents-daemon's README.
 *       {@code QITS_TOKEN} is the row's own commissioned token, so a rotated token is a roll too.
 *       Never {@link #POLICY_OWNED_ENV}: the AGENT slot policy owns those and qits-system refuses a
 *       spec naming one. Mounts, network and init are the policy's as well.
 *   <li><b>The user and the limits</b> are the front desk's: uid {@code 1000}, a hard {@code 4g}
 *       memory cap (swap the same), {@code oomScoreAdj 800}, no pids or cpu cap unless configured.
 * </ul>
 *
 * <p><b>Edge-only.</b> The container runs on a target's node and reaches the platform only at
 * {@code <app>.qits.<domain>}; a domain that is blank, has no dot, or is {@code localhost} refuses
 * to compose.
 *
 * <p>{@link #hash} is qits-system's own {@code specHash}: sha256 of the spec's canonical JSON
 * (object keys sorted, recursively, compact). Comparing it with the hash a lease answers is how
 * {@link AgentSpecRoll} sees a changed spec, though every answer redacts the env's values.
 */
@ApplicationScoped
public class AgentSpecs {

  /** The environment names every agent container gets, and no others. */
  public static final Set<String> ENV =
      Set.of(
          "QITS_AGENTS_DAEMON_PROJECT",
          "QITS_AGENTS_DAEMON_AGENT_NAME",
          "QITS_AGENTS_DAEMON_WRAPPER",
          "QITS_AGENTS_DAEMON_GIT_BASE",
          "QITS_AGENTS_DAEMON_SERVICE_URL",
          "QITS_TOKEN",
          "QITS_DOMAIN",
          "GIT_CONFIG_GLOBAL",
          "QITS_GIT_AUTH_HOST");

  /** The environment names the AGENT slot policy owns; qits-system refuses a spec naming one. */
  public static final Set<String> POLICY_OWNED_ENV = Set.of("HOME", "CLAUDE_CONFIG_DIR", "BUILDKIT_HOST");

  private static final String PLATFORM_PROJECT = "qits";

  private static final ObjectMapper JSON = new ObjectMapper();

  @ConfigProperty(name = "qits.agents.runtime.domain")
  Optional<String> domain;

  @ConfigProperty(name = "qits.agents.runtime.user", defaultValue = "1000")
  String user;

  @ConfigProperty(name = "qits.agents.runtime.memory-limit")
  Optional<String> memory;

  @ConfigProperty(name = "qits.agents.runtime.pids-limit")
  Optional<Long> pidsLimit;

  @ConfigProperty(name = "qits.agents.runtime.cpus")
  Optional<String> cpus;

  @ConfigProperty(name = "qits.agents.runtime.oom-score-adj", defaultValue = "800")
  Integer oomScoreAdj;

  /** Everything a spec is composed from that is configuration rather than the row. */
  public record Settings(
      String domain, String user, String memory, Long pidsLimit, String cpus, Integer oomScoreAdj) {}

  /** The domain cannot address an edge-only container. */
  public static final class EdgePlaneUnconfigured extends IllegalStateException {
    public EdgePlaneUnconfigured(String domain) {
      super(
          "qits-agents knows no public dotted domain (QITS_DOMAIN is '"
              + domain
              + "'), so an agent container has no edge address to dial");
    }
  }

  /** The configuration as it stands now. */
  public Settings settings() {
    return new Settings(
        domain.orElse(null),
        user,
        set(memory),
        pidsLimit.orElse(null),
        set(cpus),
        oomScoreAdj);
  }

  /** The agent's spec as it stands now. Throws {@link EdgePlaneUnconfigured}. */
  public LeaseSpec compose(ProjectAgent agent) {
    return compose(settings(), agent);
  }

  /** The spec, pure: it reads nothing but its arguments and {@link AgentImage#VERSION}. */
  public static LeaseSpec compose(Settings settings, ProjectAgent agent) {
    String d = domainOf(settings.domain());
    String slug = agent.projectSlug;
    Map<String, String> env = new LinkedHashMap<>();
    env.put("QITS_AGENTS_DAEMON_PROJECT", slug);
    env.put("QITS_AGENTS_DAEMON_AGENT_NAME", agent.name);
    env.put("QITS_AGENTS_DAEMON_WRAPPER", wrapper(slug));
    env.put("QITS_AGENTS_DAEMON_GIT_BASE", "https://" + host("githost", d) + "/git/" + slug);
    env.put("QITS_AGENTS_DAEMON_SERVICE_URL", "https://" + host("agents", d) + "/agents");
    env.put("QITS_TOKEN", agent.tokenValue == null ? "" : agent.tokenValue);
    env.put("QITS_DOMAIN", d);
    env.put("GIT_CONFIG_GLOBAL", "/etc/qits-gitconfig");
    env.put("QITS_GIT_AUTH_HOST", host("githost", d));
    Limits limits =
        new Limits(
            settings.memory(),
            settings.memory(),
            settings.pidsLimit(),
            settings.cpus(),
            settings.oomScoreAdj());
    return new LeaseSpec(image(d), env, blankToNull(settings.user()), limits);
  }

  /** {@code registry.qits.<domain>/qits/agent:<AgentImage.VERSION>}. */
  public static String image(String domain) {
    return host("registry", domainOf(domain)) + "/" + AgentImage.REPOSITORY + ":" + AgentImage.VERSION;
  }

  /**
   * The project's wrapper repository, {@code <slug>-<slug>} ({@code qits-qits}): the rule
   * qits-projects' {@code ProjectService.wrapperName} names every wrapper by.
   */
  public static String wrapper(String projectSlug) {
    return projectSlug + "-" + projectSlug;
  }

  /** qits-system's {@code specHash} of {@code spec}: sha256 of its canonical JSON, hex. */
  public static String hash(LeaseSpec spec) {
    try {
      String text = JSON.writeValueAsString(canonical(JSON.valueToTree(spec)));
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (JsonProcessingException | NoSuchAlgorithmException e) {
      throw new IllegalStateException("Could not hash an agent spec", e);
    }
  }

  /** Every object's keys sorted, recursively: qits-system's {@code LeaseSpecs.canonical}. */
  static JsonNode canonical(JsonNode node) {
    if (node == null) {
      return null;
    }
    if (node.isObject()) {
      ObjectNode sorted = JsonNodeFactory.instance.objectNode();
      List<String> names = new ArrayList<>();
      node.fieldNames().forEachRemaining(names::add);
      names.sort(null);
      for (String name : names) {
        sorted.set(name, canonical(node.get(name)));
      }
      return sorted;
    }
    if (node.isArray()) {
      var array = JsonNodeFactory.instance.arrayNode();
      node.forEach(item -> array.add(canonical(item)));
      return array;
    }
    return node;
  }

  /** The folded public domain, or {@link EdgePlaneUnconfigured}. */
  static String domainOf(String domain) {
    String folded =
        domain == null
            ? ""
            : domain.trim().toLowerCase(Locale.ROOT).replaceAll("^\\.+|\\.+$", "");
    if (folded.isEmpty()
        || folded.indexOf('.') < 0
        || folded.equals("localhost")
        || folded.endsWith(".localhost")) {
      throw new EdgePlaneUnconfigured(domain == null ? "" : domain.trim());
    }
    return folded;
  }

  private static String host(String app, String domain) {
    return app + "." + PLATFORM_PROJECT + "." + domain;
  }

  private static String set(Optional<String> value) {
    return value.map(String::trim).filter(v -> !v.isEmpty()).orElse(null);
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
