package kr.yeouro.network

import kotlinx.coroutines.runBlocking
import kr.yeouro.domain.BackendFailure
import kr.yeouro.domain.DemoVehicleGateway
import kr.yeouro.model.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assume.assumeTrue
import java.time.Instant
import java.util.UUID
import kotlin.test.*

/** Opt-in local test. No remote service/default endpoint; credentials are never printed. */
class LiveBackendTest {
    @Test fun nativeClientCompletesRealHttpFlow() = runBlocking {
        val endpoint = System.getenv("YEOURO_TEST_BASE_URL")
        assumeTrue("Set YEOURO_TEST_BASE_URL to an explicitly started local test backend", !endpoint.isNullOrBlank())
        val url = endpoint!!.toHttpUrl()
        require(url.host in setOf("127.0.0.1", "localhost")) { "Live test is restricted to an explicitly local server" }
        val repository = HttpTripRepository(url)
        repository.health()
        val owner = repository.createTrip(Place("네이티브 HTTP 검증"))
        val id = owner.trip.id
        try {
            val companionInvite = repository.invite(id, owner.accessToken, true, Role.COMPANION)
            val companion = repository.join(companionInvite.code, "동행 테스트", true)
            assertEquals(Role.COMPANION, companion.role)
            val driverInvite = repository.invite(id, owner.accessToken, true, Role.DRIVER)
            val driver = repository.join(driverInvite.code, "운전자 테스트", true)
            assertEquals(Role.DRIVER, driver.role)
            val ready = repository.ready(id, companion.accessToken, true)
            assertTrue(ready.participants.first { it.id == companion.participantId }.ready)
            val proposed = repository.propose(id, companion.accessToken, UUID.randomUUID().toString(), Place("만날 장소"))
            val proposal = proposed.proposals.last()
            assertEquals(ProposalStatus.PENDING, proposal.status)
            val accepted = repository.decide(id, driver.accessToken, proposal, Decision.ACCEPT)
            assertEquals(ProposalStatus.APPLY_REQUESTED, accepted.proposals.last().status)
            val applied = repository.report(id, driver.accessToken, proposal.id, DemoVehicleGateway().apply(proposal.place))
            assertEquals(ProposalStatus.DEMO_APPLIED, applied.proposals.last().status)
            assertEquals("UNAVAILABLE", applied.navigation.availability)
            val location = repository.location(id, driver.accessToken,
                SharedLocation(37.5665, 126.9780, 100.0, Instant.now().toString(), LocationSource.SIMULATED))
            assertEquals(LocationSource.SIMULATED, location.location?.source)
            repository.revoke(id, owner.accessToken)
            assertEquals(401, assertFailsWith<BackendFailure> { repository.trip(id, companion.accessToken) }.status)
            assertEquals(401, assertFailsWith<BackendFailure> { repository.trip(id, driver.accessToken) }.status)
        } finally {
            repository.end(id, owner.accessToken)
        }
    }
}
