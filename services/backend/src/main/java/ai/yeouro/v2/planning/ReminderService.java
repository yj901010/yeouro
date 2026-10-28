package ai.yeouro.v2.planning;

import static ai.yeouro.v2.planning.PlanningModels.*;
import static ai.yeouro.v2.planning.PlanningStore.*;

import ai.yeouro.api.ApiException;
import ai.yeouro.v2.trip.TripAccess;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "yeouro.v2.enabled", havingValue = "true")
public class ReminderService {
  private final Clock clock;
  private final TripAccess access;
  private final PlanningStore store;

  public ReminderService(TripAccess access, PlanningStore store, Clock clock) {
    this.clock = clock;
    this.access = access;
    this.store = store;
  }

  record Plan(
      UUID id,
      Anchor anchor,
      int stay,
      int lead,
      ReminderBasis basis,
      Integer walk,
      Instant remind,
      String status,
      long version) {}

  Plan plan(UUID trip) {
    var rows =
        store.query(
            "SELECT * FROM app.return_reminder_plans WHERE trip_id=?",
            (r, n) ->
                new Plan(
                    r.getObject("id", UUID.class),
                    Anchor.valueOf(r.getString("anchor")),
                    r.getInt("stay_minutes"),
                    r.getInt("lead_minutes"),
                    ReminderBasis.valueOf(r.getString("reminder_basis")),
                    (Integer) r.getObject("return_walk_seconds"),
                    r.getTimestamp("remind_at") == null
                        ? null
                        : r.getTimestamp("remind_at").toInstant(),
                    r.getString("status"),
                    r.getLong("version")),
            trip);
    return rows.isEmpty() ? null : rows.getFirst();
  }

  @Transactional
  public Map<String, Object> put(
      UUID trip, String bearer, String ifMatch, ReminderRequest request) {
    var c = access.require(trip, bearer, TripAccess.Permission.OWNER_OR_DRIVER);
    access.checkPlan(c, ifMatch);
    Instant remind;
    try {
      remind =
          PlanningCalculators.reminder(
              store.anchor(trip, request.anchor()),
              request.stayMinutes(),
              request.leadMinutes(),
              request.reminderBasis(),
              request.returnWalkSeconds());
    } catch (IllegalArgumentException ex) {
      throw ApiException.invalid("INVALID_REMINDER", ex.getMessage());
    }
    Plan old = plan(trip);
    UUID id = old == null ? UUID.randomUUID() : old.id();
    long version = old == null ? 1 : old.version() + 1;
    String status =
        !request.enabled() ? "DISABLED" : remind == null ? "WAITING_FOR_ANCHOR" : "PLANNED";
    if (!request.enabled()) remind = null;
    if (old == null)
      store.update(
          "INSERT INTO"
              + " app.return_reminder_plans(id,trip_id,anchor,stay_minutes,lead_minutes,reminder_basis,return_walk_seconds,remind_at,status,version)"
              + " VALUES (?,?,?,?,?,?,?,?,?,?)",
          id,
          trip,
          request.anchor().name(),
          request.stayMinutes(),
          request.leadMinutes(),
          request.reminderBasis().name(),
          request.returnWalkSeconds(),
          time(remind),
          status,
          version);
    else
      store.update(
          "UPDATE app.return_reminder_plans SET"
              + " anchor=?,stay_minutes=?,lead_minutes=?,reminder_basis=?,return_walk_seconds=?,remind_at=?,status=?,version=?"
              + " WHERE trip_id=?",
          request.anchor().name(),
          request.stayMinutes(),
          request.leadMinutes(),
          request.reminderBasis().name(),
          request.returnWalkSeconds(),
          time(remind),
          status,
          version,
          trip);
    access.bump(trip, true);
    var result = view(trip, c.participantId());
    result.put("planRevision", c.planRevision() + 1);
    return result;
  }

  /** Caller must hold the trip lock; force clients to reschedule after any plan edit. */
  public void invalidateForPlanChange(UUID trip) {
    store.update("UPDATE app.return_reminder_plans SET version=version+1 WHERE trip_id=?", trip);
  }

  /** Called only under the same trip lock as the confirming milestone. */
  void recalculate(UUID trip) {
    Plan p = plan(trip);
    if (p == null || Set.of("DISABLED", "CANCELLED").contains(p.status())) return;
    Instant remind =
        PlanningCalculators.reminder(
            store.anchor(trip, p.anchor()), p.stay(), p.lead(), p.basis(), p.walk());
    if (!Objects.equals(remind, p.remind()))
      store.update(
          "UPDATE app.return_reminder_plans SET remind_at=?,status=?,version=version+1 WHERE"
              + " trip_id=?",
          time(remind),
          remind == null ? "WAITING_FOR_ANCHOR" : "PLANNED",
          trip);
  }

  @Transactional
  public Map<String, Object> get(UUID trip, String bearer) {
    var c = access.require(trip, bearer, TripAccess.Permission.READ);
    var result = view(trip, c.participantId());
    if (!Set.of("PLANNED", "ACTIVE").contains(c.status())
        || !c.expiresAt().isAfter(clock.instant())) {
      result.put("status", "CANCELLED");
      result.put("remindAt", null);
    }
    return result;
  }

  private Map<String, Object> view(UUID trip, UUID participant) {
    Plan p = plan(trip);
    if (p == null)
      return map(
          "status",
          "DISABLED",
          "version",
          0,
          "remindAt",
          null,
          "clientStatuses",
          List.of(),
          "deliveryGuarantee",
          "NONE",
          "schedulingMode",
          "CLIENT_LOCAL");
    var statuses =
        store.query(
            "SELECT installation_id,plan_version,status,observed_at FROM"
                + " app.reminder_client_statuses WHERE plan_id=? AND participant_id=?",
            (r, n) ->
                map(
                    "installationId",
                    r.getObject(1, UUID.class),
                    "planVersion",
                    r.getLong(2),
                    "status",
                    r.getString(3),
                    "observedAt",
                    r.getTimestamp(4).toInstant(),
                    "current",
                    r.getLong(2) == p.version()),
            p.id(),
            participant);
    return map(
        "id",
        p.id(),
        "anchor",
        p.anchor(),
        "anchorConfirmedAt",
        store.anchor(trip, p.anchor()),
        "stayMinutes",
        p.stay(),
        "leadMinutes",
        p.lead(),
        "reminderBasis",
        p.basis(),
        "returnWalkSeconds",
        p.walk(),
        "remindAt",
        p.remind(),
        "status",
        p.status(),
        "version",
        p.version(),
        "clientStatuses",
        statuses,
        "schedulingMode",
        "CLIENT_LOCAL",
        "deliveryGuarantee",
        "NONE");
  }

  @Transactional
  public Map<String, Object> clientStatus(UUID trip, String bearer, ClientStatusRequest request) {
    var c = access.require(trip, bearer, TripAccess.Permission.DRIVER);
    if (c.vehicle()) throw ApiException.forbidden();
    Plan p = plan(trip);
    if (p == null) throw PlanningPolicy.missing();
    if (request.planVersion() != p.version())
      throw ApiException.conflict("VERSION_CONFLICT", "Schedule the latest reminder plan.");
    if (request.status() == ClientStatus.SCHEDULED && !p.status().equals("PLANNED"))
      throw ApiException.conflict(
          "REMINDER_NOT_PLANNED", "No confirmed reminder time is available.");
    PlanningPolicy.observed(request.observedAt(), clock.instant());
    var prior =
        store.query(
            "SELECT observed_at FROM app.reminder_client_statuses WHERE plan_id=? AND"
                + " participant_id=? AND installation_id=?",
            (r, n) -> r.getTimestamp(1).toInstant(),
            p.id(),
            c.participantId(),
            request.installationId());
    if (!prior.isEmpty()
        && request
            .observedAt()
            .truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
            .isBefore(prior.getFirst()))
      throw ApiException.conflict(
          "STALE_STATUS", "Client scheduling observation is older than the stored observation.");
    store.update(
        "INSERT INTO"
            + " app.reminder_client_statuses(trip_id,plan_id,participant_id,installation_id,plan_version,status,observed_at)"
            + " VALUES (?,?,?,?,?,?,?) ON CONFLICT(plan_id,participant_id,installation_id) DO"
            + " UPDATE SET"
            + " plan_version=EXCLUDED.plan_version,status=EXCLUDED.status,observed_at=EXCLUDED.observed_at",
        trip,
        p.id(),
        c.participantId(),
        request.installationId(),
        p.version(),
        request.status().name(),
        time(request.observedAt()));
    access.bump(trip, false);
    return view(trip, c.participantId());
  }
}
