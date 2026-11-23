package eu.wohlben.qits.agents.service.entity;

/**
 * What the runtime last reported about an agent: UNKNOWN until it has reported at all, then one of
 * the five states {@code PUT …/report} accepts.
 */
public enum ReportedState {
  UNKNOWN,
  STARTING,
  IDLE,
  BUSY,
  EXITED,
  FAILED;

  /** The states a report may name: every one but UNKNOWN. */
  public boolean reportable() {
    return this != UNKNOWN;
  }
}
