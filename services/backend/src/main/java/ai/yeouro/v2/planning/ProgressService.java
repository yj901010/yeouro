package ai.yeouro.v2.planning;

import static ai.yeouro.v2.planning.PlanningModels.*;
import static ai.yeouro.v2.planning.PlanningStore.*;

import ai.yeouro.api.ApiException;
import ai.yeouro.v2.common.IdempotencyService;
import ai.yeouro.v2.trip.TripAccess;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "yeouro.v2.enabled", havingValue = "true")
public class ProgressService {
  private final Clock clock;
  private final TripAccess access;
  private final PlanningStore store;
  private final IdempotencyService idem;
  private final ReminderService reminders;

  public ProgressService(
      TripAccess access,
      PlanningStore store,
      IdempotencyService idem,
      ReminderService reminders,
      Clock clock) {
    this.clock = clock;
    this.access = access;
    this.store = store;
    this.idem = idem;
    this.reminders = reminders;
  }

  @Transactional
  public Map<String, Object> parking(UUID trip, String bearer, String key, ParkingRequest request) {
    var c = access.require(trip, bearer, TripAccess.Permission.PARKING_CONFIRM);
    PlanningPolicy.active(c);
    return idem.execute(
        PlanningPolicy.principal(c),
        "POST /trips/" + trip + "/parking-confirmations",
        key,
        request,
        false,
        () -> {
          PlanningPolicy.version(c, request.expectedVersion());
          PlanningPolicy.confirmation(request.source(), request.userConfirmed());
          PlanningPolicy.place(request.place());
          checkTime(trip, request.parkedAt());
          transition(c.phase(), Phase.PARKED);
          UUID id =
              milestone(
                  trip,
                  c.participantId(),
                  "PARKING_CONFIRMED",
                  request.parkedAt(),
                  request.source(),
                  request.place());
          recordPhase(
              trip, c.participantId(), Phase.PARKED, request.parkedAt(), request.source(), true);
          access.changePhase(trip, "PARKED");
          reminders.recalculate(trip);
          return map(
              "id",
              id,
              "phase",
              "PARKED",
              "parkedAt",
              request.parkedAt(),
              "source",
              request.source(),
              "userConfirmed",
              true,
              "revision",
              c.revision() + 1);
        });
  }

  @Transactional
  public Map<String, Object> phase(UUID trip, String bearer, String key, PhaseRequest request) {
    var c = access.require(trip, bearer, TripAccess.Permission.TRIP_PHASE);
    PlanningPolicy.active(c);
    return idem.execute(
        PlanningPolicy.principal(c),
        "POST /trips/" + trip + "/phase-events",
        key,
        request,
        false,
        () -> {
          PlanningPolicy.version(c, request.expectedVersion());
          checkTime(trip, request.observedAt());
          if (request.phase() == Phase.PARKED)
            throw ApiException.invalid(
                "PARKING_CONFIRMATION_REQUIRED",
                "Use parking-confirmations to confirm actual parking.");
          PlanningPolicy.confirmation(request.source(), request.userConfirmed());
          transition(c.phase(), request.phase());
          UUID id =
              recordPhase(
                  trip,
                  c.participantId(),
                  request.phase(),
                  request.observedAt(),
                  request.source(),
                  true);
          if (request.phase() == Phase.AT_DESTINATION)
            milestone(
                trip,
                c.participantId(),
                "PERSON_ARRIVED",
                request.observedAt(),
                request.source(),
                null);
          if (request.phase() == Phase.DRIVING && "RETURNING".equals(c.phase()))
            milestone(
                trip,
                c.participantId(),
                "RETURNED_TO_CAR",
                request.observedAt(),
                request.source(),
                null);
          access.changePhase(trip, request.phase().name());
          reminders.recalculate(trip);
          return map(
              "id",
              id,
              "phase",
              request.phase(),
              "observedAt",
              request.observedAt(),
              "source",
              request.source(),
              "userConfirmed",
              true,
              "revision",
              c.revision() + 1);
        });
  }

  static void transition(String from, Phase to) {
    boolean allowed =
        from == null
            ? to == Phase.DRIVING
            : switch (from) {
              case "DRIVING" -> to == Phase.PARKED;
              case "PARKED" ->
                  Set.of(Phase.WALKING, Phase.AT_DESTINATION, Phase.DRIVING).contains(to);
              case "WALKING" -> to == Phase.AT_DESTINATION || to == Phase.RETURNING;
              case "AT_DESTINATION" -> to == Phase.RETURNING;
              case "RETURNING" -> to == Phase.DRIVING;
              default -> false;
            };
    if (!allowed)
      throw ApiException.conflict(
          "INVALID_PHASE_TRANSITION", "This phase does not follow the current trip phase.");
  }

  private void checkTime(UUID trip, Instant observed) {
    PlanningPolicy.observed(observed, clock.instant());
    var times =
        store.query(
            "SELECT observed_at FROM app.trip_phase_events WHERE trip_id=? ORDER BY observed_at"
                + " DESC LIMIT 1",
            (r, n) -> r.getTimestamp(1).toInstant(),
            trip);
    if (!times.isEmpty()
        && observed.truncatedTo(java.time.temporal.ChronoUnit.MILLIS).isBefore(times.getFirst()))
      throw ApiException.conflict(
          "STALE_PHASE_EVENT", "Progress cannot precede the latest recorded phase.");
  }

  private UUID milestone(
      UUID trip, UUID participant, String type, Instant at, Source source, Place place) {
    UUID id = UUID.randomUUID();
    store.update(
        "INSERT INTO"
            + " app.trip_milestones(id,trip_id,type,occurred_at,recorded_at,reported_by_participant_id,source,place_json)"
            + " VALUES (?,?,?,?,?,?,?,?)",
        id,
        trip,
        type,
        time(at),
        time(clock.instant()),
        participant,
        source.name(),
        place == null ? null : store.json(place));
    return id;
  }

  private UUID recordPhase(
      UUID trip, UUID participant, Phase phase, Instant at, Source source, boolean confirmed) {
    UUID id = UUID.randomUUID();
    store.update(
        "INSERT INTO"
            + " app.trip_phase_events(id,trip_id,phase,observed_at,recorded_at,source,reported_by_participant_id,user_confirmed)"
            + " VALUES (?,?,?,?,?,?,?,?)",
        id,
        trip,
        phase.name(),
        time(at),
        time(clock.instant()),
        source.name(),
        participant,
        confirmed);
    return id;
  }
}
