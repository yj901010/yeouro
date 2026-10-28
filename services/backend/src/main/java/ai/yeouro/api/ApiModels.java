package ai.yeouro.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Wire records only. Persistence entities are never serialized to callers. */
public final class ApiModels {
    private ApiModels() {}
    public enum Role { OWNER, DRIVER, COMPANION }
    public enum TripStatus { ACTIVE, ENDED }
    public enum ProposalStatus { PENDING, APPLY_REQUESTED, DISMISSED, APPLIED, FAILED, DEMO_APPLIED, CANCELLED }
    public enum Decision { ACCEPT, DISMISS }
    public enum Outcome { APPLIED, FAILED, DEMO_APPLIED }
    public enum Source { PHONE, VEHICLE, SIMULATED }
    public record Place(@NotBlank @Size(max=120) String name,
                        @DecimalMin("-90") @DecimalMax("90") Double latitude,
                        @DecimalMin("-180") @DecimalMax("180") Double longitude) {}
    public record CreateTripRequest(@NotNull @Valid Place destination) {}
    public record ConsentRequest(@NotNull Boolean consent, @NotNull Role role) {}
    public record JoinRequest(@NotBlank @Size(min=20,max=64) String code,
                              @NotBlank @Size(max=40) String displayName, @NotNull Boolean consent) {}
    public record ReadinessRequest(@NotNull Boolean ready) {}
    public record ProposalRequest(@NotNull UUID requestId, @NotNull @Valid Place place) {}
    public record DecisionRequest(@NotNull Decision decision, @NotNull @PositiveOrZero Long expectedVersion) {}
    public record NavigationResultRequest(@NotNull Outcome outcome, @Size(max=200) String reason) {}
    public record Location(@NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
                           @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
                           @NotNull @DecimalMin("0") @DecimalMax("10000") Double accuracyMeters,
                           @NotNull Instant observedAt, @NotNull Source source) {}
    public record Participant(UUID id, String displayName, Role role, boolean ready) {}
    public record Proposal(UUID id, UUID participantId, Place place, ProposalStatus status,
                           long version, String resultReason) {}
    public record NavigationStatus(String provider, String availability, String reason) {}
    public record Trip(UUID id, Place destination, TripStatus status, boolean sharingEnabled,
                       long version, Instant expiresAt, List<Participant> participants,
                       List<Proposal> proposals, Location location, NavigationStatus navigation) {}
    public record SessionResponse(Trip trip, String accessToken, Role role, UUID participantId, Instant expiresAt) {}
    public record InviteResponse(String code, Instant expiresAt) {}
    public record Error(String code, String message) {}
    public record Health(String status) {}
}
