package eu.wohlben.qits.agents.service.contracts;

import eu.wohlben.qits.agents.service.api.Roles;
import eu.wohlben.qits.agents.service.entity.ReportedState;
import eu.wohlben.qits.agents.service.entity.TurnKind;
import eu.wohlben.qits.agents.service.support.ContractCallers;
import eu.wohlben.qits.agents.service.support.Fixtures;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * <b>qits-agents' provider states</b>: each one clears the store, stages what one consumer
 * situation needs and hands back its parameters. Two callers: {@link GoldenMasterRecordingTest}
 * runs a state before each operation that names it, and {@link ConsumerPactVerificationTest}'s
 * {@code @State} methods delegate here.
 *
 * <p>Both states stage the same agent: qits' {@code developer} at generation 2, RUNNING, last
 * reported IDLE with a session, holding one open and one released claim, one RECOVERY turn pending
 * and one ASSIGNED turn delivered. {@value #A_RUNNING_AGENT_CALLING_AS_ITSELF} also grants the
 * agent's own credential as {@code authorization}, the bearer qits-2519's in-container daemon
 * sends, so every runner door and {@code GET /me} is recorded for the self-caller.
 *
 * <p>Every state starts from an empty store, so applying it twice is the same state, and the ids
 * it stages are fixed.
 */
@ApplicationScoped
public class ProviderStates {

  public static final String A_RUNNING_AGENT = "a running agent";

  public static final String A_RUNNING_AGENT_CALLING_AS_ITSELF =
      "a running agent calling as itself";

  /** Who the operator recordings call as. */
  public static final String OPERATOR = "operator";

  /** The bearer the self-caller presents: the agent's own commissioned token. */
  public static final String SELF_BEARER = "Bearer qits_tok_contract-developer";

  static final UUID AGENT = UUID.fromString("7d4ef290-a4b5-46d7-8273-8e9fa0b1c2d3");

  static final UUID PENDING_TURN = UUID.fromString("8e5f03a1-b5c6-47e8-9384-9fa0b1c2d3e4");

  static final UUID DELIVERED_TURN = UUID.fromString("9f6014b2-c6d7-48f9-a495-a0b1c2d3e4f5");

  static final UUID OPEN_ITEM = UUID.fromString("a07125c3-d7e8-490a-b5a6-b1c2d3e4f5a6");

  static final UUID RELEASED_ITEM = UUID.fromString("b18236d4-e8f9-4a1b-86b7-c2d3e4f5a6b7");

  /** What a state hands back: its parameters, keys sorted, and no unique tokens. */
  public record Setup(Map<String, String> params) {
    public List<String> uniqueTokens() {
      return List.of();
    }
  }

  private final Map<String, Supplier<Setup>> states = new LinkedHashMap<>();

  @Inject Fixtures fixtures;

  public ProviderStates() {
    states.put(A_RUNNING_AGENT, this::aRunningAgent);
    states.put(A_RUNNING_AGENT_CALLING_AS_ITSELF, this::aRunningAgentCallingAsItself);
  }

  public Set<String> names() {
    return Collections.unmodifiableSet(states.keySet());
  }

  public Setup setUp(String state) {
    Supplier<Setup> setup = states.get(state);
    if (setup == null) {
      throw new IllegalArgumentException(
          "No provider state '" + state + "' — this provider answers for " + states.keySet());
    }
    return setup.get();
  }

  public Map<String, String> params(String state) {
    return setUp(state).params();
  }

  /** The state's slug: lower-cased, every run of non-alphanumerics replaced by {@code -}. */
  public static String slug(String state) {
    return state.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
  }

  // --- the states ------------------------------------------------------------------------------

  /** qits' developer at generation 2, with claims and turns. */
  private Setup aRunningAgent() {
    stage();
    return new Setup(Collections.unmodifiableMap(params()));
  }

  /** The same agent, and its own credential granted as {@code authorization}. */
  private Setup aRunningAgentCallingAsItself() {
    stage();
    ContractCallers.bearer(SELF_BEARER, Fixtures.subject(Fixtures.PROJECT, "developer"), Roles.AGENT);
    Map<String, String> params = params();
    params.put("authorization", SELF_BEARER);
    return new Setup(Collections.unmodifiableMap(params));
  }

  private void stage() {
    fixtures.clear();
    Instant claimed = Instant.parse("2026-11-20T09:00:00Z");
    fixtures.agent(AGENT, "developer", 2);
    fixtures.reported(AGENT, ReportedState.IDLE, 2, "session-contract");
    fixtures.claim(AGENT, OPEN_ITEM, "qits-101", claimed, null);
    fixtures.claim(AGENT, RELEASED_ITEM, "qits-100", claimed.minusSeconds(3600), claimed);
    fixtures.turn(
        DELIVERED_TURN,
        AGENT,
        1,
        TurnKind.ASSIGNED,
        "(a person has assigned qits-101 to you)",
        claimed,
        claimed.plusSeconds(5));
    fixtures.turn(
        PENDING_TURN,
        AGENT,
        2,
        TurnKind.RECOVERY,
        "You were restarted; read your claims and carry on.",
        claimed.plusSeconds(60),
        null);
  }

  private static Map<String, String> params() {
    Map<String, String> params = new TreeMap<>();
    params.put("project", Fixtures.PROJECT);
    params.put("name", "developer");
    params.put("turnId", PENDING_TURN.toString());
    return params;
  }
}
