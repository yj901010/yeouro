package ai.yeouro.v2.planning;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class PlanningModels {
  private PlanningModels() {}

  public enum Mode {
    REAL,
    DEMO
  }

  public enum Source {
    USER_CONFIRMED,
    SDK_EVENT,
    SIMULATED
  }

  public enum Phase {
    DRIVING,
    PARKED,
    WALKING,
    AT_DESTINATION,
    RETURNING
  }

  public enum Anchor {
    PARKING_CONFIRMED,
    PERSON_ARRIVAL_CONFIRMED
  }

  public enum ReminderBasis {
    STAY_END,
    RETURN_TO_CAR
  }

  public enum ClientStatus {
    SCHEDULED,
    PERMISSION_DENIED,
    CANCELLED,
    ERROR
  }

  public record Place(
      @NotBlank @Size(max = 160) String name,
      @DecimalMin("-90") @DecimalMax("90") Double latitude,
      @DecimalMin("-180") @DecimalMax("180") Double longitude) {}

  public record Stop(
      @NotNull @Valid Place place,
      @Min(0) @Max(10080) int stayMinutes,
      @Min(0) @Max(604800) Integer walkToPlaceSeconds,
      @Min(0) @Max(604800) Integer returnWalkSeconds) {}

  public record StopsRequest(@NotNull @Size(max = 20) List<@Valid Stop> stops) {}

  public record Origin(
      @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
      @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
      @NotNull Instant observedAt,
      @NotBlank String source) {}

  // Destination and stops are taken from the versioned server plan, never a second mutable
  // authority.
  public record ComparisonRequest(
      @NotNull Mode mode,
      @NotNull @Valid Origin origin,
      @Min(1) @Max(10080) int parkingDurationMinutes,
      @Min(0) long tripPlanRevision) {}

  public record ParkingRequest(
      @NotNull Instant parkedAt,
      @NotNull @Valid Place place,
      @NotNull Source source,
      boolean userConfirmed,
      @Min(0) long expectedVersion) {}

  public record PhaseRequest(
      @NotNull Phase phase,
      @NotNull Instant observedAt,
      @NotNull Source source,
      boolean userConfirmed,
      @Min(0) long expectedVersion) {}

  public record ReminderRequest(
      boolean enabled,
      @NotNull Anchor anchor,
      @Min(1) @Max(10080) int stayMinutes,
      @Min(0) @Max(10080) int leadMinutes,
      @NotNull ReminderBasis reminderBasis,
      @Min(0) @Max(604800) Integer returnWalkSeconds) {}

  public record ClientStatusRequest(
      @Min(0) long planVersion,
      @NotNull UUID installationId,
      @NotNull ClientStatus status,
      @NotNull Instant observedAt) {}
}
