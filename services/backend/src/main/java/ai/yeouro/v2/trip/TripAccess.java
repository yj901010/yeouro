package ai.yeouro.v2.trip;

import ai.yeouro.api.ApiException;
import ai.yeouro.v2.common.Secrets;
import ai.yeouro.v2.identity.IdentityService;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service("v2TripAccess")
@ConditionalOnProperty(name = "yeouro.v2.enabled", havingValue = "true")
public class TripAccess {
  public enum Permission {
    MEMBER,
    READ,
    OWNER,
    DRIVER,
    OWNER_OR_DRIVER,
    COLLABORATE,
    LOCATION_PUBLISH,
    LOCATION_VIEW,
    PARKING_CONFIRM,
    TRIP_PHASE,
    NAV_COMMAND_CLAIM,
    NAV_RESULT_WRITE,
    PROPOSAL_DECIDE,
    TRIP_END
  }

  public record Context(
      UUID tripId,
      UUID userId,
      UUID participantId,
      UUID sharingSessionId,
      UUID pairingId,
      String role,
      String status,
      String phase,
      boolean owner,
      boolean vehicle,
      long revision,
      long planRevision,
      Instant expiresAt,
      Instant deadlineAt,
      String arrivalBasis,
      String timeZone,
      Integer plannedStayMinutes) {}

  private final JdbcTemplate db;
  private final IdentityService identity;
  private final Clock clock;

  public TripAccess(JdbcTemplate db, IdentityService identity, Clock clock) {
    this.db = db;
    this.identity = identity;
    this.clock = clock;
  }

  private record Actor(
      UUID userId, UUID participantId, UUID pairingId, UUID credentialSession, Set<String> scopes) {
    boolean vehicle() {
      return pairingId != null;
    }
  }

  /** Caller owns transaction; every mutation shares this lock with consent revocation. */
  public Context require(UUID tripId, String bearer, Permission permission) {
    Map<String, Object> trip = lockTrip(tripId);
    Actor actor = authenticate(tripId, bearer);
    String role = activeRole(tripId, actor.participantId());
    boolean owner = !actor.vehicle() && trip.get("owner_user_id").equals(actor.userId());
    boolean live = live((String) trip.get("status"), instant(trip.get("expires_at")));
    if (!live
        && !(owner
            && Set.of(Permission.READ, Permission.OWNER, Permission.TRIP_END)
                .contains(permission))) {
      throw inactive();
    }
    UUID session = live ? currentSession(tripId) : null;
    if (actor.vehicle()) authorizeVehicle(actor, role, session, permission);
    else authorizePhone(actor, role, owner, session, permission);
    authorizeConsent(actor.participantId(), session, permission);
    return context(tripId, trip, actor, role, owner, session);
  }

  private Map<String, Object> lockTrip(UUID tripId) {
    var rows = db.queryForList("SELECT * FROM app.trips WHERE id=? FOR UPDATE", tripId);
    if (rows.isEmpty()) throw missing();
    return rows.getFirst();
  }

  private Actor authenticate(UUID tripId, String bearer) {
    if (bearer == null || !bearer.startsWith("Bearer ")) throw ApiException.unauthorized();
    var credentials =
        db.queryForList(
            "SELECT c.* FROM app.vehicle_credentials c JOIN app.vehicle_pairings p ON"
                + " p.id=c.pairing_id AND p.trip_id=c.trip_id WHERE c.token_hash=? AND c.trip_id=?"
                + " AND c.revoked_at IS NULL AND c.expires_at>? AND p.status='CLAIMED' AND"
                + " p.revoked_at IS NULL",
            Secrets.hash(bearer.substring(7)),
            tripId,
            java.sql.Timestamp.from(clock.instant()));
    if (!credentials.isEmpty()) {
      var credential = credentials.getFirst();
      return new Actor(
          null,
          (UUID) credential.get("driver_participant_id"),
          (UUID) credential.get("pairing_id"),
          (UUID) credential.get("sharing_session_id"),
          new HashSet<>(Arrays.asList(((String) credential.get("scopes")).split(","))));
    }
    UUID user = identity.require(bearer);
    var members =
        db.queryForList(
            "SELECT id FROM app.trip_participants WHERE trip_id=? AND user_id=? AND"
                + " status='ACTIVE'",
            tripId,
            user);
    if (members.isEmpty()) throw missing();
    return new Actor(user, (UUID) members.getFirst().get("id"), null, null, Set.of());
  }

  private String activeRole(UUID tripId, UUID participant) {
    var rows =
        db.queryForList(
            "SELECT role FROM app.trip_participants WHERE trip_id=? AND id=? AND status='ACTIVE'",
            tripId,
            participant);
    if (rows.isEmpty()) throw ApiException.forbidden();
    return (String) rows.getFirst().get("role");
  }

  private UUID currentSession(UUID tripId) {
    var rows =
        db.queryForList(
            "SELECT id FROM app.sharing_sessions WHERE trip_id=? AND status='ACTIVE' AND"
                + " expires_at>?",
            tripId,
            java.sql.Timestamp.from(clock.instant()));
    return rows.isEmpty() ? null : (UUID) rows.getFirst().get("id");
  }

  private void authorizeVehicle(Actor actor, String role, UUID session, Permission permission) {
    if (session == null || !session.equals(actor.credentialSession()))
      throw ApiException.unauthorized();
    if (!role.equals("DRIVER")) throw ApiException.forbidden();
    String scope =
        switch (permission) {
          case READ -> "TRIP_READ";
          case LOCATION_PUBLISH -> "LOCATION_PUBLISH";
          case DRIVER, PROPOSAL_DECIDE -> "PROPOSAL_DECIDE";
          case NAV_COMMAND_CLAIM -> "NAV_COMMAND_CLAIM";
          case NAV_RESULT_WRITE -> "NAV_RESULT_WRITE";
          case PARKING_CONFIRM -> "PARKING_CONFIRM";
          case TRIP_PHASE -> "TRIP_PHASE";
          case TRIP_END -> "TRIP_END";
          default -> null;
        };
    if (scope == null
        || !actor.scopes().contains(scope)
        || !consent(session, actor.participantId(), "TRIP_COLLABORATION"))
      throw ApiException.forbidden();
  }

  private void authorizePhone(
      Actor actor, String role, boolean owner, UUID session, Permission permission) {
    // MEMBER lets a participant grant their own consent without already having it.
    if (!owner
        && permission != Permission.MEMBER
        && !consent(session, actor.participantId(), "TRIP_COLLABORATION"))
      throw ApiException.forbidden();
    boolean driver = role.equals("DRIVER");
    boolean allowed =
        switch (permission) {
          case OWNER -> owner;
          case DRIVER, PROPOSAL_DECIDE, PARKING_CONFIRM, TRIP_PHASE, LOCATION_PUBLISH -> driver;
          case OWNER_OR_DRIVER, TRIP_END -> owner || driver;
          case NAV_COMMAND_CLAIM, NAV_RESULT_WRITE -> false;
          default -> true;
        };
    if (!allowed) throw ApiException.forbidden();
  }

  private void authorizeConsent(UUID participant, UUID session, Permission permission) {
    if (Set.of(
                Permission.COLLABORATE,
                Permission.PROPOSAL_DECIDE,
                Permission.LOCATION_PUBLISH,
                Permission.LOCATION_VIEW)
            .contains(permission)
        && !consent(session, participant, "TRIP_COLLABORATION")) throw ApiException.forbidden();
    if (Set.of(Permission.LOCATION_PUBLISH, Permission.LOCATION_VIEW).contains(permission)
        && !consent(session, participant, permission.name())) throw ApiException.forbidden();
  }

  private Context context(
      UUID tripId,
      Map<String, Object> trip,
      Actor actor,
      String role,
      boolean owner,
      UUID session) {
    return new Context(
        tripId,
        actor.userId(),
        actor.participantId(),
        session,
        actor.pairingId(),
        role,
        (String) trip.get("status"),
        (String) trip.get("phase"),
        owner,
        actor.vehicle(),
        ((Number) trip.get("revision")).longValue(),
        ((Number) trip.get("plan_revision")).longValue(),
        instant(trip.get("expires_at")),
        instant(trip.get("deadline_at")),
        (String) trip.get("arrival_basis"),
        (String) trip.get("time_zone"),
        (Integer) trip.get("planned_stay_minutes"));
  }

  /** OWNER history/idempotent termination is readable after expiry; new writes must call this. */
  public void requireLive(Context context) {
    if (!live(context.status(), context.expiresAt())) throw inactive();
  }

  private boolean live(String status, Instant expiresAt) {
    return Set.of("PLANNED", "ACTIVE").contains(status) && expiresAt.isAfter(clock.instant());
  }

  private ApiException inactive() {
    return ApiException.conflict("TRIP_INACTIVE", "Trip has ended or expired.");
  }

  public boolean consent(UUID session, UUID participant, String scope) {
    return session != null
        && Boolean.TRUE.equals(
            db.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM app.participant_consents WHERE sharing_session_id=?"
                    + " AND participant_id=? AND scope=? AND revoked_at IS NULL)",
                Boolean.class,
                session,
                participant,
                scope));
  }

  public void bump(UUID trip, boolean plan) {
    db.update(
        "UPDATE app.trips SET"
            + " revision=revision+1,plan_revision=plan_revision+?,version=version+1,updated_at=now()"
            + " WHERE id=?",
        plan ? 1 : 0,
        trip);
  }

  public void changePhase(UUID trip, String phase) {
    db.update("UPDATE app.trips SET phase=? WHERE id=?", phase, trip);
    bump(trip, false);
  }

  public void checkPlan(Context ctx, String etag) {
    if (etag == null)
      throw new ApiException(
          HttpStatus.PRECONDITION_REQUIRED, "IF_MATCH_REQUIRED", "Plan ETag is required.");
    if (!etag.equals("\"plan-" + ctx.planRevision() + "\""))
      throw new ApiException(
          HttpStatus.PRECONDITION_FAILED, "PLAN_REVISION_CONFLICT", "Refresh the plan.");
  }

  public static Instant instant(Object value) {
    if (value == null) return null;
    if (value instanceof java.sql.Timestamp ts) return ts.toInstant();
    if (value instanceof OffsetDateTime odt) return odt.toInstant();
    return (Instant) value;
  }

  private ApiException missing() {
    return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Trip unavailable.");
  }
}
