package ai.yeouro;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class PostgresTestDatabaseTest {
  @Test
  void acceptsOnlyExplicitLoopbackDedicatedTestDatabases() {
    assertThat(PostgresTestDatabase.validate("jdbc:postgresql://127.0.0.1:55432/yeouro_test"))
        .endsWith("/yeouro_test");
    assertThat(PostgresTestDatabase.validate("jdbc:postgresql://localhost:5432/example_test"))
        .endsWith("/example_test");
  }

  @Test
  void rejectsRemoteProductionAndParameterBasedHostOverrides() {
    for (String url :
        new String[] {
          "jdbc:postgresql://production.example:5432/yeouro_test",
          "jdbc:postgresql://127.0.0.1:5432/production",
          "jdbc:postgresql://127.0.0.1:5432/yeouro_test?host=production.example",
          "jdbc:postgresql://127.0.0.1:5432,production.example:5432/yeouro_test",
          "jdbc:postgresql://user@localhost:5432/yeouro_test",
          "jdbc:postgresql://127.0.0.1:99999/yeouro_test",
          "jdbc:h2:mem:yeouro_test"
        }) {
      assertThatThrownBy(() -> PostgresTestDatabase.validate(url))
          .as(url)
          .isInstanceOf(IllegalArgumentException.class);
    }
  }
}
