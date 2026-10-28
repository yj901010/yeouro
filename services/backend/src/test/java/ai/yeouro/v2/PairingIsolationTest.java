package ai.yeouro.v2;

import static org.junit.jupiter.api.Assertions.*;

import ai.yeouro.v2.sharing.Secrets;
import ai.yeouro.v2.vehicle.PairingService;
import java.time.Clock;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** Installation identifiers are labels, not authorization to invalidate another pairing. */
class PairingIsolationTest {
  private static final class RecordingJdbc extends JdbcTemplate {
    final List<String> writes = new ArrayList<>();

    @Override
    public <T> T queryForObject(String sql, Class<T> type) {
      return type == Integer.class ? type.cast(0) : null;
    }

    @Override
    public <T> T queryForObject(String sql, Class<T> type, Object... arguments) {
      return type == Integer.class ? type.cast(0) : null;
    }

    @Override
    public int update(String sql, Object... arguments) {
      writes.add(sql);
      return 1;
    }
  }

  @Test
  void publicStartNeverRevokesExistingChallengesByInstallationLabel() {
    var db = new RecordingJdbc();
    var service = new PairingService(db, null, null, Clock.systemUTC());
    var result = service.start(UUID.randomUUID());
    assertNotEquals(result.get("displayCode"), result.get("pollSecret"));
    assertTrue(db.writes.stream().noneMatch(sql -> sql.startsWith("UPDATE app.vehicle_pairings")));
    assertEquals(1, db.writes.size());
    assertEquals(43, ((String) result.get("displayCode")).length());
  }

  @Test
  void secretDigestsAreNotRawCredentialsAndCompareConstantTime() {
    String token = Secrets.issue();
    byte[] digest = Secrets.hash(token);
    assertEquals(32, digest.length);
    assertTrue(Secrets.matches(token, digest));
    assertFalse(Secrets.matches(Secrets.issue(), digest));
    assertFalse(Secrets.matches(null, digest));
  }
}
