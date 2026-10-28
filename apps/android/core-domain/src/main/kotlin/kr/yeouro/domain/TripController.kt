package kr.yeouro.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kr.yeouro.model.*
import java.time.Instant
import java.util.UUID

/** Public display state intentionally contains no bearer credential. */
data class TripUiState(
    val trip: Trip? = null,
    val role: Role? = null,
    val participantId: String? = null,
    val invite: Invite? = null,
    val inviteRole: Role? = null,
    val connection: Connection = Connection.CHECKING,
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val lastCheckedAt: String? = null,
)
enum class Connection { CHECKING, CONNECTED, ERROR }

/** Memory-only session. Foreground polling is owned by the Android lifecycle caller. */
class TripController(private val repository: TripRepository, private val scope: CoroutineScope) {
    private val mutableState = MutableStateFlow(TripUiState())
    val state: StateFlow<TripUiState> = mutableState.asStateFlow()
    private var credentials: SessionResponse? = null
    private var operation: Job? = null
    private var generation = 0L

    suspend fun pollWhileStarted() {
        while (currentCoroutineContext().isActive) {
            if (!state.value.busy) refreshSnapshot()
            delay(5_000)
        }
    }

    private suspend fun refreshSnapshot() {
        val session = credentials
        try {
            if (session == null) {
                repository.health()
                if (credentials == null) connected()
            } else {
                val trip = repository.trip(session.trip.id, session.accessToken)
                currentCoroutineContext().ensureActive()
                if (credentials === session) acceptSnapshot(trip)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (credentials === session) handleFailure(error)
        }
    }

    fun refresh() = launchAction { refreshSnapshot() }
    fun dismissError() { mutableState.update { it.copy(error = null, notice = null) } }

    fun create(place: Place) = launchAction {
        require(validPlace(place))
        check(credentials == null)
        val result = repository.createTrip(place.copy(name = place.name.trim()))
        currentCoroutineContext().ensureActive()
        open(result)
    }
    fun join(code: String, displayName: String, consent: Boolean) = launchAction {
        require(consent && code.isNotBlank() && displayName.trim().length in 1..40)
        check(credentials == null)
        val result = repository.join(code.trim(), displayName.trim(), consent)
        currentCoroutineContext().ensureActive()
        open(result)
    }
    fun invite(consent: Boolean, role: Role) = launchAction {
        require(consent && role in listOf(Role.DRIVER, Role.COMPANION))
        val session = session(Role.OWNER)
        val invitation = repository.invite(session.trip.id, session.accessToken, consent, role)
        currentCoroutineContext().ensureActive()
        mutableState.update { it.copy(invite = invitation, inviteRole = role, notice = "15분 안에 한 번 사용할 수 있는 초대 코드예요.") }
        acceptSnapshot(repository.trip(session.trip.id, session.accessToken))
    }
    fun ready(value: Boolean) = launchAction {
        val session = session(Role.COMPANION)
        acceptSnapshot(repository.ready(session.trip.id, session.accessToken, value))
    }
    fun propose(place: Place) = launchAction {
        require(validPlace(place))
        val session = session(Role.COMPANION)
        acceptSnapshot(repository.propose(session.trip.id, session.accessToken, UUID.randomUUID().toString(), place.copy(name = place.name.trim())))
    }
    fun dismiss(proposal: Proposal) = launchAction {
        val session = driverSession()
        acceptSnapshot(repository.decide(session.trip.id, session.accessToken, proposal, Decision.DISMISS))
    }

    /** Approval is not application. Report only the adapter's actual outcome; never synthesize APPLIED. */
    fun acceptAndApply(proposal: Proposal, gateway: VehicleGateway) = launchAction {
        val session = driverSession()
        check(gateway.driving.value != DriveState.DRIVING)
        val accepted = repository.decide(session.trip.id, session.accessToken, proposal, Decision.ACCEPT)
        acceptSnapshot(accepted)
        val requested = accepted.proposals.first { it.id == proposal.id }
        if (requested.status == ProposalStatus.APPLY_REQUESTED) applyAndReport(session, requested, gateway)
    }
    fun retryApplication(proposal: Proposal, gateway: VehicleGateway) = launchAction {
        val session = driverSession()
        require(proposal.status == ProposalStatus.APPLY_REQUESTED)
        check(gateway.driving.value != DriveState.DRIVING)
        applyAndReport(session, proposal, gateway)
    }
    private suspend fun applyAndReport(session: SessionResponse, proposal: Proposal, gateway: VehicleGateway) {
        val result = gateway.apply(proposal.place)
        currentCoroutineContext().ensureActive()
        acceptSnapshot(repository.report(session.trip.id, session.accessToken, proposal.id, result))
    }
    fun sendSimulatedLocation() = launchAction {
        val session = driverSession()
        check(state.value.trip?.sharingEnabled == true)
        // One explicit action, one synthetic point. No OS location permission, sensor or background loop.
        val sample = SharedLocation(37.5665, 126.9780, 100.0, Instant.now().toString(), LocationSource.SIMULATED)
        acceptSnapshot(repository.location(session.trip.id, session.accessToken, sample))
    }
    fun revoke() = launchAction {
        val session = session(Role.OWNER)
        acceptSnapshot(repository.revoke(session.trip.id, session.accessToken))
        mutableState.update { it.copy(invite = null, inviteRole = null) }
    }
    fun end() = launchAction {
        val session = driverSession()
        acceptSnapshot(repository.end(session.trip.id, session.accessToken))
        mutableState.update { it.copy(invite = null, inviteRole = null) }
    }

    /** Cancels local work, not a request that may already have reached the server. */
    fun cancelOperation() {
        generation++
        operation?.cancel()
        operation = null
        mutableState.update { it.copy(busy = false, notice = "이 기기의 요청 대기를 취소했어요. 서버 처리 여부는 새로고침으로 확인해 주세요.") }
    }
    fun leaveDevice() {
        generation++
        operation?.cancel()
        operation = null
        credentials = null
        mutableState.value = TripUiState(notice = "이 기기의 연결 정보를 지웠어요. 서버의 이동은 자동 종료되지 않아요.")
    }

    private fun session(required: Role): SessionResponse = checkNotNull(credentials).also {
        check(it.role == required)
        check(state.value.trip?.status == TripStatus.ACTIVE)
    }
    private fun driverSession(): SessionResponse = checkNotNull(credentials).also {
        check(it.role.canDecide())
        check(state.value.trip?.status == TripStatus.ACTIVE)
    }
    private fun open(session: SessionResponse) {
        credentials = session
        mutableState.value = TripUiState(trip = session.trip, role = session.role, participantId = session.participantId,
            connection = Connection.CONNECTED, busy = true, lastCheckedAt = Instant.now().toString())
    }
    private fun connected() { mutableState.update { it.copy(connection = Connection.CONNECTED, lastCheckedAt = Instant.now().toString()) } }
    private fun acceptSnapshot(trip: Trip) {
        if (credentials?.trip?.id != trip.id) return
        mutableState.update { current ->
            if ((current.trip?.version ?: -1) > trip.version) current
            else current.copy(trip = trip, connection = Connection.CONNECTED, lastCheckedAt = Instant.now().toString(),
                invite = if (trip.sharingEnabled && trip.status == TripStatus.ACTIVE) current.invite else null)
        }
    }
    private fun handleFailure(error: Exception) {
        val backend = error as? BackendFailure
        if (backend?.status == 401 || backend?.code in setOf("UNAUTHORIZED", "TRIP_EXPIRED", "TOKEN_EXPIRED", "SESSION_EXPIRED")) {
            // Revocation/expiry is not offline mode: stop retaining shared personal data immediately.
            generation++
            operation?.cancel()
            operation = null
            credentials = null
            mutableState.value = TripUiState(connection = Connection.ERROR,
                error = "이동 연결이 만료되었거나 공유가 종료되었어요. 이 기기의 공유 정보를 지웠어요. 새 초대로 다시 연결해 주세요.")
        } else {
            mutableState.update { it.copy(error = failureMessage(error), connection = Connection.ERROR) }
        }
    }
    private fun launchAction(block: suspend () -> Unit) {
        if (state.value.busy) return
        val ticket = ++generation
        mutableState.update { it.copy(busy = true, error = null, notice = null) }
        operation = scope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (ticket == generation) handleFailure(error)
            } finally {
                if (ticket == generation) mutableState.update { it.copy(busy = false) }
            }
        }
    }
}
