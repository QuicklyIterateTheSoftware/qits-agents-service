package eu.wohlben.qits.agents.service.control;

/**
 * A refusal the domain states and the REST layer turns into a status: {@link Kind#BAD_REQUEST} is
 * 400, {@link Kind#FORBIDDEN} 403, {@link Kind#NOT_FOUND} 404 and {@link Kind#CONFLICT} 409. The
 * message is the answer's text, so it never carries a token.
 */
public class AgentsException extends RuntimeException {

  /** Which status the refusal is. */
  public enum Kind {
    BAD_REQUEST,
    FORBIDDEN,
    NOT_FOUND,
    CONFLICT
  }

  private final Kind kind;

  public AgentsException(Kind kind, String message) {
    super(message);
    this.kind = kind;
  }

  public Kind kind() {
    return kind;
  }

  public static AgentsException badRequest(String message) {
    return new AgentsException(Kind.BAD_REQUEST, message);
  }

  public static AgentsException forbidden(String message) {
    return new AgentsException(Kind.FORBIDDEN, message);
  }

  public static AgentsException notFound(String message) {
    return new AgentsException(Kind.NOT_FOUND, message);
  }

  public static AgentsException conflict(String message) {
    return new AgentsException(Kind.CONFLICT, message);
  }
}
