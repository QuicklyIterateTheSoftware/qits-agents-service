package eu.wohlben.qits.agents.runtime;

import java.util.Optional;

/**
 * The platform CA's anchor bundle as it stands right now (qits-2636): the PEM a NEW AGENT lease is
 * asked for with, or empty while none is held. A seam over qits-trust-core's {@code TrustAnchors}
 * so a test holds a fixed answer instead of its real poller.
 *
 * <p>Read by {@link AgentLeases#apply} when it asks for a brand new lease — and by {@link
 * AgentLeases#backfillTrustAnchors} for a live lease whose row froze none (qits-2525) — and frozen onto the
 * agent's row at that moment ({@code ProjectAgent#trustAnchors}, {@code Agents#storeLease}).
 * {@link AgentSpecs#compose} and {@link AgentSpecRoll} never call this: they read the frozen row
 * field, which is what keeps a change of the bundle alone from rolling a running container.
 */
@FunctionalInterface
public interface TrustAnchorSource {

  /** The bundle a lease asked for right now would be given, or empty. Never throws. */
  Optional<String> pem();
}
