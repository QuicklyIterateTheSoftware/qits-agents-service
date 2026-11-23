package eu.wohlben.qits.agents.service.api;

import java.util.Set;

/**
 * The roles the design page names, as constants the {@code @RolesAllowed} lists spell out.
 *
 * <ul>
 *   <li><b>Reads</b> (the agents, one agent, its claims): {@code qits:admin}, {@code
 *       qits:admin-agent}, {@code qits:desk-admin}, {@code qits:desk}, {@code qits:agent} and
 *       {@code qits:system}. No read carries the token value.
 *   <li><b>The runner doors</b> (turns, delivered, launch, report): {@code qits:admin}, {@code
 *       qits:desk-admin}, {@code qits:system} — and {@code qits:agent}, admitted by the annotation
 *       and then held to <b>self</b> by {@link SelfOrOperator}: only the agent whose credential's
 *       subject is the caller's. {@code qits:desk-admin} is there so the delivery desk can drive
 *       the live checks and act for a person.
 *   <li><b>{@code GET /me}</b>: {@code qits:agent}, {@code qits:admin-agent}, {@code
 *       qits:desk-admin}, {@code qits:admin} and {@code qits:system}; it answers only a caller that
 *       is an agent's credential.
 * </ul>
 *
 * <p>Each resource carries its set at class level or per method; a method's {@code @RolesAllowed}
 * REPLACES the class-level one. DeskAdminParityRules holds over all of them: wherever {@code
 * qits:admin-agent} is admitted, so is {@code qits:desk-admin}.
 */
public final class Roles {

  public static final String ADMIN = "qits:admin";
  public static final String ADMIN_AGENT = "qits:admin-agent";
  public static final String DESK_ADMIN = "qits:desk-admin";
  public static final String DESK = "qits:desk";
  public static final String AGENT = "qits:agent";
  public static final String SYSTEM = "qits:system";

  /** The runner doors' operators: they act on any agent's row, no self check. */
  public static final Set<String> OPERATORS = Set.of(ADMIN, DESK_ADMIN, SYSTEM);

  private Roles() {}
}
