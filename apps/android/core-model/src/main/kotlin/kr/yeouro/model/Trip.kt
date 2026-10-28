package kr.yeouro.model

import kotlinx.serialization.Serializable

@Serializable data class Place(val name: String, val latitude: Double? = null, val longitude: Double? = null)
@Serializable enum class Role { OWNER, DRIVER, COMPANION }
@Serializable enum class TripStatus { ACTIVE, ENDED }
@Serializable enum class ProposalStatus { PENDING, APPLY_REQUESTED, DISMISSED, APPLIED, DEMO_APPLIED, FAILED, CANCELLED }
@Serializable enum class LocationSource { PHONE, VEHICLE, SIMULATED }
@Serializable enum class NavigationOutcome { APPLIED, DEMO_APPLIED, FAILED }
@Serializable enum class Decision { ACCEPT, DISMISS }
@Serializable data class Participant(val id: String, val displayName: String, val ready: Boolean, val role: Role = Role.COMPANION)
@Serializable data class Proposal(
    val id: String,
    val participantId: String,
    val place: Place,
    val status: ProposalStatus,
    val version: Long,
    val resultReason: String? = null,
)
@Serializable data class SharedLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
    val observedAt: String,
    val source: LocationSource,
)
@Serializable data class NavigationCapability(val provider: String, val availability: String, val reason: String)
@Serializable data class Trip(
    val id: String,
    val destination: Place,
    val status: TripStatus,
    val sharingEnabled: Boolean,
    val version: Long,
    val expiresAt: String,
    val participants: List<Participant> = emptyList(),
    val proposals: List<Proposal> = emptyList(),
    val location: SharedLocation? = null,
    val navigation: NavigationCapability,
)

/** Credentials must never be part of UI state, logs, SavedStateHandle, or backups. */
@Serializable data class SessionResponse(
    val trip: Trip,
    val accessToken: String,
    val role: Role,
    val participantId: String? = null,
    val expiresAt: String,
) {
    override fun toString(): String = "SessionResponse(role=$role, credentials=[REDACTED])"
}
@Serializable data class Invite(val code: String, val expiresAt: String)
@Serializable data class ApiError(val code: String = "UNKNOWN", val message: String = "")
@Serializable data class CreateTripRequest(val destination: Place)
@Serializable data class CreateInviteRequest(val consent: Boolean, val role: Role = Role.COMPANION)
@Serializable data class JoinRequest(val code: String, val displayName: String, val consent: Boolean)
@Serializable data class ReadyRequest(val ready: Boolean)
@Serializable data class ProposalRequest(val requestId: String, val place: Place)
@Serializable data class DecisionRequest(val decision: Decision, val expectedVersion: Long)
@Serializable data class NavigationResultRequest(val outcome: NavigationOutcome, val reason: String? = null)
