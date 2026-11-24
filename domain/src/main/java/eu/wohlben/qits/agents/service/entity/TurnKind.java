package eu.wohlben.qits.agents.service.entity;

/**
 * Why a turn was queued: a new generation's recovery prompt, an assignment of an item to the agent,
 * a comment on an item it holds, or a declared wait closing (qits-2597). The {@code kind} column is
 * {@code varchar(16)} with no check constraint, so a new value needs no migration.
 */
public enum TurnKind {
  RECOVERY,
  ASSIGNED,
  COMMENTED,
  WAIT
}
