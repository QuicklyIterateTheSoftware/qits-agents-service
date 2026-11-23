package eu.wohlben.qits.agents.service.entity;

import eu.wohlben.qits.eventstream.Uncaused;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * A work item an agent holds ({@code agent_claim}): open while {@code released_at} is null. At most
 * one open claim per agent and item ({@code agent_claim_open_uq}), so a redelivered assignment is a
 * no-op. Written from qits-projects' {@code EntityAssigned} ({@code control.Assignments}); read
 * by the claims door and by {@code openClaims} on the agent list.
 *
 * <p><b>Uncaused.</b> The claim carries who claimed and released it ({@code claimed_by}, {@code
 * released_by}), which is the record an operator reads.
 */
@Entity
@Table(name = "agent_claim")
@Uncaused
public class AgentClaim extends PanacheEntityBase {

  @Id public UUID id;

  @Column(name = "agent_id", nullable = false)
  public UUID agentId;

  @Column(name = "entity_id", nullable = false)
  public UUID entityId;

  @Column(name = "qualified_id", nullable = false, length = 64)
  public String qualifiedId;

  @Column(nullable = false, length = 16)
  public String archetype;

  @Column(nullable = false, columnDefinition = "text")
  public String title;

  @Column(name = "claimed_by", nullable = false, length = 256)
  public String claimedBy;

  @Column(name = "claimed_at", nullable = false)
  public Instant claimedAt;

  @Column(name = "released_at")
  public Instant releasedAt;

  @Column(name = "released_by", length = 256)
  public String releasedBy;
}
