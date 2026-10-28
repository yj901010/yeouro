package ai.yeouro.v2.trip;

import ai.yeouro.api.ApiException;
import ai.yeouro.v2.common.IdempotencyService;
import ai.yeouro.v2.identity.IdentityService;
import ai.yeouro.v2.planning.ReminderService;
import ai.yeouro.v2.planning.StopsService;
import ai.yeouro.v2.sharing.LocationService;
import ai.yeouro.v2.sharing.SharingService;
import ai.yeouro.v2.trip.TripModels.*;
import java.time.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service("v2TripService")
@ConditionalOnProperty(name = "yeouro.v2.enabled", havingValue = "true")
public class TripService {
  private final JdbcTemplate db;
  private final IdentityService identity;
  private final TripAccess access;
  private final IdempotencyService idempotency;
  private final SharingService sharing;
  private final LocationService locations;
  private final Clock clock;
  private final ReminderService reminders;
  private final StopsService stops;

  public TripService(
      JdbcTemplate db,
      IdentityService identity,
      TripAccess access,
      IdempotencyService idempotency,
      SharingService sharing,
      LocationService locations,
      Clock clock,
      ReminderService reminders,
      StopsService stops) {
    this.db = db;
    this.identity = identity;
    this.access = access;
    this.idempotency = idempotency;
    this.sharing = sharing;
    this.locations = locations;
    this.clock = clock;
    this.reminders = reminders;
    this.stops = stops;
  }

  @Transactional
  public Map<String, Object> me(String bearer) {
    UUID user = identity.require(bearer);
    return Map.of(
        "userId",
        user,
        "displayName",
        db.queryForObject("SELECT display_name FROM app.app_users WHERE id=?", String.class, user),
        "serverTime",
        clock.instant());
  }

  @Transactional
  public Map<String, Object> create(String bearer, String key, Plan plan) {
    UUID user = identity.require(bearer);
    validatePlan(plan);
    return idempotency.execute(
        user.toString(),
        "POST /trips",
        key,
        plan,
        false,
        () -> {
          Instant expires = expiry(plan);
          UUID trip = UUID.randomUUID();
          db.update(
              """
              INSERT INTO app.trips(id,owner_user_id,title,destination_name,destination_latitude,
              destination_longitude,deadline_at,arrival_basis,planned_stay_minutes,time_zone,status,
              starts_at,expires_at) VALUES (?,?,?,?,?,?,?,?,?,?,'PLANNED',?,?)
              """,
              trip,
              user,
              plan.title(),
              plan.destination().name().strip(),
              plan.destination().latitude(),
              plan.destination().longitude(),
              timestamp(plan.deadlineAt()),
              basis(plan),
              plan.plannedStayMinutes(),
              plan.timeZone(),
              timestamp(plan.startsAt()),
              timestamp(expires));
          db.update(
              """
              INSERT INTO app.trip_participants(id,trip_id,user_id,role,status,display_name)
              VALUES (?,?,?,?,'ACTIVE',(SELECT display_name FROM app.app_users WHERE id=?))
              """,
              UUID.randomUUID(),
              trip,
              user,
              plan.ownerRole() == null ? "DRIVER" : plan.ownerRole(),
              user);
          return snapshot(access.require(trip, bearer, TripAccess.Permission.READ));
        });
  }

  @Transactional
  public Map<String, Object> get(UUID trip, String bearer) {
    return snapshot(access.require(trip, bearer, TripAccess.Permission.READ));
  }

  @Transactional
  public Map<String, Object> list(String bearer, String status) {
    UUID user = identity.require(bearer);
    if (status != null
        && !Set.of("PLANNED", "ACTIVE", "ENDED", "CANCELLED", "EXPIRED").contains(status))
      throw ApiException.invalid("INVALID_STATUS", "Unknown trip status.");
    // Minimal membership list excludes locations and other people's identities.
    var rows =
        db.queryForList(
            """
            SELECT t.id,t.title,t.status,t.destination_name,t.plan_revision,t.expires_at
            FROM app.trips t JOIN app.trip_participants p ON p.trip_id=t.id
            WHERE p.user_id=? AND p.status='ACTIVE' AND (CAST(? AS text) IS NULL OR (CASE WHEN t.status IN ('PLANNED','ACTIVE') AND t.expires_at<=CURRENT_TIMESTAMP THEN 'EXPIRED' ELSE t.status END)=?)
            AND (t.owner_user_id=p.user_id OR (t.status IN ('PLANNED','ACTIVE') AND t.expires_at>CURRENT_TIMESTAMP
              AND EXISTS(SELECT 1 FROM app.sharing_sessions s JOIN app.participant_consents c
                ON c.sharing_session_id=s.id WHERE s.trip_id=t.id AND s.status='ACTIVE'
                AND s.expires_at>CURRENT_TIMESTAMP AND c.participant_id=p.id
                AND c.scope='TRIP_COLLABORATION' AND c.revoked_at IS NULL)))
            ORDER BY t.created_at DESC LIMIT 100
            """,
            user,
            status,
            status);
    return Map.of(
        "trips", rows.stream().map(this::listItem).toList(), "serverTime", clock.instant());
  }

  @Transactional
  public Map<String, Object> update(UUID trip, String bearer, String etag, Plan plan) {
    var ctx = access.require(trip, bearer, TripAccess.Permission.OWNER);
    requireLive(ctx);
    access.checkPlan(ctx, etag);
    validatePlan(plan);
    if (plan.ownerRole() != null)
      throw ApiException.invalid("ROLE_IMMUTABLE", "Plan edits cannot change roles.");
    Instant expires = plan.expiresAt() == null ? ctx.expiresAt() : expiry(plan);
    validateExpiry(plan, expires);
    db.update(
        """
        UPDATE app.trips SET title=?,destination_name=?,destination_latitude=?,destination_longitude=?,
        deadline_at=?,arrival_basis=?,planned_stay_minutes=?,time_zone=?,starts_at=?,expires_at=? WHERE id=?
        """,
        plan.title(),
        plan.destination().name().strip(),
        plan.destination().latitude(),
        plan.destination().longitude(),
        timestamp(plan.deadlineAt()),
        basis(plan),
        plan.plannedStayMinutes(),
        plan.timeZone(),
        timestamp(plan.startsAt()),
        timestamp(expires),
        trip);
    access.bump(trip, true);
    reminders.invalidateForPlanChange(trip);
    return snapshot(access.require(trip, bearer, TripAccess.Permission.READ));
  }

  @Transactional
  public Map<String, Object> transition(
      UUID trip, String bearer, String key, String action, Transition body) {
    var permission =
        switch (action) {
          case "start" -> TripAccess.Permission.OWNER_OR_DRIVER;
          case "end" -> TripAccess.Permission.TRIP_END;
          default -> TripAccess.Permission.OWNER;
        };
    var ctx = access.require(trip, bearer, permission);
    String principal = ctx.vehicle() ? ctx.pairingId().toString() : ctx.userId().toString();
    return idempotency.execute(
        principal,
        "POST /trips/" + trip + "/" + action,
        key,
        body,
        false,
        () -> {
          requireLive(ctx);
          Long version =
              db.queryForObject("SELECT version FROM app.trips WHERE id=?", Long.class, trip);
          if (version.longValue() != body.expectedVersion().longValue())
            throw ApiException.conflict("VERSION_CONFLICT", "Refresh trip version.");
          if (action.equals("start")) {
            if (!ctx.status().equals("PLANNED"))
              throw ApiException.conflict("INVALID_TRANSITION", "Only planned trips can start.");
            db.update(
                "UPDATE app.trips SET status='ACTIVE',phase='DRIVING',started_at=? WHERE id=?",
                timestamp(clock.instant()),
                trip);
          } else {
            if (action.equals("end") && !ctx.status().equals("ACTIVE"))
              throw ApiException.conflict(
                  "INVALID_TRANSITION", "Only active trips can end; cancel a planned trip.");
            sharing.revokeAll(trip, "ENDED");
            db.update(
                "UPDATE app.trips SET status=?,ended_at=? WHERE id=?",
                action.equals("end") ? "ENDED" : "CANCELLED",
                timestamp(clock.instant()),
                trip);
          }
          access.bump(trip, false);
          return Map.of(
              "id",
              trip,
              "status",
              action.equals("start") ? "ACTIVE" : action.equals("end") ? "ENDED" : "CANCELLED",
              "version",
              version + 1);
        });
  }

  private Map<String, Object> snapshot(TripAccess.Context ctx) {
    var row = db.queryForMap("SELECT * FROM app.trips WHERE id=?", ctx.tripId());
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("id", ctx.tripId());
    result.put(
        "viewer",
        Map.of(
            "participantId",
            ctx.participantId(),
            "role",
            ctx.role(),
            "isOwner",
            ctx.owner(),
            "clientType",
            ctx.vehicle() ? "VEHICLE" : "PHONE"));
    result.put("status", effectiveStatus(ctx.status(), ctx.expiresAt()));
    result.put("phase", ctx.phase());
    result.put("revision", ctx.revision());
    result.put("planRevision", ctx.planRevision());
    result.put("version", row.get("version"));
    result.put("title", row.get("title"));
    Map<String, Object> place = new LinkedHashMap<>();
    place.put("name", row.get("destination_name"));
    place.put("latitude", row.get("destination_latitude"));
    place.put("longitude", row.get("destination_longitude"));
    result.put("destination", place);
    result.put("deadlineAt", ctx.deadlineAt());
    result.put("arrivalBasis", ctx.arrivalBasis());
    result.put("plannedStayMinutes", ctx.plannedStayMinutes());
    result.put("timeZone", ctx.timeZone());
    result.put("expiresAt", ctx.expiresAt());
    result.put("stops", stops.read(ctx));
    result.put("sharing", sharingProjection(ctx));
    result.put("location", ctx.sharingSessionId() == null ? null : locations.visible(ctx));
    // Participant identities/readiness are visible only while collaboration consent is live.
    if (ctx.sharingSessionId() != null
        && access.consent(ctx.sharingSessionId(), ctx.participantId(), "TRIP_COLLABORATION"))
      result.put(
          "participants",
          db
              .queryForList(
                  "SELECT p.id,p.role,p.display_name,p.ready,p.ready_updated_at,p.consent_version"
                      + " FROM app.trip_participants p WHERE p.trip_id=? AND p.status='ACTIVE' AND"
                      + " EXISTS(SELECT 1 FROM app.participant_consents c WHERE"
                      + " c.participant_id=p.id AND c.sharing_session_id=? AND"
                      + " c.scope='TRIP_COLLABORATION' AND c.revoked_at IS NULL)",
                  ctx.tripId(),
                  ctx.sharingSessionId())
              .stream()
              .map(this::participantProjection)
              .toList());
    else result.put("participants", List.of());
    result.put(
        "capabilities",
        Map.of("navigation", Map.of("availability", "UNAVAILABLE", "reason", "SDK_NOT_CONNECTED")));
    result.put("serverTime", clock.instant());
    return result;
  }

  private String effectiveStatus(String status, Instant expiresAt) {
    return Set.of("PLANNED", "ACTIVE").contains(status) && !expiresAt.isAfter(clock.instant())
        ? "EXPIRED"
        : status;
  }

  private Map<String, Object> listItem(Map<String, Object> row) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", row.get("id"));
    out.put("title", row.get("title"));
    out.put(
        "status",
        effectiveStatus((String) row.get("status"), TripAccess.instant(row.get("expires_at"))));
    out.put("destinationName", row.get("destination_name"));
    out.put("planRevision", row.get("plan_revision"));
    out.put("expiresAt", TripAccess.instant(row.get("expires_at")));
    return out;
  }

  private Map<String, Object> participantProjection(Map<String, Object> row) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", row.get("id"));
    out.put("role", row.get("role"));
    out.put("displayName", row.get("display_name"));
    out.put("ready", row.get("ready"));
    out.put("readyUpdatedAt", TripAccess.instant(row.get("ready_updated_at")));
    out.put("consentVersion", row.get("consent_version"));
    return out;
  }

  private Map<String, Object> sharingProjection(TripAccess.Context ctx) {
    var current = sharing.currentSession(ctx.tripId());
    if (ctx.sharingSessionId() == null || current == null) return null;
    return Map.of(
        "sessionId",
        current.get("id"),
        "generation",
        current.get("generation"),
        "expiresAt",
        TripAccess.instant(current.get("expires_at")));
  }

  private void requireLive(TripAccess.Context ctx) {
    if (!Set.of("PLANNED", "ACTIVE").contains(ctx.status())
        || !ctx.expiresAt().isAfter(clock.instant()))
      throw ApiException.conflict("TRIP_INACTIVE", "Trip has ended or expired.");
  }

  private void validatePlan(Plan plan) {
    try {
      ZoneId.of(plan.timeZone());
    } catch (DateTimeException ex) {
      throw ApiException.invalid("INVALID_TIME_ZONE", "Use an IANA time zone.");
    }
    Place place = plan.destination();
    if ((place.latitude() == null) != (place.longitude() == null)
        || (place.latitude() != null
            && (!Double.isFinite(place.latitude()) || !Double.isFinite(place.longitude()))))
      throw ApiException.invalid("INVALID_COORDINATES", "Coordinates must be a finite pair.");
  }

  private Instant planAnchor(Plan plan) {
    Instant anchor = clock.instant();
    if (plan.startsAt() != null && plan.startsAt().isAfter(anchor)) anchor = plan.startsAt();
    if (plan.deadlineAt() != null && plan.deadlineAt().isAfter(anchor)) anchor = plan.deadlineAt();
    return anchor;
  }

  private long stayMinutes(Plan plan) {
    return plan.plannedStayMinutes() == null ? 0 : plan.plannedStayMinutes();
  }

  private void validateExpiry(Plan plan, Instant expires) {
    Instant now = clock.instant();
    Instant coveredThrough = planAnchor(plan).plus(Duration.ofMinutes(stayMinutes(plan)));
    if (!expires.isAfter(now)
        || expires.isAfter(now.plus(Duration.ofDays(30)))
        || expires.isBefore(coveredThrough))
      throw ApiException.invalid(
          "INVALID_EXPIRY",
          "Expiry must cover start/deadline plus planned stay and be within 30 days.");
  }

  private Instant expiry(Plan plan) {
    Instant expires =
        plan.expiresAt() == null
            ? planAnchor(plan).plus(Duration.ofDays(1)).plus(Duration.ofMinutes(stayMinutes(plan)))
            : plan.expiresAt();
    validateExpiry(plan, expires);
    return expires;
  }

  private String basis(Plan plan) {
    return plan.arrivalBasis() == null ? "PERSON" : plan.arrivalBasis();
  }

  private java.sql.Timestamp timestamp(Instant time) {
    return time == null ? null : java.sql.Timestamp.from(time);
  }
}
