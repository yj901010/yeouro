package ai.yeouro.v2.vehicle;

import ai.yeouro.api.ApiException;
import ai.yeouro.v2.sharing.*;
import ai.yeouro.v2.trip.TripAccess;
import ai.yeouro.v2.trip.TripAccess.*;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "yeouro.v2.enabled", havingValue = "true")
public class PairingService {
  public static final Set<String> SCOPES =
      Set.of(
          "TRIP_READ",
          "LOCATION_PUBLISH",
          "NAV_COMMAND_CLAIM",
          "NAV_RESULT_WRITE",
          "PROPOSAL_DECIDE",
          "TRIP_END",
          "PARKING_CONFIRM",
          "TRIP_PHASE");
  private final Clock clock;
  private final JdbcTemplate db;
  private final TripAccess access;
  private final SharingService sharing;

  public PairingService(JdbcTemplate db, TripAccess access, SharingService sharing, Clock clock) {
    this.clock = clock;
    this.db = db;
    this.access = access;
    this.sharing = sharing;
  }

  @Transactional
  public Map<String, Object> start(UUID installation) {
    if (installation == null)
      throw ApiException.invalid("INVALID_INSTALLATION", "Installation identifier required.");
    // Database-wide pending budget also bounds abuse across forged installation identifiers.
    db.queryForObject("SELECT pg_advisory_xact_lock(871920021)", Object.class);
    if (db.queryForObject(
            "SELECT count(*) FROM app.vehicle_pairings WHERE expires_at>CURRENT_TIMESTAMP",
            Integer.class)
        > 1000)
      throw new ApiException(
          HttpStatus.TOO_MANY_REQUESTS,
          "PAIRING_RATE_LIMIT",
          "Too many pairing attempts; retry later.");
    if (db.queryForObject(
            "SELECT count(*) FROM app.vehicle_pairings WHERE installation_id=? AND"
                + " expires_at>CURRENT_TIMESTAMP",
            Integer.class,
            installation)
        >= 5)
      throw new ApiException(
          HttpStatus.TOO_MANY_REQUESTS,
          "PAIRING_RATE_LIMIT",
          "Too many pairing attempts; retry later.");
    // Installation labels are untrusted: never cancel an existing challenge by label.
    UUID id = UUID.randomUUID();
    String code = Secrets.issue(), poll = Secrets.issue();
    Instant expiry = clock.instant().plusSeconds(300);
    db.update(
        "INSERT INTO"
            + " app.vehicle_pairings(id,installation_id,challenge_hash,poll_secret_hash,status,expires_at)"
            + " VALUES(?,?,?,?,'PENDING',?)",
        id,
        installation,
        Secrets.hash(code),
        Secrets.hash(poll),
        java.sql.Timestamp.from(expiry));
    return Map.of("pairingId", id, "displayCode", code, "pollSecret", poll, "expiresAt", expiry);
  }

  public void authorizeApproval(UUID pairing, String bearer, UUID trip) {
    Context c = access.require(trip, bearer, Permission.DRIVER);
    if (c.vehicle()) throw ApiException.forbidden();
    var session = sharing.requireSession(trip);
    if (!sharing.hasConsent((UUID) session.get("id"), c.participantId(), "TRIP_COLLABORATION"))
      throw ApiException.forbidden();
    var p = row(pairing, false);
    if (Set.of("REVOKED", "EXPIRED").contains(p.get("status"))) throw ApiException.forbidden();
    if (p.get("trip_id") != null
        && (!trip.equals(p.get("trip_id"))
            || !session.get("id").equals(p.get("sharing_session_id"))
            || !c.participantId().equals(p.get("driver_participant_id"))))
      throw ApiException.forbidden();
  }

  @Transactional
  public Map<String, Object> approve(
      UUID pairing, String bearer, UUID trip, String code, List<String> scopes) {
    Context c = access.require(trip, bearer, Permission.DRIVER);
    if (c.vehicle()) throw ApiException.forbidden();
    var s = sharing.requireSession(trip);
    if (!sharing.hasConsent((UUID) s.get("id"), c.participantId(), "TRIP_COLLABORATION"))
      throw ApiException.forbidden();
    if (scopes == null || scopes.isEmpty() || !SCOPES.containsAll(scopes))
      throw ApiException.invalid("INVALID_SCOPE", "Explicit vehicle scope allowlist required.");
    if (scopes.contains("LOCATION_PUBLISH")
        && !sharing.hasConsent((UUID) s.get("id"), c.participantId(), "LOCATION_PUBLISH"))
      throw ApiException.forbidden();
    var p = row(pairing, true);
    if (!Secrets.matches(code, (byte[]) p.get("challenge_hash"))) throw ApiException.forbidden();
    checkPending(p);
    db.update(
        "UPDATE app.vehicle_pairings SET"
            + " trip_id=?,sharing_session_id=?,driver_participant_id=?,scopes=?,status='APPROVED',approved_at=CURRENT_TIMESTAMP"
            + " WHERE id=?",
        trip,
        s.get("id"),
        c.participantId(),
        String.join(",", new TreeSet<>(scopes)),
        pairing);
    return Map.of("pairingId", pairing, "status", "APPROVED");
  }

  private Map<String, Object> row(UUID id, boolean lock) {
    var rows =
        db.queryForList(
            "SELECT * FROM app.vehicle_pairings WHERE id=?" + (lock ? " FOR UPDATE" : ""), id);
    if (rows.isEmpty()) throw ApiException.unauthorized();
    return rows.getFirst();
  }

  private void checkPending(Map<String, Object> p) {
    if (!"PENDING".equals(p.get("status"))
        || !SharingService.instant(p.get("expires_at")).isAfter(clock.instant()))
      throw ApiException.conflict("PAIRING_UNAVAILABLE", "Pairing expired or no longer pending.");
  }

  public void verifyPoll(UUID id, String poll) {
    var p = row(id, false);
    if (!Secrets.matches(poll, (byte[]) p.get("poll_secret_hash")))
      throw ApiException.unauthorized();
  }

  @Transactional
  public Map<String, Object> poll(UUID id, String poll) {
    var p = row(id, false);
    if (!Secrets.matches(poll, (byte[]) p.get("poll_secret_hash")))
      throw ApiException.unauthorized();
    String status = (String) p.get("status");
    if (Set.of("PENDING", "APPROVED").contains(status)
        && !SharingService.instant(p.get("expires_at")).isAfter(clock.instant()))
      status = "EXPIRED";
    return Map.of(
        "pairingId",
        id,
        "status",
        status,
        "expiresAt",
        SharingService.instant(p.get("expires_at")));
  }

  @Transactional
  public Map<String, Object> claim(UUID id, String poll) {
    var initial = row(id, false);
    if (!Secrets.matches(poll, (byte[]) initial.get("poll_secret_hash")))
      throw ApiException.unauthorized();
    if (initial.get("trip_id") == null)
      throw ApiException.conflict("PAIRING_NOT_APPROVED", "Phone approval required.");
    UUID trip = (UUID) initial.get("trip_id");
    // Same trip-first lock ordering as approval, revoke, end, and location avoids deadlocks.
    var trips = db.queryForList("SELECT * FROM app.trips WHERE id=? FOR UPDATE", trip);
    if (trips.isEmpty()) throw ApiException.unauthorized();
    var t = trips.getFirst();
    var p = row(id, true);
    if ("CLAIMED".equals(p.get("status")))
      throw ApiException.conflict(
          "SECRET_ALREADY_ISSUED", "Start new pairing; credentials are never replayed.");
    if (!"APPROVED".equals(p.get("status"))
        || !SharingService.instant(p.get("expires_at")).isAfter(clock.instant()))
      throw ApiException.conflict("PAIRING_UNAVAILABLE", "Pairing expired or revoked.");
    if (!Set.of("PLANNED", "ACTIVE").contains(t.get("status"))
        || !SharingService.instant(t.get("expires_at")).isAfter(clock.instant()))
      throw ApiException.forbidden();
    var s = sharing.requireSession(trip);
    UUID driver = (UUID) p.get("driver_participant_id");
    if (!s.get("id").equals(p.get("sharing_session_id"))
        || !sharing.hasConsent((UUID) s.get("id"), driver, "TRIP_COLLABORATION")
        || db.queryForObject(
                "SELECT count(*) FROM app.trip_participants WHERE id=? AND trip_id=? AND"
                    + " status='ACTIVE' AND role='DRIVER'",
                Integer.class,
                driver,
                trip)
            != 1) throw ApiException.forbidden();
    if (Arrays.asList(((String) p.get("scopes")).split(",")).contains("LOCATION_PUBLISH")
        && !sharing.hasConsent((UUID) s.get("id"), driver, "LOCATION_PUBLISH"))
      throw ApiException.forbidden();
    Instant expiry = clock.instant().plusSeconds(43200);
    for (Instant bound :
        List.of(
            SharingService.instant(s.get("expires_at")),
            SharingService.instant(t.get("expires_at"))))
      if (bound.isBefore(expiry)) expiry = bound;
    String token = Secrets.issue();
    db.update(
        "INSERT INTO"
            + " app.vehicle_credentials(id,pairing_id,trip_id,sharing_session_id,driver_participant_id,token_hash,scopes,expires_at)"
            + " VALUES(?,?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        id,
        trip,
        s.get("id"),
        driver,
        Secrets.hash(token),
        p.get("scopes"),
        java.sql.Timestamp.from(expiry));
    db.update("UPDATE app.vehicle_pairings SET status='CLAIMED' WHERE id=?", id);
    return Map.of(
        "pairingId",
        id,
        "tripId",
        trip,
        "credential",
        token,
        "expiresAt",
        expiry,
        "scopes",
        ((String) p.get("scopes")).split(","),
        "sharingGeneration",
        s.get("generation"));
  }

  @Transactional
  public void revoke(UUID trip, UUID pairing, String bearer) {
    access.require(trip, bearer, Permission.OWNER_OR_DRIVER);
    if (db.update(
            "UPDATE app.vehicle_pairings SET status='REVOKED',revoked_at=CURRENT_TIMESTAMP WHERE"
                + " id=? AND trip_id=?",
            pairing,
            trip)
        == 0) throw ApiException.forbidden();
    db.update(
        "UPDATE app.vehicle_credentials SET revoked_at=CURRENT_TIMESTAMP WHERE pairing_id=? AND"
            + " trip_id=?",
        pairing,
        trip);
    db.update(
        "DELETE FROM app.trip_latest_locations WHERE trip_id=? AND vehicle_pairing_id=?",
        trip,
        pairing);
    db.update(
        "UPDATE app.navigation_commands SET status=CASE WHEN status='CLAIMED' THEN"
            + " 'OUTCOME_UNKNOWN' ELSE 'CANCELLED'"
            + " END,result_code='SHARING_REVOKED',completed_at=CASE WHEN status='REQUESTED' THEN"
            + " CURRENT_TIMESTAMP ELSE completed_at END,version=version+1 WHERE trip_id=? AND"
            + " target_pairing_id=? AND status IN ('REQUESTED','CLAIMED')",
        trip,
        pairing);
    access.bump(trip, false);
  }
}
