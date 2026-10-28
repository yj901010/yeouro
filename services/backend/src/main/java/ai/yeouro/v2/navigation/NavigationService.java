package ai.yeouro.v2.navigation;

import ai.yeouro.api.ApiException;
import ai.yeouro.v2.common.IdempotencyService;
import ai.yeouro.v2.trip.TripAccess;
import ai.yeouro.v2.trip.TripAccess.Context;
import ai.yeouro.v2.trip.TripAccess.Permission;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "yeouro.v2.enabled", havingValue = "true")
public class NavigationService {
  private final JdbcTemplate db;
  private final TripAccess access;
  private final IdempotencyService idem;
  private final Clock clock;
  private final boolean demoEnabled;

  public NavigationService(
      JdbcTemplate db,
      TripAccess access,
      IdempotencyService idem,
      Clock clock,
      @Value("${yeouro.v2.demo-enabled:false}") boolean demoEnabled) {
    this.db = db;
    this.access = access;
    this.idem = idem;
    this.clock = clock;
    this.demoEnabled = demoEnabled;
  }

  private String principal(Context c) {
    return (c.vehicle() ? c.pairingId() : c.userId()).toString();
  }

  private void active(Context c) {
    if (!"ACTIVE".equals(c.status()))
      throw ApiException.conflict("TRIP_NOT_ACTIVE", "Start the trip before navigation actions.");
  }

  private void session(Context c) {
    if (c.sharingSessionId() == null)
      throw ApiException.conflict("SHARING_REQUIRED", "An active sharing session is required.");
  }

  private void version(Map<String, Object> row, long expected) {
    if (((Number) row.get("version")).longValue() != expected)
      throw ApiException.conflict(
          "VERSION_CONFLICT", "Refresh the current entity before deciding.");
  }

  private Map<String, Object> one(String sql, Object... args) {
    var rows = db.queryForList(sql, args);
    if (rows.isEmpty())
      throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource is not accessible.");
    return rows.getFirst();
  }

  private Instant instant(Object value) {
    return value instanceof Timestamp t
        ? t.toInstant()
        : ((java.time.OffsetDateTime) value).toInstant();
  }

  private void place(NavigationController.Place p) {
    if ((p.latitude() == null) != (p.longitude() == null)
        || p.latitude() != null
            && (!Double.isFinite(p.latitude())
                || !Double.isFinite(p.longitude())
                || p.latitude() < -90
                || p.latitude() > 90
                || p.longitude() < -180
                || p.longitude() > 180))
      throw ApiException.invalid("INVALID_PLACE", "Coordinates must be a finite, valid pair.");
  }

  private Map<String, Object> proposal(UUID trip, UUID id) {
    var r = one("SELECT * FROM app.place_proposals WHERE trip_id=? AND id=?", trip, id);
    var out = new LinkedHashMap<String, Object>();
    out.put("id", r.get("id"));
    out.put("kind", r.get("kind"));
    out.put("decisionStatus", r.get("decision_status"));
    out.put("version", r.get("version"));
    var p = new LinkedHashMap<String, Object>();
    p.put("name", r.get("place_name"));
    p.put("latitude", r.get("latitude"));
    p.put("longitude", r.get("longitude"));
    out.put("place", p);
    return out;
  }

  private Map<String, Object> command(Map<String, Object> r) {
    var out = new LinkedHashMap<String, Object>();
    for (var field :
        Map.of(
                "id",
                "id",
                "status",
                "status",
                "version",
                "version",
                "commandType",
                "command_type",
                "integrationMode",
                "integration_mode",
                "reason",
                "result_code")
            .entrySet()) out.put(field.getKey(), r.get(field.getValue()));
    out.put("expiresAt", instant(r.get("expires_at")));
    var p = new LinkedHashMap<String, Object>();
    p.put("name", r.get("target_name"));
    p.put("latitude", r.get("target_latitude"));
    p.put("longitude", r.get("target_longitude"));
    out.put("target", p);
    return out;
  }

  private Map<String, Object> command(UUID trip, UUID id) {
    return command(one("SELECT * FROM app.navigation_commands WHERE trip_id=? AND id=?", trip, id));
  }

  /** Foreground polling/reconnect discovery, restricted to the current consent generation. */
  @Transactional
  public Map<String, Object> proposals(
      UUID trip, String bearer, int limit, UUID cursor, String status) {
    Context context = access.require(trip, bearer, Permission.READ);
    access.requireLive(context);
    session(context);
    if (!access.consent(context.sharingSessionId(), context.participantId(), "TRIP_COLLABORATION"))
      throw ApiException.forbidden();
    if (limit < 1 || limit > 100)
      throw ApiException.invalid("INVALID_LIMIT", "Limit must be between 1 and 100.");
    if (status != null && !Set.of("PENDING", "ACCEPTED", "DISMISSED", "CANCELLED").contains(status))
      throw ApiException.invalid("INVALID_STATUS", "Unknown proposal decision status.");
    expire(trip);
    String query = "SELECT id FROM app.place_proposals WHERE trip_id=? AND sharing_session_id=?";
    List<Object> parameters = new ArrayList<>(List.of(trip, context.sharingSessionId()));
    if (status != null) {
      query += " AND decision_status=?";
      parameters.add(status);
    }
    if (cursor != null) {
      var before =
          one(
              "SELECT created_at,id FROM app.place_proposals WHERE trip_id=? AND"
                  + " sharing_session_id=? AND id=?",
              trip,
              context.sharingSessionId(),
              cursor);
      query += " AND (created_at,id)<(?,?)";
      parameters.add(before.get("created_at"));
      parameters.add(cursor);
    }
    query += " ORDER BY created_at DESC,id DESC LIMIT ?";
    parameters.add(limit + 1);
    var ids = db.queryForList(query, parameters.toArray());
    boolean more = ids.size() > limit;
    List<Map<String, Object>> items = new ArrayList<>();
    for (var row : ids.subList(0, Math.min(limit, ids.size()))) {
      UUID proposalId = (UUID) row.get("id");
      Map<String, Object> item = new LinkedHashMap<>(proposal(trip, proposalId));
      var commands =
          db.queryForList(
              "SELECT * FROM app.navigation_commands WHERE trip_id=? AND sharing_session_id=? AND"
                  + " proposal_id=?",
              trip,
              context.sharingSessionId(),
              proposalId);
      item.put("navigationCommand", commands.isEmpty() ? null : command(commands.getFirst()));
      items.add(item);
    }
    Map<String, Object> response = new LinkedHashMap<>();
    response.put("proposals", items);
    response.put("nextCursor", more ? ids.get(limit - 1).get("id") : null);
    response.put("serverTime", clock.instant());
    return response;
  }

  // Every transition takes the same trip row lock as consent revocation and lifecycle changes.
  @Transactional
  public Map<String, Object> propose(
      UUID trip, String bearer, String key, NavigationController.Proposal body) {
    var c = access.require(trip, bearer, Permission.COLLABORATE);
    session(c);
    place(body.place());
    if (!Set.of("DESTINATION", "STOP", "PARKING").contains(body.kind()))
      throw ApiException.invalid("INVALID_KIND", "Unknown proposal kind.");
    return idem.execute(
        principal(c) + ":" + c.sharingSessionId(),
        "POST /trips/" + trip + "/proposals",
        key,
        body,
        false,
        () -> {
          UUID id = UUID.randomUUID();
          db.update(
              "INSERT INTO"
                  + " app.place_proposals(id,trip_id,sharing_session_id,proposer_participant_id,kind,place_name,latitude,longitude)"
                  + " VALUES (?,?,?,?,?,?,?,?)",
              id,
              trip,
              c.sharingSessionId(),
              c.participantId(),
              body.kind(),
              body.place().name(),
              body.place().latitude(),
              body.place().longitude());
          access.bump(trip, false);
          return proposal(trip, id);
        });
  }

  @Transactional
  public Map<String, Object> decide(
      UUID trip, UUID proposalId, String bearer, String key, NavigationController.Decision body) {
    var c = access.require(trip, bearer, Permission.PROPOSAL_DECIDE);
    if (!access.consent(c.sharingSessionId(), c.participantId(), "TRIP_COLLABORATION")) {
      throw ApiException.forbidden();
    }
    active(c);
    session(c);
    if (!Set.of("ACCEPT", "DISMISS").contains(body.decision()))
      throw ApiException.invalid("INVALID_DECISION", "Unknown decision.");
    String mode = body.mode() == null ? "PLEOS" : body.mode();
    if (!Set.of("PLEOS", "DEMO").contains(mode))
      throw ApiException.invalid("INVALID_MODE", "Choose PLEOS or explicit DEMO.");
    if ("DEMO".equals(mode) && !demoEnabled) {
      throw ApiException.forbidden();
    }
    // Authorize the resource generation before reading any stored response.
    var authorizedProposal =
        one(
            "SELECT * FROM app.place_proposals WHERE trip_id=? AND id=? AND sharing_session_id=?"
                + " FOR UPDATE",
            trip,
            proposalId,
            c.sharingSessionId());
    return idem.execute(
        principal(c),
        "POST /trips/" + trip + "/proposals/" + proposalId + "/decision",
        key,
        body,
        false,
        () -> {
          var p = authorizedProposal;
          version(p, body.expectedVersion());
          if (!"PENDING".equals(p.get("decision_status")))
            throw ApiException.conflict(
                "PROPOSAL_ALREADY_DECIDED", "The proposal already has a decision.");
          expire(trip);
          if ("ACCEPT".equals(body.decision())
              && db.queryForObject(
                      "SELECT count(*) FROM app.navigation_commands WHERE trip_id=? AND status IN"
                          + " ('REQUESTED','CLAIMED','OUTCOME_UNKNOWN')",
                      Integer.class,
                      trip)
                  > 0)
            throw ApiException.conflict(
                "COMMAND_IN_PROGRESS", "Resolve the current command before another approval.");
          db.update(
              "UPDATE app.place_proposals SET"
                  + " decision_status=?,decided_by_participant_id=?,decided_at=?,version=version+1,updated_at=?"
                  + " WHERE trip_id=? AND id=?",
              body.decision().equals("ACCEPT") ? "ACCEPTED" : "DISMISSED",
              c.participantId(),
              Timestamp.from(clock.instant()),
              Timestamp.from(clock.instant()),
              trip,
              proposalId);
          var out = new LinkedHashMap<String, Object>();
          out.put("proposal", proposal(trip, proposalId));
          if (body.decision().equals("ACCEPT")) {
            appendCommand(out, trip, proposalId, c, p, mode);
          }
          access.bump(trip, false);
          out.put(
              "tripRevision",
              db.queryForObject("SELECT revision FROM app.trips WHERE id=?", Long.class, trip));
          return out;
        });
  }

  private void appendCommand(
      Map<String, Object> out,
      UUID trip,
      UUID proposalId,
      Context c,
      Map<String, Object> p,
      String mode) {
    String type =
        switch ((String) p.get("kind")) {
          case "STOP" -> "ADD_STOP";
          case "PARKING" -> "SELECT_PARKING";
          default -> "SET_DESTINATION";
        };
    UUID pairing = c.pairingId();
    if (pairing == null) {
      var rows =
          db.queryForList(
              "SELECT id FROM app.vehicle_pairings WHERE trip_id=? AND sharing_session_id=?"
                  + " AND driver_participant_id=? AND status='CLAIMED' ORDER BY approved_at"
                  + " DESC",
              trip,
              c.sharingSessionId(),
              c.participantId());
      if (!rows.isEmpty()) pairing = (UUID) rows.getFirst().get("id");
    }
    String reason =
        !type.equals("SET_DESTINATION")
            ? "UNSUPPORTED_COMMAND"
            : p.get("latitude") == null
                ? "COORDINATES_REQUIRED"
                : mode.equals("PLEOS")
                    ? "SDK_NOT_CONNECTED"
                    : pairing == null ? "VEHICLE_NOT_PAIRED" : null;
    UUID id = UUID.randomUUID();
    Instant now = clock.instant();
    Instant expiry =
        now.plusSeconds(120).isBefore(c.expiresAt()) ? now.plusSeconds(120) : c.expiresAt();
    db.update(
        "INSERT INTO"
            + " app.navigation_commands(id,trip_id,sharing_session_id,proposal_id,target_pairing_id,requested_by_participant_id,command_type,target_name,target_latitude,target_longitude,status,integration_mode,requested_at,expires_at,completed_at,result_code)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        id,
        trip,
        c.sharingSessionId(),
        proposalId,
        pairing,
        c.participantId(),
        type,
        p.get("place_name"),
        p.get("latitude"),
        p.get("longitude"),
        reason == null ? "REQUESTED" : "FAILED",
        mode,
        Timestamp.from(now),
        Timestamp.from(expiry),
        reason == null ? null : Timestamp.from(now),
        reason);
    out.put("navigationCommand", command(trip, id));
    out.put(
        "navigation",
        Map.of(
            "availability",
            mode.equals("DEMO") ? "SIMULATED" : "UNAVAILABLE",
            "reason",
            reason == null ? "DEMO_ONLY" : reason));
  }

  private void expire(UUID trip) {
    int n =
        db.update(
            "UPDATE app.navigation_commands SET status=CASE WHEN status='REQUESTED' THEN 'EXPIRED'"
                + " ELSE 'OUTCOME_UNKNOWN'"
                + " END,result_code='COMMAND_LEASE_EXPIRED',version=version+1 WHERE trip_id=? AND"
                + " status IN ('REQUESTED','CLAIMED') AND expires_at<=?",
            trip,
            Timestamp.from(clock.instant()));
    if (n > 0) access.bump(trip, false);
  }

  @Transactional
  public Map<String, Object> commands(UUID trip, String bearer, String status) {
    var c = access.require(trip, bearer, Permission.NAV_COMMAND_CLAIM);
    active(c);
    if (!c.vehicle()) throw ApiException.forbidden();
    if (!Set.of(
            "REQUESTED",
            "CLAIMED",
            "DEMO_APPLIED",
            "FAILED",
            "CANCELLED",
            "EXPIRED",
            "OUTCOME_UNKNOWN")
        .contains(status)) throw ApiException.invalid("INVALID_STATUS", "Unknown command filter.");
    expire(trip);
    return Map.of(
        "commands",
        db
            .queryForList(
                "SELECT * FROM app.navigation_commands WHERE trip_id=? AND sharing_session_id=? AND"
                    + " target_pairing_id=? AND status=? ORDER BY requested_at",
                trip,
                c.sharingSessionId(),
                c.pairingId(),
                status)
            .stream()
            .map(this::command)
            .toList());
  }

  private Map<String, Object> targeted(UUID trip, UUID id, Context c) {
    if (!c.vehicle()) throw ApiException.forbidden();
    return one(
        "SELECT * FROM app.navigation_commands WHERE trip_id=? AND id=? AND sharing_session_id=?"
            + " AND target_pairing_id=? FOR UPDATE",
        trip,
        id,
        c.sharingSessionId(),
        c.pairingId());
  }

  @Transactional
  public Map<String, Object> claim(
      UUID trip, UUID id, String bearer, String key, NavigationController.Claim body) {
    var c = access.require(trip, bearer, Permission.NAV_COMMAND_CLAIM);
    active(c);
    targeted(trip, id, c);
    if (!demoEnabled) throw ApiException.forbidden();
    return idem.execute(
        principal(c),
        "POST /trips/" + trip + "/navigation-commands/" + id + "/claim",
        key,
        body,
        false,
        () -> {
          var r = targeted(trip, id, c);
          version(r, body.expectedVersion());
          if (!"REQUESTED".equals(r.get("status")))
            throw ApiException.conflict(
                "COMMAND_NOT_CLAIMABLE", "Command cannot be claimed again.");
          if (!clock.instant().isBefore(instant(r.get("expires_at"))))
            throw ApiException.conflict("COMMAND_EXPIRED", "Command has expired.");
          if (!"DEMO".equals(r.get("integration_mode")))
            throw ApiException.conflict("SDK_NOT_CONNECTED", "Real SDK execution is unavailable.");
          db.update(
              "UPDATE app.navigation_commands SET status='CLAIMED',claimed_at=?,version=version+1"
                  + " WHERE trip_id=? AND id=?",
              Timestamp.from(clock.instant()),
              trip,
              id);
          access.bump(trip, false);
          return command(trip, id);
        });
  }

  @Transactional
  public Map<String, Object> result(
      UUID trip, UUID id, String bearer, String key, NavigationController.Result body) {
    var c = access.require(trip, bearer, Permission.NAV_RESULT_WRITE);
    active(c);
    targeted(trip, id, c);
    if (!Set.of("DEMO_APPLIED", "FAILED").contains(body.outcome()))
      throw ApiException.invalid(
          "SDK_NOT_CONNECTED", "Real APPLIED cannot be reported without a verified SDK adapter.");
    return idem.execute(
        principal(c),
        "POST /trips/" + trip + "/navigation-commands/" + id + "/result",
        key,
        body,
        false,
        () -> {
          var r = targeted(trip, id, c);
          version(r, body.expectedVersion());
          if (!"CLAIMED".equals(r.get("status")))
            throw ApiException.conflict(
                "COMMAND_NOT_CLAIMED", "Command is cancelled, terminal, or not claimed.");
          if (!clock.instant().isBefore(instant(r.get("expires_at"))))
            throw ApiException.conflict(
                "OUTCOME_UNKNOWN", "Execution lease elapsed; do not replay the SDK command.");
          if (!"DEMO".equals(r.get("integration_mode")))
            throw ApiException.conflict("SDK_NOT_CONNECTED", "Real SDK results are unavailable.");
          db.update(
              "UPDATE app.navigation_commands SET"
                  + " status=?,result_code=?,completed_at=?,version=version+1 WHERE trip_id=? AND"
                  + " id=?",
              body.outcome(),
              body.outcome().equals("DEMO_APPLIED") ? "SIMULATED_ONLY" : "ADAPTER_REPORTED_FAILURE",
              Timestamp.from(clock.instant()),
              trip,
              id);
          access.bump(trip, false);
          return command(trip, id);
        });
  }
}
