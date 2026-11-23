package eu.wohlben.qits.agents.service.entity;

import eu.wohlben.qits.eventstream.Uncaused;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * One turn in an agent's inbox ({@code agent_turn}): a text the runtime hands the agent, then
 * acknowledges with {@code delivered_at}. RECOVERY turns are queued by the roster reconcile when a
 * generation moves past 1; ASSIGNED and COMMENTED ones come from qits-projects' events ({@code
 * control.Assignments}, {@code control.CommentNotices}), keyed by {@code source_event_id} so a
 * redelivered event queues nothing ({@code agent_turn_source_uq}).
 *
 * <p><b>Uncaused.</b> {@code source_event_id} is the causation record of an event-driven turn; a
 * RECOVERY turn's cause is the generation it carries.
 */
@Entity
@Table(name = "agent_turn")
@Uncaused
public class AgentTurn extends PanacheEntityBase {

  @Id public UUID id;

  @Column(name = "agent_id", nullable = false)
  public UUID agentId;

  @Column(nullable = false)
  public int generation;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  public TurnKind kind;

  @Column(nullable = false, columnDefinition = "text")
  public String text;

  @Column(name = "source_event_id")
  public UUID sourceEventId;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

  @Column(name = "delivered_at")
  public Instant deliveredAt;
}
