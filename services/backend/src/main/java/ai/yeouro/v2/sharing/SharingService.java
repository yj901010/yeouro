package ai.yeouro.v2.sharing;

import ai.yeouro.api.ApiException;
import ai.yeouro.v2.identity.IdentityService;
import ai.yeouro.v2.trip.TripAccess;
import ai.yeouro.v2.trip.TripAccess.*;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service("v2SharingService")
@ConditionalOnProperty(name = "yeouro.v2.enabled", havingValue = "true")
public class SharingService {
  private final Clock clock;
  private final JdbcTemplate db;
  private final TripAccess access;
  private final IdentityService identity;

  public SharingService(JdbcTemplate db, TripAccess access, IdentityService identity, Clock clock) {
    this.clock = clock;
    this.db = db;
    this.access = access;
    this.identity = identity;
  }

  public static Instant instant(Object value) {
    return value instanceof java.sql.Timestamp t
        ? t.toInstant()
        : value instanceof OffsetDateTime t ? t.toInstant() : (Instant) value;
  }

  public Map<String, Object> currentSession(UUID trip) {
    return db
        .queryForList(
            "SELECT * FROM app.sharing_sessions WHERE trip_id=? AND status='ACTIVE' AND"
                + " expires_at>CURRENT_TIMESTAMP",
            trip)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public boolean hasConsent(UUID session, UUID participant, String scope) {
    return db.queryForObject(
            "SELECT count(*) FROM app.participant_consents WHERE sharing_session_id=? AND"
                + " participant_id=? AND scope=? AND revoked_at IS NULL",
            Integer.class,
            session,
            participant,
            scope)
        > 0;
  }

  public Map<String, Object> requireSession(UUID trip) {
    var s = currentSession(trip);
    if (s == null)
      throw ApiException.conflict("SHARING_REVOKED", "An active sharing session is required.");
    return s;
  }

  private static void validateScopes(Collection<String> scopes, String policy, String role) {
    if (scopes == null || policy == null || policy.isBlank() || policy.length() > 40)
      throw ApiException.invalid("INVALID_CONSENT", "Explicit scopes and policy version required.");
    for (String scope : scopes)
      if (scope == null
          || !Set.of("TRIP_COLLABORATION", "LOCATION_VIEW", "LOCATION_PUBLISH").contains(scope))
        throw ApiException.invalid("INVALID_SCOPE", "Unknown consent scope.");
    if (scopes.contains("LOCATION_PUBLISH") && !"DRIVER".equals(role))
      throw ApiException.forbidden();
  }

  private void grant(
      UUID trip, UUID session, UUID participant, Collection<String> scopes, String policy) {
    for (String scope : scopes)
      if (!hasConsent(session, participant, scope))
        db.update(
            "INSERT INTO"
                + " app.participant_consents(id,trip_id,sharing_session_id,participant_id,scope,policy_version,granted_at)"
                + " VALUES(?,?,?,?,?,?,CURRENT_TIMESTAMP)",
            UUID.randomUUID(),
            trip,
            session,
            participant,
            scope,
            policy);
  }

  @Transactional
  public Map<String, Object> start(UUID trip, String bearer, List<String> scopes, String policy) {
    Context c = access.require(trip, bearer, Permission.OWNER);
    access.requireLive(c);
    validateScopes(scopes, policy, c.role());
    if (currentSession(trip) != null)
      throw ApiException.conflict("SHARING_ACTIVE", "Sharing is already active.");
    if (db.queryForObject(
            "SELECT count(*) FROM app.sharing_sessions WHERE trip_id=? AND status='ACTIVE'",
            Integer.class,
            trip)
        > 0) {
      // Expiry has the same admission/privacy boundary as an explicit global revocation.
      revokeAll(trip, "EXPIRED");
    }
    db.update("DELETE FROM app.trip_latest_locations WHERE trip_id=?", trip);
    // Readiness is scoped to this collaboration generation, never inherited on re-sharing.
    resetReadiness(trip);
    long generation =
        db.queryForObject(
            "SELECT COALESCE(MAX(generation),0)+1 FROM app.sharing_sessions WHERE trip_id=?",
            Long.class,
            trip);
    UUID id = UUID.randomUUID();
    db.update(
        "INSERT INTO"
            + " app.sharing_sessions(id,trip_id,generation,status,started_by_participant_id,started_at,expires_at)"
            + " VALUES(?,?,?,'ACTIVE',?,CURRENT_TIMESTAMP,?)",
        id,
        trip,
        generation,
        c.participantId(),
        java.sql.Timestamp.from(c.expiresAt()));
    grant(trip, id, c.participantId(), scopes, policy);
    access.bump(trip, false);
    return Map.of("sessionId", id, "generation", generation, "expiresAt", c.expiresAt());
  }

  @Transactional
  public Map<String, Object> invite(
      UUID trip, String bearer, String role, UUID session, int seconds) {
    Context c = access.require(trip, bearer, Permission.OWNER);
    access.requireLive(c);
    var s = requireSession(trip);
    if (!s.get("id").equals(session))
      throw ApiException.conflict(
          "SHARING_GENERATION_MISMATCH", "Use the current sharing session.");
    if (role == null
        || !Set.of("DRIVER", "COMPANION").contains(role)
        || seconds < 1
        || seconds > 900) throw ApiException.invalid("INVALID_INVITE", "Role or expiry invalid.");
    if (role.equals("DRIVER")
        && db.queryForObject(
                "SELECT count(*) FROM app.trip_participants WHERE trip_id=? AND role='DRIVER' AND"
                    + " status='ACTIVE'",
                Integer.class,
                trip)
            > 0) throw ApiException.conflict("DRIVER_EXISTS", "An active driver already exists.");
    db.update(
        "UPDATE app.trip_invitations SET revoked_at=CURRENT_TIMESTAMP WHERE trip_id=? AND"
            + " created_by_participant_id=? AND consumed_at IS NULL AND revoked_at IS NULL",
        trip,
        c.participantId());
    UUID id = UUID.randomUUID();
    String token = Secrets.issue();
    Instant expiry = clock.instant().plusSeconds(seconds);
    if (expiry.isAfter(instant(s.get("expires_at")))) expiry = instant(s.get("expires_at"));
    db.update(
        "INSERT INTO"
            + " app.trip_invitations(id,trip_id,sharing_session_id,created_by_participant_id,token_hash,offered_role,expires_at)"
            + " VALUES(?,?,?,?,?,?,?)",
        id,
        trip,
        session,
        c.participantId(),
        Secrets.hash(token),
        role,
        java.sql.Timestamp.from(expiry));
    return Map.of("id", id, "code", token, "expiresAt", expiry, "role", role);
  }

  public void authorizeJoinReplay(UUID user, String code) {
    if (code == null) throw ApiException.invalid("INVALID_INVITE", "Invite code required.");
    var rows =
        db.queryForList(
            "SELECT * FROM app.trip_invitations WHERE token_hash=?", Secrets.hash(code));
    if (rows.isEmpty()) throw ApiException.forbidden();
    var inv = rows.getFirst();
    UUID trip = (UUID) inv.get("trip_id");
    var t =
        db.queryForList(
            "SELECT id FROM app.trips WHERE id=? AND status IN ('PLANNED','ACTIVE') AND"
                + " expires_at>CURRENT_TIMESTAMP FOR UPDATE",
            trip);
    if (t.isEmpty()) throw ApiException.forbidden();
    var session = requireSession(trip);
    if (!session.get("id").equals(inv.get("sharing_session_id")) || inv.get("revoked_at") != null)
      throw ApiException.forbidden();
    if (inv.get("consumed_at") != null) {
      if (!user.equals(inv.get("consumed_by_user_id"))) throw ApiException.forbidden();
      var m =
          db.queryForList(
              "SELECT id FROM app.trip_participants WHERE trip_id=? AND user_id=? AND"
                  + " status='ACTIVE'",
              trip,
              user);
      if (m.isEmpty()
          || !hasConsent(
              (UUID) session.get("id"), (UUID) m.getFirst().get("id"), "TRIP_COLLABORATION"))
        throw ApiException.forbidden();
    }
  }

  @Transactional
  public Map<String, Object> join(String bearer, String code, List<String> scopes, String policy) {
    UUID user = identity.require(bearer);
    if (code == null) throw ApiException.invalid("INVALID_INVITE", "Invite code required.");
    var rows =
        db.queryForList(
            "SELECT * FROM app.trip_invitations WHERE token_hash=?", Secrets.hash(code));
    if (rows.isEmpty()) throw ApiException.forbidden();
    var inv = rows.getFirst();
    UUID trip = (UUID) inv.get("trip_id");
    db.queryForMap(
        "SELECT id FROM app.trips WHERE id=? AND status IN ('PLANNED','ACTIVE') AND"
            + " expires_at>CURRENT_TIMESTAMP FOR UPDATE",
        trip);
    inv = db.queryForMap("SELECT * FROM app.trip_invitations WHERE id=? FOR UPDATE", inv.get("id"));
    if (inv.get("consumed_at") != null
        || inv.get("revoked_at") != null
        || !instant(inv.get("expires_at")).isAfter(clock.instant()))
      throw ApiException.conflict("INVITE_UNAVAILABLE", "Invite expired or already used.");
    var s = requireSession(trip);
    if (!s.get("id").equals(inv.get("sharing_session_id")))
      throw ApiException.conflict("SHARING_REVOKED", "Invite belongs to an old sharing session.");
    String role = (String) inv.get("offered_role");
    validateScopes(scopes, policy, role);
    if (!scopes.contains("TRIP_COLLABORATION"))
      throw ApiException.invalid(
          "CONSENT_REQUIRED", "Joining requires explicit collaboration consent.");
    if (role.equals("DRIVER")
        && db.queryForObject(
                "SELECT count(*) FROM app.trip_participants WHERE trip_id=? AND role='DRIVER' AND"
                    + " status='ACTIVE'",
                Integer.class,
                trip)
            > 0) throw ApiException.conflict("DRIVER_EXISTS", "An active driver already exists.");
    var memberships =
        db.queryForList(
            "SELECT id,status,role FROM app.trip_participants WHERE trip_id=? AND user_id=?",
            trip,
            user);
    UUID participant;
    if (memberships.isEmpty()) {
      participant = UUID.randomUUID();
      db.update(
          "INSERT INTO"
              + " app.trip_participants(id,trip_id,user_id,role,status,display_name,ready,joined_at)"
              + " VALUES(?,?,?,?,'ACTIVE',(SELECT display_name FROM app.app_users WHERE"
              + " id=?),false,CURRENT_TIMESTAMP)",
          participant,
          trip,
          user,
          role,
          user);
    } else {
      var m = memberships.getFirst();
      if ("ACTIVE".equals(m.get("status")))
        throw ApiException.conflict(
            "ALREADY_MEMBER", "Already a member; update own consent instead.");
      participant = (UUID) m.get("id");
      db.update(
          "UPDATE app.trip_participants SET"
              + " status='ACTIVE',role=?,ready=false,ready_updated_at=NULL,left_at=NULL,joined_at=CURRENT_TIMESTAMP"
              + " WHERE id=?",
          role,
          participant);
    }
    grant(trip, (UUID) s.get("id"), participant, scopes, policy);
    db.update(
        "UPDATE app.trip_invitations SET consumed_at=CURRENT_TIMESTAMP,consumed_by_user_id=? WHERE"
            + " id=?",
        user,
        inv.get("id"));
    access.bump(trip, false);
    return Map.of(
        "tripId",
        trip,
        "participantId",
        participant,
        "role",
        role,
        "sharingGeneration",
        s.get("generation"));
  }

  /** Own consent state remains recoverable after collaboration consent is withdrawn. */
  @Transactional
  public Map<String, Object> ownConsents(UUID trip, String bearer) {
    Context c = access.require(trip, bearer, Permission.MEMBER);
    var session = requireSession(trip);
    UUID sessionId = (UUID) session.get("id");
    Map<String, Boolean> scopes = new LinkedHashMap<>();
    for (String scope : List.of("TRIP_COLLABORATION", "LOCATION_VIEW", "LOCATION_PUBLISH")) {
      scopes.put(scope, hasConsent(sessionId, c.participantId(), scope));
    }
    long version =
        db.queryForObject(
            "SELECT consent_version FROM app.trip_participants WHERE id=?",
            Long.class,
            c.participantId());
    return Map.of(
        "sessionId",
        sessionId,
        "generation",
        session.get("generation"),
        "version",
        version,
        "scopes",
        scopes);
  }

  @Transactional
  public Map<String, Object> consent(
      UUID trip, String bearer, Map<String, Boolean> scopes, String policy, Long expectedVersion) {
    Context c = access.require(trip, bearer, Permission.MEMBER);
    if (scopes == null) throw ApiException.invalid("INVALID_CONSENT", "Scopes required.");
    long version =
        db.queryForObject(
            "SELECT consent_version FROM app.trip_participants WHERE id=?",
            Long.class,
            c.participantId());
    if (expectedVersion == null || expectedVersion != version)
      throw ApiException.conflict("VERSION_CONFLICT", "Refresh consent version.");
    var s = requireSession(trip);
    validateScopes(scopes.keySet(), policy, c.role());
    UUID session = (UUID) s.get("id");
    for (var entry : scopes.entrySet()) {
      if (Boolean.TRUE.equals(entry.getValue()))
        grant(trip, session, c.participantId(), List.of(entry.getKey()), policy);
      else {
        db.update(
            "UPDATE app.participant_consents SET revoked_at=CURRENT_TIMESTAMP WHERE"
                + " sharing_session_id=? AND participant_id=? AND scope=? AND revoked_at IS NULL",
            session,
            c.participantId(),
            entry.getKey());
        if (entry.getKey().equals("LOCATION_PUBLISH"))
          db.update(
              "DELETE FROM app.trip_latest_locations WHERE trip_id=? AND"
                  + " publisher_participant_id=?",
              trip,
              c.participantId());
        if (entry.getKey().equals("TRIP_COLLABORATION")) {
          db.update(
              "UPDATE app.trip_participants SET ready=false,ready_updated_at=NULL WHERE trip_id=?"
                  + " AND id=?",
              trip,
              c.participantId());
        }
        if (entry.getKey().equals("TRIP_COLLABORATION")
            || entry.getKey().equals("LOCATION_PUBLISH"))
          revokeParticipant(trip, c.participantId());
      }
    }
    db.update(
        "UPDATE app.trip_participants SET consent_version=consent_version+1 WHERE id=?",
        c.participantId());
    access.bump(trip, false);
    return Map.of("sessionId", session, "scopes", scopes, "version", version + 1);
  }

  public void revokeParticipant(UUID trip, UUID participant) {
    db.update(
        "UPDATE app.place_proposals SET decision_status='CANCELLED',version=version+1 WHERE"
            + " trip_id=? AND proposer_participant_id=? AND decision_status='PENDING'",
        trip,
        participant);
    db.update(
        "UPDATE app.vehicle_credentials SET revoked_at=CURRENT_TIMESTAMP WHERE trip_id=? AND"
            + " driver_participant_id=? AND revoked_at IS NULL",
        trip,
        participant);
    db.update(
        "UPDATE app.vehicle_pairings SET status='REVOKED',revoked_at=CURRENT_TIMESTAMP WHERE"
            + " trip_id=? AND driver_participant_id=? AND status IN ('APPROVED','CLAIMED')",
        trip,
        participant);
    db.update(
        "DELETE FROM app.trip_latest_locations WHERE trip_id=? AND publisher_participant_id=?",
        trip,
        participant);
    if (db.queryForObject(
            "SELECT role FROM app.trip_participants WHERE id=?", String.class, participant)
        .equals("DRIVER")) cancelCommands(trip);
  }

  private void cancelCommands(UUID trip) {
    db.update(
        "UPDATE app.place_proposals SET decision_status='CANCELLED',version=version+1 WHERE"
            + " trip_id=? AND decision_status='PENDING'",
        trip);
    db.update(
        "UPDATE app.return_reminder_plans SET status='CANCELLED',remind_at=NULL,version=version+1"
            + " WHERE trip_id=? AND status<>'CANCELLED'",
        trip);
    db.update(
        "UPDATE app.navigation_commands SET status=CASE WHEN status='CLAIMED' THEN"
            + " 'OUTCOME_UNKNOWN' ELSE 'CANCELLED'"
            + " END,result_code='SHARING_REVOKED',completed_at=CASE WHEN status='REQUESTED' THEN"
            + " CURRENT_TIMESTAMP ELSE completed_at END,version=version+1 WHERE trip_id=? AND"
            + " status IN ('REQUESTED','CLAIMED')",
        trip);
  }

  private void resetReadiness(UUID trip) {
    db.update(
        "UPDATE app.trip_participants SET ready=false,ready_updated_at=NULL WHERE trip_id=?", trip);
  }

  public void revokeAll(UUID trip, String status) {
    resetReadiness(trip);
    // Only the owner persists across sharing generations; everyone else needs a fresh invite.
    db.update(
        "UPDATE app.trip_participants SET status='LEFT',left_at=CURRENT_TIMESTAMP WHERE trip_id=?"
            + " AND user_id<>(SELECT owner_user_id FROM app.trips WHERE id=?) AND status='ACTIVE'",
        trip,
        trip);
    db.update(
        "UPDATE app.trip_participants SET consent_version=consent_version+1 WHERE trip_id=?", trip);
    db.update(
        "UPDATE app.sharing_sessions SET status=?,revoked_at=CURRENT_TIMESTAMP WHERE trip_id=? AND"
            + " status='ACTIVE'",
        status,
        trip);
    db.update(
        "UPDATE app.participant_consents SET revoked_at=CURRENT_TIMESTAMP WHERE trip_id=? AND"
            + " revoked_at IS NULL",
        trip);
    db.update(
        "UPDATE app.trip_invitations SET revoked_at=CURRENT_TIMESTAMP WHERE trip_id=? AND"
            + " revoked_at IS NULL",
        trip);
    db.update(
        "UPDATE app.vehicle_credentials SET revoked_at=CURRENT_TIMESTAMP WHERE trip_id=? AND"
            + " revoked_at IS NULL",
        trip);
    db.update(
        "UPDATE app.vehicle_pairings SET status='REVOKED',revoked_at=CURRENT_TIMESTAMP WHERE"
            + " trip_id=? AND status IN ('APPROVED','CLAIMED')",
        trip);
    db.update("DELETE FROM app.trip_latest_locations WHERE trip_id=?", trip);
    cancelCommands(trip);
  }

  @Transactional
  public Map<String, Object> revoke(UUID trip, String bearer) {
    access.require(trip, bearer, Permission.OWNER);
    revokeAll(trip, "REVOKED");
    access.bump(trip, false);
    return Map.of("sharingEnabled", false);
  }

  @Transactional
  public void leave(UUID trip, String bearer) {
    Context c = access.require(trip, bearer, Permission.MEMBER);
    if (c.owner())
      throw ApiException.conflict("OWNER_CANNOT_LEAVE", "Owner must end or cancel the trip.");
    db.update(
        "UPDATE app.trip_participants SET"
            + " status='LEFT',left_at=CURRENT_TIMESTAMP,ready=false,ready_updated_at=NULL WHERE"
            + " id=?",
        c.participantId());
    db.update(
        "UPDATE app.participant_consents SET revoked_at=CURRENT_TIMESTAMP WHERE participant_id=?"
            + " AND revoked_at IS NULL",
        c.participantId());
    revokeParticipant(trip, c.participantId());
    access.bump(trip, false);
  }

  @Transactional
  public Map<String, Object> readiness(
      UUID trip, String bearer, boolean ready, Instant observedAt) {
    Context c = access.require(trip, bearer, Permission.COLLABORATE);
    if (!"COMPANION".equals(c.role())) throw ApiException.forbidden();
    if (observedAt == null || observedAt.isAfter(clock.instant().plusSeconds(30)))
      throw ApiException.invalid("INVALID_OBSERVED_AT", "Valid observation time required.");
    db.update(
        "UPDATE app.trip_participants SET ready=?,ready_updated_at=CURRENT_TIMESTAMP WHERE id=?",
        ready,
        c.participantId());
    access.bump(trip, false);
    return Map.of("participantId", c.participantId(), "ready", ready);
  }
}
