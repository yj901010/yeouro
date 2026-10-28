package ai.yeouro;

import java.util.regex.Pattern;
import org.springframework.test.context.DynamicPropertyRegistry;

/** Reject unsafe destinations before Spring creates a datasource or applies any migration. */
public final class PostgresTestDatabase {
  private static final Pattern LOCAL_TEST =
      Pattern.compile(
          "^jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):([0-9]{1,5})/([a-zA-Z0-9_]+_test)$");

  private PostgresTestDatabase() {}

  public static String validate(String url) {
    var match = LOCAL_TEST.matcher(url);
    if (!match.matches()) {
      throw new IllegalArgumentException(
          "TEST_DATABASE_URL must target loopback with an explicit port and a database ending"
              + " _test; no URL parameters are allowed.");
    }
    int port = Integer.parseInt(match.group(1));
    if (port < 1024 || port > 65535) {
      throw new IllegalArgumentException(
          "TEST_DATABASE_URL requires a port between 1024 and 65535.");
    }
    return url;
  }

  public static void configure(DynamicPropertyRegistry properties) {
    // Never inherit production DATABASE_URL, username or password.
    String url =
        validate(
            System.getenv()
                .getOrDefault(
                    "TEST_DATABASE_URL", "jdbc:postgresql://127.0.0.1:55432/yeouro_test"));
    String username = System.getenv().getOrDefault("TEST_DATABASE_USERNAME", "yeouro");
    String password = System.getenv().getOrDefault("TEST_DATABASE_PASSWORD", "");
    properties.add("spring.datasource.url", () -> url);
    properties.add("spring.datasource.username", () -> username);
    properties.add("spring.datasource.password", () -> password);
    // Flyway can have an independent URL; pin that destination too.
    properties.add("spring.flyway.url", () -> url);
    properties.add("spring.flyway.user", () -> username);
    properties.add("spring.flyway.password", () -> password);
  }
}
