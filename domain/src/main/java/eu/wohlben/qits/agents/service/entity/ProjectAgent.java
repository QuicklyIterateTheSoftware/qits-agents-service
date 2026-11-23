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
 * One declared agent of a project's roster ({@code agent}): what its declaration says, which
 * generation of it should run, what the runtime last reported, and its credential.
 *
 * <p>{@code generation} is 1 at first sight and moves by one exactly when the declaration's hash
 * ({@code AgentRosterDocument.hash}) changes, or when a RETIRED agent is declared again. A row is
 * never deleted: an agent its roster no longer declares is RETIRED, its token revoked, and its
 * claims and turns stay for the record.
 *
 * <p>{@code token_value} is the commissioned bearer, handed to the runtime by {@code launch} and
 * never served by any other door.
 *
 * <p><b>Uncaused.</b> The roster reconcile writes the declaration (its cause is a release of this
 * service), and the runtime writes the report (its caller is on the door's log line).
 */
@Entity
@Table(name = "agent")
@Uncaused
public class ProjectAgent extends PanacheEntityBase {

  @Id public UUID id;

  @Column(name = "project_id", nullable = false)
  public UUID projectId;

  @Column(name = "project_slug", nullable = false, length = 63)
  public String projectSlug;

  @Column(nullable = false, length = 32)
  public String name;

  @Column(nullable = false, columnDefinition = "text")
  public String description;

  @Column(nullable = false, columnDefinition = "text")
  public String brief;

  @Column(name = "recovery_prompt", nullable = false, columnDefinition = "text")
  public String recoveryPrompt;

  @Column(length = 64)
  public String model;

  @Column(length = 32)
  public String effort;

  @Column(name = "declaration_hash", nullable = false, columnDefinition = "char(64)")
  public String declarationHash;

  @Column(nullable = false)
  public int generation;

  @Enumerated(EnumType.STRING)
  @Column(name = "desired_state", nullable = false, length = 16)
  public DesiredState desiredState;

  @Enumerated(EnumType.STRING)
  @Column(name = "reported_state", nullable = false, length = 16)
  public ReportedState reportedState;

  @Column(name = "reported_generation")
  public Integer reportedGeneration;

  @Column(name = "session_id", length = 128)
  public String sessionId;

  @Column(name = "reported_detail", columnDefinition = "text")
  public String reportedDetail;

  @Column(name = "reported_at")
  public Instant reportedAt;

  @Column(name = "token_id")
  public UUID tokenId;

  @Column(name = "token_subject", length = 128)
  public String tokenSubject;

  @Column(name = "token_value", columnDefinition = "text")
  public String tokenValue;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt;
}
