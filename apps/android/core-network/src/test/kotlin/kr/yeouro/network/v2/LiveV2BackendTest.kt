package kr.yeouro.network.v2

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kr.yeouro.domain.BackendFailure
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.*

/** Opt-in real native HTTP boundary test, restricted to an explicitly local synthetic fixture. */
class LiveV2BackendTest {
    @Test fun accountBasedNativeClientCompletesSharingFlow(): Unit = runBlocking {
        val endpoint = System.getenv("YEOURO_V2_TEST_BASE_URL")
        assumeTrue("Use scripts/smoke-native-v2.py with a disposable local database", !endpoint.isNullOrBlank())
        val url = endpoint!!.toHttpUrl()
        require(url.host in setOf("127.0.0.1", "localhost"))
        val driverToken = requireNotNull(System.getenv("YEOURO_V2_TEST_DRIVER_JWT"))
        val companionToken = requireNotNull(System.getenv("YEOURO_V2_TEST_COMPANION_JWT"))
        val driver = V2PhoneRepository(url, PhoneAccessTokenProvider { driverToken })
        val companion = V2PhoneRepository(url, PhoneAccessTokenProvider { companionToken })
        fun key() = UUID.randomUUID().toString()
        val trip = driver.createTrip(TripPlan(V2Place("합성 네이티브 검증", 37.5, 127.0), "Asia/Seoul"), key())
        assertTrue(trip.viewer.isOwner)
        assertEquals(V2Role.DRIVER, trip.viewer.role)
        driver.startTrip(trip.id, trip.version, key())
        val sharing = driver.startSharing(trip.id, SharingConsent(setOf(ConsentScope.TRIP_COLLABORATION), "test-v1"), key())
        val sessionId = sharing.getValue("sessionId").jsonPrimitive.content
        val invitation = driver.invite(trip.id, InviteRequest(V2Role.COMPANION, sessionId), key())
        val member = companion.join(JoinRequest(invitation.code, setOf(ConsentScope.TRIP_COLLABORATION), "test-v1"), key())
        assertEquals(trip.id, member.tripId)
        companion.setReadiness(trip.id, ReadinessRequest(true, Instant.now().toString()))
        assertTrue(driver.trip(trip.id).participants.single { it.id == member.participantId }.ready)
        val proposal = companion.propose(trip.id, ProposalRequest(ProposalKind.DESTINATION, V2Place("합성 제안", 37.6, 127.1)), key())
        assertEquals(proposal.id, driver.proposals(trip.id).proposals.single().id)
        val decision = driver.decide(trip.id, proposal.id, ProposalDecisionRequest(ProposalDecision.ACCEPT, proposal.version), key())
        assertEquals("FAILED", decision.getValue("navigationCommand").jsonObject.getValue("status").jsonPrimitive.content)
        assertEquals("SDK_NOT_CONNECTED", decision.getValue("navigationCommand").jsonObject.getValue("reason").jsonPrimitive.content)
        driver.endTrip(trip.id, driver.trip(trip.id).version, key())
        assertEquals(TripStatus.ENDED, driver.trip(trip.id).status)
        assertFailsWith<BackendFailure> { companion.trip(trip.id) }
    }
}
