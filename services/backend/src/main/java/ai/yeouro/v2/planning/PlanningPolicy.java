package ai.yeouro.v2.planning;

import static ai.yeouro.v2.planning.PlanningModels.*;

import ai.yeouro.api.ApiException;
import ai.yeouro.v2.trip.TripAccess.Context;
import java.time.Instant;
import org.springframework.http.HttpStatus;

final class PlanningPolicy {
  private PlanningPolicy() {}

  static void place(Place p) {
    if ((p.latitude() == null) != (p.longitude() == null)
        || p.latitude() != null
            && (!Double.isFinite(p.latitude()) || !Double.isFinite(p.longitude())))
      throw ApiException.invalid("INVALID_COORDINATES", "Coordinates must be a finite pair.");
  }

  static void observed(Instant value, Instant now) {
    if (value.isAfter(now.plusSeconds(30)) || value.isBefore(now.minusSeconds(86400)))
      throw ApiException.invalid(
          "INVALID_OBSERVED_AT", "Observation must be within the past day and allowed clock skew.");
  }

  static void active(Context c) {
    if (!"ACTIVE".equals(c.status()))
      throw ApiException.conflict("TRIP_NOT_ACTIVE", "Start the trip before recording progress.");
  }

  static void version(Context c, long expected) {
    if (c.revision() != expected)
      throw ApiException.conflict("VERSION_CONFLICT", "Refresh the current trip revision.");
  }

  static String principal(Context c) {
    return (c.vehicle() ? c.pairingId() : c.userId()).toString();
  }

  static void confirmation(Source source, boolean confirmed) {
    if (!confirmed || source != Source.USER_CONFIRMED)
      throw ApiException.invalid(
          "USER_CONFIRMATION_REQUIRED",
          "Navigation arrival and simulated data are not actual parking or person-arrival"
              + " confirmation.");
  }

  static ApiException missing() {
    return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Planning resource not found.");
  }
}
