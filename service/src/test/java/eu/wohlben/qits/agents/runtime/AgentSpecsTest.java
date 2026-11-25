package eu.wohlben.qits.agents.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.agents.runtime.SystemLeasesApi.LeaseSpec;
import eu.wohlben.qits.agents.runtime.SystemLeasesApi.Limits;
import eu.wohlben.qits.agents.service.entity.ProjectAgent;
import eu.wohlben.qits.agentsdaemon.protocol.AgentImage;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * An agent's container, composed from its row and the shipped configuration (the suite's domain is
 * {@code wohlben.eu}): exactly the daemon's env table, nothing the AGENT policy owns, the pinned
 * image, the desk's user and limits, and qits-system's own spec hash.
 */
@QuarkusTest
class AgentSpecsTest {

  @Inject AgentSpecs specs;

  static ProjectAgent developer() {
    ProjectAgent agent = new ProjectAgent();
    agent.id = UUID.fromString("00000000-0000-4000-8000-0000000000aa");
    agent.projectSlug = "qits";
    agent.name = "developer";
    agent.tokenValue = "qits_tok_developer";
    return agent;
  }

  @Test
  void theEnvTableHoldsExactlyTheDaemonsNames() {
    LeaseSpec spec = specs.compose(developer());

    assertEquals(AgentSpecs.ENV, spec.env().keySet());
    assertEquals(
        Map.of(
            "QITS_AGENTS_DAEMON_PROJECT", "qits",
            "QITS_AGENTS_DAEMON_AGENT_NAME", "developer",
            "QITS_AGENTS_DAEMON_WRAPPER", "qits-qits",
            "QITS_AGENTS_DAEMON_GIT_BASE", "https://githost.qits.wohlben.eu/git/qits",
            "QITS_AGENTS_DAEMON_SERVICE_URL", "https://agents.qits.wohlben.eu/agents",
            "QITS_TOKEN", "qits_tok_developer",
            "QITS_DOMAIN", "wohlben.eu",
            "GIT_CONFIG_GLOBAL", "/etc/qits-gitconfig",
            "QITS_GIT_AUTH_HOST", "githost.qits.wohlben.eu"),
        spec.env());
  }

  @Test
  void nothingThePolicyOwnsIsInTheEnv() {
    Set<String> names = specs.compose(developer()).env().keySet();
    for (String owned : Set.of("HOME", "CLAUDE_CONFIG_DIR", "BUILDKIT_HOST")) {
      assertFalse(names.contains(owned), owned + " is the AGENT policy's");
    }
    assertTrue(AgentSpecs.POLICY_OWNED_ENV.stream().noneMatch(names::contains));
  }

  @Test
  void theImageTagIsThePinnedAgentImageVersion() {
    LeaseSpec spec = specs.compose(developer());

    assertEquals("registry.qits.wohlben.eu/qits/agent:" + AgentImage.VERSION, spec.image());
    assertTrue(spec.image().endsWith(":" + AgentImage.VERSION));
  }

  @Test
  void theUserAndLimitsAreTheDesks() {
    LeaseSpec spec = specs.compose(developer());

    assertEquals("1000", spec.user());
    assertEquals(new Limits("4g", "4g", null, null, 800), spec.limits());
  }

  @Test
  void theHashIsQitsSystemsAndMovesWithTheToken() {
    // qits-system's golden master: this spec, sent, is answered with this specHash.
    LeaseSpec recorded =
        new LeaseSpec(
            "registry.qits.wohlben.eu/qits/agent:2026.1",
            Map.of("QITS_TOKEN", "qits_tok_lease"),
            "1000",
            new Limits("8g", null, null, null, null));
    assertEquals(
        "f34e90339b1909046c18196017214e6c9c5f4d3544502c8565272e2ee22d8f73", AgentSpecs.hash(recorded));

    ProjectAgent rotated = developer();
    rotated.tokenValue = "qits_tok_rotated";
    assertNotEquals(
        AgentSpecs.hash(specs.compose(developer())), AgentSpecs.hash(specs.compose(rotated)));
    assertEquals(
        AgentSpecs.hash(specs.compose(developer())), AgentSpecs.hash(specs.compose(developer())));
  }

  @Test
  void theTrustAnchorsEnvHoldsTheRowsFrozenBundle() {
    ProjectAgent agent = developer();
    agent.trustAnchors = "-----BEGIN CERTIFICATE-----\nabc\n-----END CERTIFICATE-----";

    LeaseSpec spec = specs.compose(agent);

    assertEquals(agent.trustAnchors, spec.env().get(AgentSpecs.TRUST_ANCHORS_ENV));
  }

  @Test
  void theTrustAnchorsEnvIsLeftOutWhenTheRowHoldsNone() {
    ProjectAgent agent = developer();
    agent.trustAnchors = null;

    LeaseSpec spec = specs.compose(agent);

    assertFalse(spec.env().containsKey(AgentSpecs.TRUST_ANCHORS_ENV));
    assertEquals(AgentSpecs.ENV, spec.env().keySet());
  }

  @Test
  void aBlankTrustAnchorsRowIsAlsoLeftOut() {
    ProjectAgent agent = developer();
    agent.trustAnchors = "   ";

    assertFalse(specs.compose(agent).env().containsKey(AgentSpecs.TRUST_ANCHORS_ENV));
  }

  @Test
  void aDomainAnEdgeCannotServeRefusesToCompose() {
    for (String domain : new String[] {null, "", "localhost", "qits.localhost", "nodot"}) {
      AgentSpecs.Settings settings = new AgentSpecs.Settings(domain, "1000", "4g", null, null, 800);
      assertThrows(
          AgentSpecs.EdgePlaneUnconfigured.class, () -> AgentSpecs.compose(settings, developer()));
    }
  }
}
