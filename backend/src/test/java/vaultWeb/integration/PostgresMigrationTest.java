package vaultWeb.integration;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import vaultWeb.BackendApplication;

@EnabledIfEnvironmentVariable(named = "VAULT_TEST_POSTGRES_URL", matches = "jdbc:postgresql:.*")
class PostgresMigrationTest {
  private final String url = System.getenv("VAULT_TEST_POSTGRES_URL");
  private final String user = System.getenv("VAULT_TEST_POSTGRES_USER");
  private final String password = System.getenv("VAULT_TEST_POSTGRES_PASSWORD");
  private final String schema =
      "vault_migration_test_" + UUID.randomUUID().toString().replace("-", "");

  @BeforeEach
  void createSchema() throws SQLException {
    try (var connection = DriverManager.getConnection(url, user, password);
        var statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA " + schema);
    }
  }

  @AfterEach
  void dropSchema() throws SQLException {
    try (var connection = DriverManager.getConnection(url, user, password);
        var statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
  }

  @Test
  void startsOnAnEmptyDatabaseWithHibernateValidation() throws SQLException {
    try (var context = start(false)) {
      assertTrue(context.isRunning());
      assertMigrationInstalled();
    }
  }

  @Test
  void baselinesExistingSchemaAndPreservesPopulatedChatMessages() throws SQLException {
    try (var connection = openSchema();
        var statement = connection.createStatement()) {
      ScriptUtils.executeSqlScript(
          connection, new ClassPathResource("db/migration/V1__baseline_schema.sql"));
      statement.executeUpdate("INSERT INTO vault_user (id, username) VALUES (1, 'fixture')");
      statement.executeUpdate(
          "INSERT INTO chat_message (id, sender_id, e2ee_payload) VALUES (1, 1, 'fixture-payload')");
    }
    try (var context = start(true);
        var connection = openSchema();
        var statement = connection.createStatement();
        var rows =
            statement.executeQuery("SELECT deleted, e2ee_payload FROM chat_message WHERE id=1")) {
      assertTrue(context.isRunning());
      assertTrue(rows.next());
      assertFalse(rows.getBoolean("deleted"));
      assertFalse(rows.wasNull());
      assertEquals("fixture-payload", rows.getString("e2ee_payload"));
      assertMigrationInstalled();
    }
  }

  @Test
  void refusesToStartWhenTheMigrationFails() throws SQLException {
    try (var connection = openSchema();
        var statement = connection.createStatement()) {
      statement.execute("CREATE TABLE chat_message (deleted integer)");
    }
    assertThatThrownBy(
            () -> {
              try (var ignored = start(true)) {
                throw new AssertionError(
                    "The application started with an incompatible deleted column");
              }
            })
        .hasStackTraceContaining("V2__backfill_chat_message_deleted.sql");
  }

  private void assertMigrationInstalled() throws SQLException {
    try (var connection = openSchema();
        var statement = connection.createStatement();
        var rows =
            statement.executeQuery(
                "SELECT count(*) FROM flyway_schema_history WHERE version IN ('2', '3') AND success")) {
      assertTrue(rows.next());
      assertEquals(2, rows.getInt(1));
    }
  }

  private String schemaUrl() {
    return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
  }

  private Connection openSchema() throws SQLException {
    return DriverManager.getConnection(schemaUrl(), user, password);
  }

  private ConfigurableApplicationContext start(boolean baseline) {
    return new SpringApplicationBuilder(BackendApplication.class)
        .run(
            "--server.port=0",
            "--spring.datasource.url=" + schemaUrl(),
            "--spring.datasource.username=" + user,
            "--spring.datasource.password=" + password,
            "--spring.flyway.baseline-on-migrate=" + baseline,
            "--jwt.secret=" + "a".repeat(48),
            "--jwt.refreshSecret=" + "b".repeat(48),
            "--jwt.mediaSecret=" + "c".repeat(48));
  }
}
