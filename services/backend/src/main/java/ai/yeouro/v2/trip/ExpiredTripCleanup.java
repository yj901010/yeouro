package ai.yeouro.v2.trip;

import ai.yeouro.v2.sharing.SharingService;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Best-effort retention cleanup, never the authority for access expiry. */
@Component("v2ExpiredTripCleanup")
@ConditionalOnProperty(name = "yeouro.v2.enabled", havingValue = "true")
public class ExpiredTripCleanup {
  private final JdbcTemplate db;
  private final SharingService sharing;
  private final TripAccess access;
  private final TransactionTemplate transactions;
  private final Clock clock;

  public ExpiredTripCleanup(
      JdbcTemplate db,
      SharingService sharing,
      TripAccess access,
      TransactionTemplate transactions,
      Clock clock) {
    this.db = db;
    this.sharing = sharing;
    this.access = access;
    this.transactions = transactions;
    this.clock = clock;
  }

  @Scheduled(fixedDelayString = "${yeouro.v2.cleanup-delay-ms:60000}", initialDelay = 60000)
  public void cleanup() {
    transactions.executeWithoutResult(
        status -> {
          var expired =
              db.queryForList(
                  """
                  SELECT id FROM app.trips WHERE status IN ('PLANNED','ACTIVE') AND expires_at<=?
                  ORDER BY expires_at LIMIT 100 FOR UPDATE SKIP LOCKED
                  """,
                  UUID.class,
                  Timestamp.from(clock.instant()));
          for (UUID trip : expired) {
            sharing.revokeAll(trip, "EXPIRED");
            db.update(
                "UPDATE app.trips SET status='EXPIRED',ended_at=? WHERE id=?",
                Timestamp.from(clock.instant()),
                trip);
            access.bump(trip, false);
          }
          db.update(
              "DELETE FROM app.idempotency_records WHERE expires_at<=?",
              Timestamp.from(clock.instant()));
          db.update(
              "DELETE FROM app.trip_latest_locations WHERE observed_at<?",
              Timestamp.from(clock.instant().minusSeconds(120)));
        });
  }
}
