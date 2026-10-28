package kr.yeouro.network.v2

import kotlinx.coroutines.runBlocking
import kr.yeouro.domain.BackendFailure
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import kotlin.test.*

class V2PhoneRepositoryTest {
    @Test fun joinUsesAccountBearerAndReturnsMembershipNotCapabilitySession(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"tripId":"trip","participantId":"member","role":"COMPANION","sharingGeneration":2}"""))
            val api = V2PhoneRepository(server.url("/"), PhoneAccessTokenProvider { "phone-jwt" })
            val result = api.join(JoinRequest("invite-secret", setOf(ConsentScope.TRIP_COLLABORATION), "v1"), "join-key")
            assertEquals(V2Role.COMPANION, result.role)
            val request = server.takeRequest()
            assertEquals("/api/v2/invites/join", request.path)
            assertEquals("Bearer phone-jwt", request.getHeader("Authorization"))
            assertEquals("join-key", request.getHeader("Idempotency-Key"))
            assertFalse(request.path!!.contains("secret"))
            assertTrue(request.body.readUtf8().contains("invite-secret"))
        }
    }

    @Test fun missingAccountSessionDoesNotSendRequest(): Unit = runBlocking {
        MockWebServer().use { server ->
            val api = V2PhoneRepository(server.url("/"), PhoneAccessTokenProvider { null })
            assertEquals(401, assertFailsWith<BackendFailure> { api.trip("trip") }.status)
            assertEquals(0, server.requestCount)
        }
    }

    @Test fun forbiddenResponseIsNotRetriedOrConvertedToOffline(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"code":"FORBIDDEN"}"""))
            val api = V2PhoneRepository(server.url("/"), PhoneAccessTokenProvider { "token" })
            val failure = assertFailsWith<BackendFailure> { api.trip("trip") }
            assertEquals(403, failure.status)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun redirectNeverForwardsBearerToAnotherOrigin(): Unit = runBlocking {
        MockWebServer().use { server -> MockWebServer().use { destination ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", destination.url("/capture")))
            val api = V2PhoneRepository(server.url("/"), PhoneAccessTokenProvider { "secret-jwt" })
            assertEquals(302, assertFailsWith<BackendFailure> { api.trip("trip") }.status)
            assertEquals(0, destination.requestCount)
        } }
    }

    @Test fun planReplacementSendsPlanEtagAndOmitsOwnerRole(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(412).setBody("""{"code":"PLAN_REVISION_CONFLICT"}"""))
            val api = V2PhoneRepository(server.url("/"), PhoneAccessTokenProvider { "token" })
            assertEquals(412, assertFailsWith<BackendFailure> {
                api.updatePlan("trip", TripPlan(V2Place("목적지"), "Asia/Seoul"), 7)
            }.status)
            val request = server.takeRequest()
            assertEquals("PATCH", request.method)
            assertEquals("\"plan-7\"", request.getHeader("If-Match"))
            assertFalse(request.body.readUtf8().contains("ownerRole"))
        }
    }

    @Test fun invalidReleaseEndpointFailsBeforeRequest(): Unit = runBlocking {
        val api = V2PhoneRepository.configured("http://example.com", false, PhoneAccessTokenProvider { "token" })
        assertFailsWith<BackendFailure> { api.trip("trip") }
    }
    @Test fun proposalDiscoveryKeepsApprovalDistinctFromNavigation(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"proposals":[{"id":"p","kind":"DESTINATION","decisionStatus":"ACCEPTED","version":2,"place":{"name":"장소"},"navigationCommand":{"status":"FAILED","reason":"SDK_NOT_CONNECTED"}}],"nextCursor":null,"serverTime":"2026-10-28T09:00:00Z"}"""))
            val api = V2PhoneRepository(server.url("/"), PhoneAccessTokenProvider { "token" })
            val page = api.proposals("trip", "cursor-id")
            assertEquals(ProposalDecisionStatus.ACCEPTED, page.proposals.single().decisionStatus)
            assertNotNull(page.proposals.single().navigationCommand)
            assertEquals("/api/v2/trips/trip/proposals?cursor=cursor-id", server.takeRequest().path)
        }
    }

    @Test fun realDecisionUsesPleosModeNotRoutingRealMode(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"proposal":{"decisionStatus":"ACCEPTED"},"navigationCommand":{"status":"FAILED","reason":"SDK_NOT_CONNECTED"}}"""))
            val api = V2PhoneRepository(server.url("/"), PhoneAccessTokenProvider { "token" })
            api.decide("trip", "proposal", ProposalDecisionRequest(ProposalDecision.ACCEPT, 2), "decision-key")
            val request = server.takeRequest()
            assertTrue(request.body.readUtf8().contains("\"mode\":\"PLEOS\""))
        }
    }

    @Test fun createDecodesOwnMembershipAndDoesNotInventPhoneCapability(): Unit = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"id":"trip","status":"PLANNED","revision":1,"planRevision":1,"version":0,"destination":{"name":"장소","latitude":null,"longitude":null},"arrivalBasis":"PERSON","timeZone":"Asia/Seoul","expiresAt":"2026-10-29T09:00:00Z","serverTime":"2026-10-28T09:00:00Z","sharing":null,"location":null,"participants":[],"capabilities":{"navigation":{"availability":"UNAVAILABLE","reason":"SDK_NOT_CONNECTED"}},"viewer":{"participantId":"owner-member","role":"COMPANION","isOwner":true,"clientType":"PHONE"},"stops":[]}"""))
            val api = V2PhoneRepository(server.url("/"), PhoneAccessTokenProvider { "token" })
            val trip = api.createTrip(TripPlan(V2Place("장소"), "Asia/Seoul", ownerRole = V2Role.COMPANION), "create-key")
            assertTrue(trip.viewer.isOwner)
            assertEquals(V2Role.COMPANION, trip.viewer.role)
            assertNull(trip.location)
            assertNull(trip.sharing)
            assertEquals("/api/v2/trips", server.takeRequest().path)
        }
    }

}
