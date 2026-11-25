package eu.wohlben.qits.agents.service.support;

import eu.wohlben.qits.agents.runtime.PlatformTrustAnchors;
import java.util.Optional;

/**
 * The platform CA's anchor bundle (qits-2636), held in memory instead of qits-trust-core's real
 * poller — empty until {@link #set} is called. It extends the deployable's bean because QuarkusMock
 * replaces a bean only with an instance of its own class.
 */
public class FakeTrustAnchors extends PlatformTrustAnchors {

  private volatile String pem;

  /** Today's bundle from now on; {@code null} means none is held. */
  public void set(String pem) {
    this.pem = pem;
  }

  @Override
  public Optional<String> pem() {
    return Optional.ofNullable(pem);
  }
}
