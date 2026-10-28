package ai.yeouro.v2.sharing;

import ai.yeouro.api.ApiException;
import ai.yeouro.v2.trip.TripAccess;
import ai.yeouro.v2.trip.TripAccess.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "yeouro.v2.enabled", havingValue = "true")
public class LocationService {
  private final Clock clock;
  private final JdbcTemplate db;
  private final TripAccess access;
  private final SharingService sharing;
  private final boolean demo;

  public LocationService(
      JdbcTemplate db,
      TripAccess access,
      SharingService sharing,
      @Value("${yeouro.v2.demo-enabled:false}") boolean demo,
      Clock clock) {
    this.clock = clock;
    this.db = db;
    this.access = access;
    this.sharing = sharing;
    this.demo = demo;
  }

  public record Observation(
      UUID observationId,
      Long sharingGeneration,
      Double latitude,
      Double longitude,
      Double accuracyMeters,
      Instant observedAt,
      String source,
      Long publisherSequence) {}

  @Transactional
  public Map<String, Object> publish(UUID trip, String bearer, Observation o) {
    Context c = access.require(trip, bearer, Permission.LOCATION_PUBLISH);
    var session = sharing.requireSession(trip);
    UUID sessionId = (UUID) session.get("id");
    Instant now = clock.instant();
    if (o.sharingGeneration() == null
        || o.latitude() == null
        || o.longitude() == null
        || o.accuracyMeters() == null
        || o.observationId() == null
        || o.observedAt() == null
        || o.source() == null
        || !Double.isFinite(o.latitude())
        || !Double.isFinite(o.longitude())
        || !Double.isFinite(o.accuracyMeters())
        || Math.abs(o.latitude()) > 90
        || Math.abs(o.longitude()) > 180
        || o.accuracyMeters() < 0
        || o.accuracyMeters() > 10000)
      throw ApiException.invalid("INVALID_LOCATION", "Invalid location observation.");
    if (((Number) session.get("generation")).longValue() != o.sharingGeneration())
      throw ApiException.conflict(
          "SHARING_GENERATION_MISMATCH", "Use the current sharing generation.");
    if (!sharing.hasConsent(sessionId, c.participantId(), "LOCATION_PUBLISH"))
      throw ApiException.forbidden();
    if (!(o.source().equals(c.vehicle() ? "VEHICLE" : "PHONE")
        || (demo && o.source().equals("SIMULATED")))) throw ApiException.forbidden();
    Instant observed = o.observedAt().truncatedTo(ChronoUnit.MILLIS);
    byte[] hash =
        Secrets.hash(
            o.observationId()
                + "|"
                + o.sharingGeneration()
                + "|"
                + o.latitude()
                + "|"
                + o.longitude()
                + "|"
                + o.accuracyMeters()
                + "|"
                + observed
                + "|"
                + o.source()
                + "|"
                + o.publisherSequence());
    var existing = db.queryForList("SELECT * FROM app.trip_latest_locations WHERE trip_id=?", trip);
    if (!existing.isEmpty()) {
      var prev = existing.getFirst();
      if (o.observationId().equals(prev.get("observation_id"))) {
        if (!java.security.MessageDigest.isEqual(hash, (byte[]) prev.get("request_hash")))
          throw ApiException.conflict(
              "OBSERVATION_REUSED", "Observation identifier already used for another payload.");
        return metadata(prev);
      }
      if (!observed.isAfter(SharingService.instant(prev.get("observed_at"))))
        throw ApiException.conflict(
            "STALE_LOCATION", "Observation must be newer than the current point.");
    }
    if (observed.isBefore(now.minusSeconds(120)) || observed.isAfter(now.plusSeconds(30)))
      throw ApiException.conflict("STALE_LOCATION", "Observation too old or ahead of server time.");
    db.update(
        "INSERT INTO"
            + " app.trip_latest_locations(trip_id,sharing_session_id,publisher_participant_id,vehicle_pairing_id,latitude,longitude,accuracy_meters,observed_at,received_at,source,publisher_sequence,observation_id,request_hash)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(trip_id) DO UPDATE SET"
            + " sharing_session_id=EXCLUDED.sharing_session_id,publisher_participant_id=EXCLUDED.publisher_participant_id,vehicle_pairing_id=EXCLUDED.vehicle_pairing_id,latitude=EXCLUDED.latitude,longitude=EXCLUDED.longitude,accuracy_meters=EXCLUDED.accuracy_meters,observed_at=EXCLUDED.observed_at,received_at=EXCLUDED.received_at,source=EXCLUDED.source,publisher_sequence=EXCLUDED.publisher_sequence,observation_id=EXCLUDED.observation_id,request_hash=EXCLUDED.request_hash",
        trip,
        sessionId,
        c.participantId(),
        c.pairingId(),
        o.latitude(),
        o.longitude(),
        o.accuracyMeters(),
        java.sql.Timestamp.from(observed),
        java.sql.Timestamp.from(now),
        o.source(),
        o.publisherSequence(),
        o.observationId(),
        hash);
    access.bump(trip, false);
    return Map.of(
        "observationId",
        o.observationId(),
        "receivedAt",
        now,
        "freshness",
        "FRESH",
        "ageSeconds",
        Math.max(0, Duration.between(observed, now).toSeconds()));
  }

  private Map<String, Object> metadata(Map<String, Object> row) {
    Instant observed = SharingService.instant(row.get("observed_at"));
    long age = Math.max(0, Duration.between(observed, clock.instant()).toSeconds());
    return Map.of(
        "observationId",
        row.get("observation_id"),
        "receivedAt",
        SharingService.instant(row.get("received_at")),
        "freshness",
        age > 120 ? "STALE" : "FRESH",
        "ageSeconds",
        age);
  }

  public Map<String, Object> visible(Context c) {
    var s = sharing.currentSession(c.tripId());
    if (s == null
        || !sharing.hasConsent((UUID) s.get("id"), c.participantId(), "TRIP_COLLABORATION")
        || !sharing.hasConsent((UUID) s.get("id"), c.participantId(), "LOCATION_VIEW")) return null;
    var rows =
        db.queryForList(
            "SELECT * FROM app.trip_latest_locations WHERE trip_id=? AND sharing_session_id=?",
            c.tripId(),
            s.get("id"));
    if (rows.isEmpty()) return null;
    var row = rows.getFirst();
    if (!sharing.hasConsent(
            (UUID) s.get("id"), (UUID) row.get("publisher_participant_id"), "LOCATION_PUBLISH")
        || SharingService.instant(row.get("observed_at"))
            .isBefore(clock.instant().minusSeconds(120))) return null;
    var out = new LinkedHashMap<String, Object>(metadata(row));
    out.put("latitude", row.get("latitude"));
    out.put("longitude", row.get("longitude"));
    out.put("accuracyMeters", row.get("accuracy_meters"));
    out.put("observedAt", SharingService.instant(row.get("observed_at")));
    out.put("source", row.get("source"));
    return out;
  }
}
