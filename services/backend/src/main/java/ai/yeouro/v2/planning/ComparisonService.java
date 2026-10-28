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
public class ComparisonService {
  private final PlanningRoutingProvider routing;
  private final Clock clock;
  private final TripAccess access;
  private final PlanningStore store;
  private final IdempotencyService idem;

  public ComparisonService(
      TripAccess access,
      PlanningStore store,
      IdempotencyService idem,
      Clock clock,
      PlanningRoutingProvider routing) {
    this.clock = clock;
    this.routing = routing;
    this.access = access;
    this.store = store;
    this.idem = idem;
  }

  @Transactional
  public Map<String, Object> create(
      UUID trip, String bearer, String key, ComparisonRequest request) {
    var c = access.require(trip, bearer, TripAccess.Permission.COLLABORATE);
    if (c.vehicle()) throw ApiException.forbidden();
    return idem.execute(
        PlanningPolicy.principal(c),
        "POST /trips/" + trip + "/comparisons",
        key,
        request,
        false,
        () -> {
          if (request.tripPlanRevision() != c.planRevision())
            throw ApiException.conflict(
                "PLAN_REVISION_CONFLICT", "Comparison must use the current plan.");
          Instant now = clock.instant();
          Origin origin = request.origin();
          if (!Double.isFinite(origin.latitude()) || !Double.isFinite(origin.longitude()))
            throw ApiException.invalid("INVALID_COORDINATES", "Coordinates must be finite.");
          if (origin.observedAt().isBefore(now.minusSeconds(120))
              || origin.observedAt().isAfter(now.plusSeconds(30)))
            throw ApiException.invalid("STALE_ORIGIN", "Use a fresh origin observation.");
          if (!Set.of("PHONE", "VEHICLE", "SIMULATED").contains(origin.source()))
            throw ApiException.invalid("INVALID_SOURCE", "Unsupported origin source.");
          var route = routing.route(request.mode());
          List<Stop> stops = store.stops(trip);
          Long intermediate = PlanningCalculators.intermediateSeconds(stops);
          var eta =
              PlanningCalculators.eta(
                  now,
                  route.drivingSeconds(),
                  intermediate,
                  route.parkingSearchSeconds(),
                  route.walkSeconds(),
                  c.deadlineAt(),
                  c.arrivalBasis());
          long staySeconds = stops.stream().mapToLong(s -> s.stayMinutes() * 60L).sum();
          List<Map<String, Object>> provenance = new ArrayList<>();
          for (String metric :
              List.of(
                  "drivingSeconds",
                  "parkingSearchSeconds",
                  "walkToDestinationSeconds",
                  "returnWalkSeconds",
                  "fee"))
            provenance.add(
                map(
                    "metric",
                    metric,
                    "provider",
                    "FIXTURE",
                    "source",
                    "SIMULATED",
                    "fetchedAt",
                    now,
                    "assumptions",
                    List.of(
                        "Synthetic demo fixture; not provider data",
                        "Fee: KRW 500 per started 10 minutes; no discounts or caps")));
          provenance.add(
              map(
                  "metric",
                  "intermediateSeconds",
                  "provider",
                  "USER_PLAN",
                  "source",
                  "CALCULATED",
                  "fetchedAt",
                  now,
                  "assumptions",
                  List.of(
                      "Each stop adds outbound walk + stay + independently specified return walk"
                          + " exactly once")));
          var candidate =
              map(
                  "candidateId",
                  "fixture-parking-a",
                  "vehicleArrivalAt",
                  eta.vehicleArrivalAt(),
                  "personArrivalAt",
                  eta.personArrivalAt(),
                  "drivingSeconds",
                  route.drivingSeconds(),
                  "parkingSearchSeconds",
                  route.parkingSearchSeconds(),
                  "walkToDestinationSeconds",
                  route.walkSeconds(),
                  "returnWalkSeconds",
                  route.returnWalkSeconds(),
                  "intermediateStaySeconds",
                  staySeconds,
                  "intermediateSeconds",
                  intermediate,
                  "slackSeconds",
                  eta.slackSeconds(),
                  "arrivalBasis",
                  c.arrivalBasis(),
                  "quality",
                  eta.quality(),
                  "fee",
                  map(
                      "estimatedAmount",
                      PlanningCalculators.demoFee(request.parkingDurationMinutes()),
                      "currency",
                      "KRW",
                      "pricingDurationMinutes",
                      request.parkingDurationMinutes(),
                      "rulesAsOf",
                      null,
                      "availability",
                      "ESTIMATED"),
                  "occupancy",
                  map("availability", "UNKNOWN", "availableSpaces", null, "observedAt", null),
                  "provenance",
                  provenance,
                  "warnings",
                  intermediate == null
                      ? List.of("SIMULATED_DATA", "OCCUPANCY_UNKNOWN", "STOP_WALK_UNKNOWN")
                      : List.of("SIMULATED_DATA", "OCCUPANCY_UNKNOWN"));
          UUID id = UUID.randomUUID();
          Instant expires =
              now.plusSeconds(300).isAfter(c.expiresAt()) ? c.expiresAt() : now.plusSeconds(300);
          var result =
              map(
                  "comparisonId",
                  id,
                  "mode",
                  "DEMO",
                  "tripPlanRevision",
                  c.planRevision(),
                  "calculatedAt",
                  now,
                  "expiresAt",
                  expires,
                  "candidates",
                  List.of(candidate));
          store.update(
              "INSERT INTO"
                  + " app.comparison_snapshots(id,trip_id,requested_by_participant_id,trip_plan_revision,mode,calculated_at,expires_at,snapshot_json)"
                  + " VALUES (?,?,?,?,?,?,?,?)",
              id,
              trip,
              c.participantId(),
              c.planRevision(),
              "DEMO",
              time(now),
              time(expires),
              store.json(result));
          return result;
        });
  }

  @Transactional
  public Map<String, Object> get(UUID trip, UUID id, String bearer) {
    var c = access.require(trip, bearer, TripAccess.Permission.READ);
    var rows =
        store.query(
            "SELECT snapshot_json,trip_plan_revision,expires_at FROM app.comparison_snapshots"
                + " WHERE trip_id=? AND id=?",
            (r, n) -> {
              var result =
                  map(
                      "snapshot",
                      store.object(r.getString(1)),
                      "stalePlan",
                      r.getLong(2) != c.planRevision(),
                      "expired",
                      !r.getTimestamp(3).toInstant().isAfter(clock.instant()));
              return result;
            },
            trip,
            id);
    if (rows.isEmpty()) throw PlanningPolicy.missing();
    return rows.getFirst();
  }
}
