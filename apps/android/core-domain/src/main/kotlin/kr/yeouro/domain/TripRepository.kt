package kr.yeouro.domain

import kr.yeouro.model.*

interface TripRepository {
    suspend fun health()
    suspend fun createTrip(place: Place): SessionResponse
    suspend fun join(code: String, displayName: String, consent: Boolean): SessionResponse
    suspend fun trip(id: String, token: String): Trip
    suspend fun invite(id: String, token: String, consent: Boolean, role: Role): Invite
    suspend fun ready(id: String, token: String, ready: Boolean): Trip
    suspend fun propose(id: String, token: String, requestId: String, place: Place): Trip
    suspend fun decide(id: String, token: String, proposal: Proposal, decision: Decision): Trip
    suspend fun report(id: String, token: String, proposalId: String, result: NavigationResultRequest): Trip
    suspend fun location(id: String, token: String, location: SharedLocation): Trip
    suspend fun revoke(id: String, token: String): Trip
    suspend fun end(id: String, token: String): Trip
}

class BackendFailure(val code: String, val status: Int? = null) : Exception(code)

fun failureMessage(error: Throwable): String = when ((error as? BackendFailure)?.code) {
    "CONFIGURATION" -> "서버 주소가 설정되지 않았어요. HTTPS 서버 설정이 필요해요."
    "CONSENT_REQUIRED" -> "이번 이동 공유에 먼저 동의해 주세요."
    "INVITE_INVALID", "INVITE_EXPIRED", "INVITE_CONSUMED" -> "초대 코드가 만료되었거나 이미 사용되었어요. 새 코드를 받아 주세요."
    "UNAUTHORIZED", "TOKEN_EXPIRED", "INVALID_TOKEN", "SESSION_EXPIRED" -> "연결이 만료되었어요. 이 기기에서 나간 뒤 다시 연결해 주세요."
    "FORBIDDEN" -> "이번 역할로는 이 작업을 할 수 없어요."
    "SHARING_DISABLED", "TRIP_ENDED", "TRIP_EXPIRED" -> "이동 또는 공유가 종료되었어요."
    "VERSION_CONFLICT", "PROPOSAL_FINALIZED", "REQUEST_ID_REUSED" -> "다른 기기에서 상태가 바뀌었어요. 새로고침 후 확인해 주세요."
    "INVALID_COORDINATES", "INVALID_REQUEST" -> "입력한 내용을 다시 확인해 주세요."
    "LIMIT_EXCEEDED" -> "요청이 많아요. 잠시 뒤 다시 시도해 주세요."
    "NAVIGATION_UNAVAILABLE" -> "실제 내비게이션 SDK가 연결되지 않았어요."
    "NETWORK" -> "서버에 연결하지 못했어요. 네트워크와 서버를 확인해 주세요."
    else -> "처리 결과를 확인하지 못했어요. 새로고침 후 다시 확인해 주세요."
}

fun validPlace(place: Place): Boolean = place.name.trim().length in 1..100 &&
    ((place.latitude == null && place.longitude == null) ||
        (place.latitude?.let { it.isFinite() && it in -90.0..90.0 } == true &&
            place.longitude?.let { it.isFinite() && it in -180.0..180.0 } == true))

fun Role.canDecide(): Boolean = this == Role.OWNER || this == Role.DRIVER
