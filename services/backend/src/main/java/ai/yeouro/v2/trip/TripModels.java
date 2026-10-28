package ai.yeouro.v2.trip;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;

public final class TripModels {
  private TripModels() {}

  public record Place(
      @NotBlank @Size(max = 160) String name,
      @DecimalMin("-90") @DecimalMax("90") Double latitude,
      @DecimalMin("-180") @DecimalMax("180") Double longitude) {}

  public record Plan(
      @NotNull @Valid Place destination,
      @Pattern(regexp = "PERSON|VEHICLE") String arrivalBasis,
      Instant deadlineAt,
      @Min(1) @Max(10080) Integer plannedStayMinutes,
      @NotBlank String timeZone,
      Instant startsAt,
      Instant expiresAt,
      @Pattern(regexp = "DRIVER|COMPANION") String ownerRole,
      @Size(max = 120) String title) {}

  public record Transition(@NotNull @Min(0) Long expectedVersion) {}
}
