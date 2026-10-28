package kr.yeouro.network.v2

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Wire models for the v2 phone boundary. Not interchangeable with v1 capability sessions. */
@Serializable data class V2Place(val name: String, val latitude: Double? = null, val longitude: Double? = null)
@Serializable enum class V2Role { DRIVER, COMPANION }
@Serializable enum class ArrivalBasis { PERSON, VEHICLE }
@Serializable enum class TripStatus { PLANNED, ACTIVE, ENDED, CANCELLED, EXPIRED }
@Serializable enum class ConsentScope { TRIP_COLLABORATION, LOCATION_PUBLISH, LOCATION_VIEW }
@Serializable data class TripPlan(
    val destination: V2Place,
    val timeZone: String,
    val arrivalBasis: ArrivalBasis = ArrivalBasis.PERSON,
    val deadlineAt: String? = null,
    val plannedStayMinutes: Int? = null,
    val startsAt: String? = null,
    val expiresAt: String? = null,
    val ownerRole: V2Role? = V2Role.DRIVER,
    val title: String? = null,
)
@Serializable data class SharingSession(val sessionId: String, val generation: Long, val expiresAt: String)
@Serializable data class V2Participant(
    val id: String, val role: V2Role, val displayName: String, val ready: Boolean,
    val readyUpdatedAt: String? = null, val consentVersion: Long,
)
@Serializable data class V2Trip(
    val id: String, val status: TripStatus, val revision: Long, val planRevision: Long,
    val version: Long, val destination: V2Place, val arrivalBasis: ArrivalBasis,
    val timeZone: String, val expiresAt: String, val serverTime: String,
    val title: String? = null, val phase: String? = null, val deadlineAt: String? = null,
    val plannedStayMinutes: Int? = null, val sharing: SharingSession? = null,
    val location: JsonObject? = null, val participants: List<V2Participant> = emptyList(),
    val capabilities: JsonObject, val viewer: V2Viewer,
)
@Serializable data class SharingConsent(val scopes: Set<ConsentScope>, val consentPolicyVersion: String)
@Serializable data class InviteRequest(val role: V2Role, val sessionId: String, val expiresInSeconds: Int = 900)
@Serializable data class V2Invite(val id: String, val code: String, val expiresAt: String, val role: V2Role)
@Serializable data class JoinRequest(val code: String, val consentScopes: Set<ConsentScope>, val consentPolicyVersion: String)
@Serializable data class Membership(val tripId: String, val participantId: String, val role: V2Role, val sharingGeneration: Long)
@Serializable data class ReadinessRequest(val ready: Boolean, val observedAt: String)
@Serializable data class ReadinessResult(val participantId: String, val ready: Boolean)
@Serializable data class TransitionRequest(val expectedVersion: Long)
@Serializable data class TransitionResult(val id: String, val status: TripStatus, val version: Long)
@Serializable internal data class V2Error(val code: String)

@Serializable data class V2Viewer(val participantId: String, val role: V2Role, val isOwner: Boolean, val clientType: String)
@Serializable enum class ProposalDecisionStatus { PENDING, ACCEPTED, DISMISSED, CANCELLED }
@Serializable enum class ProposalKind { DESTINATION, STOP, PARKING }
@Serializable data class V2Proposal(
    val id: String, val kind: ProposalKind, val decisionStatus: ProposalDecisionStatus,
    val version: Long, val place: V2Place, val navigationCommand: JsonObject? = null,
)
@Serializable data class ProposalPage(val proposals: List<V2Proposal>, val nextCursor: String? = null, val serverTime: String)
@Serializable data class ProposalRequest(val kind: ProposalKind, val place: V2Place)
@Serializable enum class ProposalDecision { ACCEPT, DISMISS }
@Serializable enum class NavigationMode { PLEOS, DEMO }
@Serializable data class ProposalDecisionRequest(val decision: ProposalDecision, val expectedVersion: Long, val mode: NavigationMode = NavigationMode.PLEOS)
