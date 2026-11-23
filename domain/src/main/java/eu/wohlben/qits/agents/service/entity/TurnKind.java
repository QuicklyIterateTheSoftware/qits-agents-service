package eu.wohlben.qits.agents.service.entity;

/**
 * Why a turn was queued: a new generation's recovery prompt, an assignment of an item to the agent,
 * or a comment on an item it holds.
 */
public enum TurnKind {
  RECOVERY,
  ASSIGNED,
  COMMENTED
}
