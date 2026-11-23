package eu.wohlben.qits.agents.service.testdb;

import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.config.spi.ConfigSource;

/**
 * Hands the running {@link EmbeddedPg} to every {@code @QuarkusTest} in this module, as the three
 * keys a deployment would supply — {@code jdbc.url}, {@code username}, {@code password} — for
 * <b>both</b> datasources this deployable boots: this service's own store and the qits-eventstream
 * outbox, which opens and migrates even while the bus is dark in {@code %test}.
 *
 * <p>A config source rather than six lines in the test properties because the port is chosen at
 * run time. The ordinal sits above application.properties (250), and it is registered through
 * {@code META-INF/services}. The database names are this repository's own, so two surefire JVMs on
 * one host never mean one database.
 */
public class EmbeddedPgConfigSource implements ConfigSource {

  /** This service's own store: agents, claims and turns. */
  private static final String AGENTS_DATABASE = "agents_svc";

  /** The outbox's store, named for this repository too. */
  private static final String EVENTSTREAM_DATABASE = "eventstream_agents";

  private final Map<String, String> values =
      Map.of(
          "quarkus.datasource.agents.jdbc.url", EmbeddedPg.url(AGENTS_DATABASE),
          "quarkus.datasource.agents.username", EmbeddedPg.USER,
          "quarkus.datasource.agents.password", EmbeddedPg.PASSWORD,
          "quarkus.datasource.eventstream.jdbc.url", EmbeddedPg.url(EVENTSTREAM_DATABASE),
          "quarkus.datasource.eventstream.username", EmbeddedPg.USER,
          "quarkus.datasource.eventstream.password", EmbeddedPg.PASSWORD);

  @Override
  public int getOrdinal() {
    return 500;
  }

  @Override
  public Set<String> getPropertyNames() {
    return values.keySet();
  }

  @Override
  public String getValue(String propertyName) {
    return values.get(propertyName);
  }

  @Override
  public String getName() {
    return "embedded-pg";
  }
}
