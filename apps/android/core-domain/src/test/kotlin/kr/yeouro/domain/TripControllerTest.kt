package kr.yeouro.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kr.yeouro.model.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TripControllerTest {
    @Test fun unavailableGatewayFailsClosed() = runTest {
        val gateway = UnavailablePleosGateway()
        assertEquals(DriveState.UNKNOWN, gateway.driving.value)
        assertEquals(NavigationOutcome.FAILED, gateway.apply(Place("시청")).outcome)
    }
    @Test fun demoNeverReportsRealApplied() = runTest {
        val gateway = DemoVehicleGateway()
        assertEquals(NavigationOutcome.DEMO_APPLIED, gateway.apply(Place("시청")).outcome)
        gateway.setDriving(true)
        assertEquals(NavigationOutcome.FAILED, gateway.apply(Place("시청")).outcome)
    }
    @Test fun coordinatesArePairedAndFinite() {
        assertTrue(validPlace(Place("시청")))
        assertFalse(validPlace(Place(" ")))
        assertFalse(validPlace(Place("시청", 37.5, null)))
        assertFalse(validPlace(Place("시청", Double.NaN, 127.0)))
        assertFalse(validPlace(Place("시청", 91.0, 127.0)))
        assertTrue(validPlace(Place("시청", 37.5, 127.0)))
    }
    @Test fun repeatedCreateIsSingleFlightAndCredentialsNeverEnterDisplayState() = runTest {
        val repository = FakeRepository().apply { delayCreate = 500 }
        val controller = TripController(repository, backgroundScope)
        controller.create(Place("시청")); controller.create(Place("중복"))
        runCurrent()
        assertEquals(1, repository.creates)
        advanceTimeBy(500); runCurrent()
        assertEquals("시청", controller.state.value.trip?.destination?.name)
        assertFalse(controller.state.value.toString().contains("secret"))
        assertFalse(repository.session().toString().contains("secret"))
    }
    @Test fun noConsentCannotInviteOrJoin() = runTest {
        val repository = FakeRepository()
        val controller = TripController(repository, backgroundScope)
        controller.join("code", "이름", false); runCurrent()
        assertEquals(0, repository.joins)
        controller.create(Place("시청")); runCurrent()
        controller.invite(false, Role.COMPANION); runCurrent()
        assertEquals(0, repository.invites)
    }
    @Test fun companionCannotApproveOrUploadLocation() = runTest {
        val repository = FakeRepository().apply { grantedRole = Role.COMPANION }
        val controller = TripController(repository, backgroundScope)
        controller.join("code", "동행", true); runCurrent()
        controller.acceptAndApply(proposal(), DemoVehicleGateway()); runCurrent()
        controller.sendSimulatedLocation(); runCurrent()
        assertEquals(0, repository.decisions)
        assertEquals(0, repository.locations)
    }
    @Test fun driverCannotInviteButCanApproveDemoAndReportSeparately() = runTest {
        val repository = FakeRepository().apply { grantedRole = Role.DRIVER }
        val controller = TripController(repository, backgroundScope)
        controller.join("code", "운전자", true); runCurrent()
        controller.invite(true, Role.COMPANION); runCurrent()
        assertEquals(0, repository.invites)
        controller.acceptAndApply(proposal(), DemoVehicleGateway()); runCurrent()
        assertEquals(listOf("decision:ACCEPT", "report:DEMO_APPLIED"), repository.events)
        assertEquals(ProposalStatus.DEMO_APPLIED, controller.state.value.trip?.proposals?.single()?.status)
    }
    @Test fun drivingLockBlocksApprovalBeforeNetwork() = runTest {
        val repository = FakeRepository()
        val controller = TripController(repository, backgroundScope)
        controller.create(Place("시청")); runCurrent()
        val gateway = DemoVehicleGateway().apply { setDriving(true) }
        controller.acceptAndApply(proposal(), gateway); runCurrent()
        assertEquals(0, repository.decisions)
    }
    @Test fun unavailableGatewayCannotInventApplySuccess() = runTest {
        val repository = FakeRepository()
        val controller = TripController(repository, backgroundScope)
        controller.create(Place("시청")); runCurrent()
        controller.acceptAndApply(proposal(), UnavailablePleosGateway()); runCurrent()
        assertEquals(listOf("decision:ACCEPT", "report:FAILED"), repository.events)
    }
    @Test fun snapshotCannotRollBackNewerVersion() = runTest {
        val repository = FakeRepository()
        val controller = TripController(repository, backgroundScope)
        controller.create(Place("시청")); runCurrent()
        repository.current = repository.current.copy(version = 10, destination = Place("새 목적지"))
        controller.refresh(); runCurrent()
        repository.current = repository.current.copy(version = 1, destination = Place("오래된 목적지"))
        controller.refresh(); runCurrent()
        assertEquals("새 목적지", controller.state.value.trip?.destination?.name)
    }
    @Test fun pollingStopsWhenLifecycleCoroutineCancelsAndNeverUploadsLocation() = runTest {
        val repository = FakeRepository()
        val controller = TripController(repository, backgroundScope)
        controller.create(Place("시청")); runCurrent()
        val polling = backgroundScope.launch { controller.pollWhileStarted() }
        runCurrent()
        assertEquals(1, repository.reads)
        advanceTimeBy(5_000); runCurrent()
        assertEquals(2, repository.reads)
        polling.cancelAndJoin()
        advanceTimeBy(20_000); runCurrent()
        assertEquals(2, repository.reads)
        assertEquals(0, repository.locations)
    }
    @Test fun locationIsOnlyExplicitAndLabelledSimulated() = runTest {
        val repository = FakeRepository()
        val controller = TripController(repository, backgroundScope)
        controller.create(Place("시청")); runCurrent()
        assertEquals(0, repository.locations)
        controller.sendSimulatedLocation(); runCurrent()
        assertEquals(LocationSource.SIMULATED, repository.current.location?.source)
        assertEquals(1, repository.locations)
    }
    @Test fun retryReadsDoesNotRepeatMutation() = runTest {
        val repository = FakeRepository().apply { failCreate = true }
        val controller = TripController(repository, backgroundScope)
        controller.create(Place("시청")); runCurrent()
        assertEquals(Connection.ERROR, controller.state.value.connection)
        controller.refresh(); runCurrent()
        assertEquals(1, repository.creates)
        assertEquals(1, repository.healthChecks)
    }
    @Test fun cancelPendingCreateDoesNotRestoreSessionLater() = runTest {
        val repository = FakeRepository().apply { delayCreate = 5_000 }
        val controller = TripController(repository, backgroundScope)
        controller.create(Place("시청")); runCurrent()
        controller.cancelOperation()
        advanceTimeBy(10_000); runCurrent()
        assertNull(controller.state.value.trip)
        assertFalse(controller.state.value.busy)
    }
    @Test fun revokedReadClearsSensitiveSessionNotJustConnectionStatus() = runTest {
        val repository = FakeRepository().apply { grantedRole = Role.COMPANION }
        val controller = TripController(repository, backgroundScope)
        controller.join("code", "동행", true); runCurrent()
        repository.unauthorized = true
        controller.refresh(); runCurrent()
        assertNull(controller.state.value.trip)
        assertNull(controller.state.value.role)
        assertNull(controller.state.value.participantId)
        assertNull(controller.state.value.invite)
        assertFalse(controller.state.value.busy)
    }
    @Test fun revokedMutationClearsSessionAndDoesNotKeepLocation() = runTest {
        val repository = FakeRepository().apply { grantedRole = Role.COMPANION }
        val controller = TripController(repository, backgroundScope)
        controller.join("code", "동행", true); runCurrent()
        repository.unauthorized = true
        controller.ready(true); runCurrent()
        assertNull(controller.state.value.trip)
        assertNull(controller.state.value.role)
        assertFalse(controller.state.value.busy)
    }
    @Test fun lateOldPollCannotRestoreOrRevokeNewSession() = runTest {
        for (lateUnauthorized in listOf(false, true)) {
            val repository = FakeRepository()
            val controller = TripController(repository, backgroundScope)
            controller.create(Place("이전 이동")); runCurrent()
            val gate = CompletableDeferred<Unit>()
            repository.readGate = gate
            val polling = backgroundScope.launch { controller.pollWhileStarted() }
            runCurrent()
            controller.leaveDevice()
            repository.readGate = null
            repository.current = repository.current.copy(id = "new-trip")
            controller.create(Place("새 이동")); runCurrent()
            repository.unauthorized = lateUnauthorized
            gate.complete(Unit); runCurrent()
            assertEquals("new-trip", controller.state.value.trip?.id)
            assertEquals("새 이동", controller.state.value.trip?.destination?.name)
            polling.cancelAndJoin()
        }
    }
    @Test fun localLeaveRemovesSessionWithoutEndingOtherDevices() = runTest {
        val repository = FakeRepository()
        val controller = TripController(repository, backgroundScope)
        controller.create(Place("시청")); runCurrent()
        controller.leaveDevice()
        controller.refresh(); runCurrent()
        assertNull(controller.state.value.trip)
        assertEquals(0, repository.ends)
        assertEquals(1, repository.healthChecks)
    }
}

private fun proposal() = Proposal("proposal-id", "companion-id", Place("카페"), ProposalStatus.PENDING, 1)
private fun trip() = Trip("trip-id", Place("시청"), TripStatus.ACTIVE, true, 1, "2030-01-01T00:00:00Z",
    proposals = listOf(proposal()), navigation = NavigationCapability("PLEOS", "UNAVAILABLE", "SDK_NOT_CONNECTED"))
private class FakeRepository : TripRepository {
    var current = trip()
    var grantedRole = Role.OWNER
    var creates = 0; var joins = 0; var invites = 0; var reads = 0; var locations = 0; var decisions = 0; var ends = 0; var healthChecks = 0
    var delayCreate = 0L; var failCreate = false; var unauthorized = false
    var readGate: CompletableDeferred<Unit>? = null
    val events = mutableListOf<String>()
    fun session() = SessionResponse(current, "super-secret-token", grantedRole, if (grantedRole == Role.OWNER) null else "companion-id", current.expiresAt)
    override suspend fun health() { healthChecks++ }
    override suspend fun createTrip(place: Place): SessionResponse { creates++; delay(delayCreate); if (failCreate) throw BackendFailure("NETWORK"); current = current.copy(destination = place); return session() }
    override suspend fun join(code: String, displayName: String, consent: Boolean): SessionResponse { joins++; return session() }
    override suspend fun trip(id: String, token: String): Trip { reads++; val captured = current; readGate?.await(); if (unauthorized) throw BackendFailure("UNAUTHORIZED", 401); return captured }
    override suspend fun invite(id: String, token: String, consent: Boolean, role: Role): Invite { invites++; return Invite("code", current.expiresAt) }
    override suspend fun ready(id: String, token: String, ready: Boolean): Trip { if (unauthorized) throw BackendFailure("UNAUTHORIZED", 401); return current }
    override suspend fun propose(id: String, token: String, requestId: String, place: Place): Trip = current
    override suspend fun decide(id: String, token: String, proposal: Proposal, decision: Decision): Trip {
        decisions++; events += "decision:$decision"
        current = current.copy(version = current.version + 1, proposals = listOf(proposal.copy(status = ProposalStatus.APPLY_REQUESTED)))
        return current
    }
    override suspend fun report(id: String, token: String, proposalId: String, result: NavigationResultRequest): Trip {
        events += "report:${result.outcome}"
        current = current.copy(version = current.version + 1, proposals = current.proposals.map { it.copy(status = ProposalStatus.valueOf(result.outcome.name)) })
        return current
    }
    override suspend fun location(id: String, token: String, location: SharedLocation): Trip { locations++; current = current.copy(location = location, version = current.version + 1); return current }
    override suspend fun revoke(id: String, token: String): Trip { current = current.copy(sharingEnabled = false, version = current.version + 1); return current }
    override suspend fun end(id: String, token: String): Trip { ends++; current = current.copy(status = TripStatus.ENDED, version = current.version + 1); return current }
}
