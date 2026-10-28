package ai.yeouro.v2.planning;

import static ai.yeouro.v2.planning.PlanningModels.*;

import java.time.Duration;
import java.time.Instant;

/** Pure calculations: missing measurements propagate as unknown, never zero. */
public final class PlanningCalculators {
  private PlanningCalculators() {}

  public record Eta(
      Instant vehicleArrivalAt, Instant personArrivalAt, Long slackSeconds, String quality) {}

  public static Eta eta(
      Instant departure,
      Long driveSeconds,
      Long intermediateSeconds,
      Long parkingSeconds,
      Long walkSeconds,
      Instant deadline,
      String basis) {
    nonnegative(driveSeconds);
    nonnegative(intermediateSeconds);
    nonnegative(parkingSeconds);
    nonnegative(walkSeconds);
    Instant vehicle =
        driveSeconds == null || intermediateSeconds == null
            ? null
            : departure.plusSeconds(Math.addExact(driveSeconds, intermediateSeconds));
    Instant person =
        vehicle == null || parkingSeconds == null || walkSeconds == null
            ? null
            : vehicle.plusSeconds(Math.addExact(parkingSeconds, walkSeconds));
    if (!"PERSON".equals(basis) && !"VEHICLE".equals(basis))
      throw new IllegalArgumentException("Unknown arrival basis");
    Instant selected = "PERSON".equals(basis) ? person : vehicle;
    return new Eta(
        vehicle,
        person,
        deadline == null || selected == null
            ? null
            : Duration.between(selected, deadline).getSeconds(),
        person != null ? "COMPLETE" : vehicle != null ? "PARTIAL" : "UNAVAILABLE");
  }

  public static Long intermediateSeconds(java.util.List<Stop> stops) {
    long total = 0;
    for (Stop s : stops) {
      if (s.walkToPlaceSeconds() == null || s.returnWalkSeconds() == null) return null;
      total =
          Math.addExact(
              total, s.stayMinutes() * 60L + s.walkToPlaceSeconds() + s.returnWalkSeconds());
    }
    return total;
  }

  /** Deliberately synthetic fixture tariff: KRW 500 per started ten minutes. */
  public static long demoFee(int durationMinutes) {
    if (durationMinutes <= 0) throw new IllegalArgumentException("Duration must be positive");
    return ((durationMinutes + 9L) / 10L) * 500L;
  }

  public static Instant reminder(
      Instant anchor,
      int stayMinutes,
      int leadMinutes,
      ReminderBasis basis,
      Integer returnWalkSeconds) {
    if (stayMinutes <= 0 || leadMinutes < 0 || leadMinutes > stayMinutes)
      throw new IllegalArgumentException("Invalid reminder duration");
    if (basis == ReminderBasis.RETURN_TO_CAR && returnWalkSeconds == null)
      throw new IllegalArgumentException("Return walk is required");
    if (returnWalkSeconds != null && returnWalkSeconds < 0)
      throw new IllegalArgumentException("Negative walk");
    long offset =
        (stayMinutes - leadMinutes) * 60L
            - (basis == ReminderBasis.RETURN_TO_CAR ? returnWalkSeconds : 0);
    if (offset < 0) throw new IllegalArgumentException("Reminder precedes its anchor");
    return anchor == null ? null : anchor.plusSeconds(offset);
  }

  private static void nonnegative(Long value) {
    if (value != null && value < 0) throw new IllegalArgumentException("Negative duration");
  }
}
