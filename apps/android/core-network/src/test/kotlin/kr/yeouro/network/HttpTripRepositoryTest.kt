package kr.yeouro.network

import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kr.yeouro.domain.BackendFailure
import kr.yeouro.model.*
import okhttp3.mockwebserver.*
import org.junit.After
import org.junit.Before
import kotlin.test.*

class HttpTripRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: HttpTripRepository
    private val json = Json { encodeDefaults = true }
    private val trip = Trip("trip-1", Place("시청"), TripStatus.ACTIVE, true, 1, "2030-01-01T00:00:00Z",
        navigation = NavigationCapability("PLEOS", "UNAVAILABLE", "SDK_NOT_CONNECTED"))
    @Before fun setup() { server = MockWebServer(); server.start(); repository = HttpTripRepository(server.url("/")) }
    @After fun teardown() { server.shutdown() }
    private fun tripResponse() = MockResponse().setHeader("Content-Type", "application/json").setBody(json.encodeToString(trip))
    private fun sessionResponse(role: Role = Role.OWNER) = MockResponse().setResponseCode(201).setBody(json.encodeToString(SessionResponse(trip, "test-token", role, null, trip.expiresAt)))

    @Test fun bearerCredentialOnlyInHeader() = runBlocking {
        server.enqueue(tripResponse())
        assertEquals(trip, repository.trip("trip-1", "test-token"))
        val request = server.takeRequest()
        assertEquals("/api/v1/trips/trip-1", request.path)
        assertEquals("Bearer test-token", request.getHeader("Authorization"))
        assertFalse(request.path.orEmpty().contains("test-token"))
    }
    @Test fun createHasNoImplicitSharingConsent() = runBlocking {
        server.enqueue(sessionResponse())
        repository.createTrip(Place("시청"))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/v1/trips", request.path)
        assertNull(request.getHeader("Authorization"))
        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(setOf("destination"), body.keys)
    }
    @Test fun inviteRoleIsOwnerGrantedAndConsentExplicit() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"code":"test-invite","expiresAt":"2030-01-01T00:00:00Z"}"""))
        repository.invite("trip-1", "test-token", true, Role.DRIVER)
        val request = server.takeRequest()
        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("DRIVER", body["role"]?.jsonPrimitive?.content)
        assertEquals(true, body["consent"]?.jsonPrimitive?.boolean)
    }
    @Test fun joinCannotChooseOrEscalateRole() = runBlocking {
        server.enqueue(sessionResponse(Role.COMPANION))
        repository.join("test-invite", "동행자", true)
        val request = server.takeRequest()
        assertEquals("/api/v1/invites/join", request.path)
        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(setOf("code", "displayName", "consent"), body.keys)
        assertNull(body["role"])
    }
    @Test fun proposalVersionAndDecisionUseExactContract() = runBlocking {
        server.enqueue(tripResponse())
        repository.decide("trip-1", "test-token", Proposal("p-1", "c-1", Place("카페"), ProposalStatus.PENDING, 7), Decision.ACCEPT)
        val request = server.takeRequest()
        assertEquals("/api/v1/trips/trip-1/proposals/p-1/decision", request.path)
        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(7, body["expectedVersion"]?.jsonPrimitive?.int)
        assertEquals("ACCEPT", body["decision"]?.jsonPrimitive?.content)
    }
    @Test fun demoOutcomeAndLocationRemainExplicit() = runBlocking {
        server.enqueue(tripResponse()); server.enqueue(tripResponse())
        repository.report("trip-1", "test-token", "p-1", NavigationResultRequest(NavigationOutcome.DEMO_APPLIED, "SIMULATED_NO_VEHICLE_CALL"))
        assertTrue(server.takeRequest().body.readUtf8().contains("DEMO_APPLIED"))
        repository.location("trip-1", "test-token", SharedLocation(37.0, 127.0, 100.0, "2026-10-28T00:00:00Z", LocationSource.SIMULATED))
        assertTrue(server.takeRequest().body.readUtf8().contains("SIMULATED"))
    }
    @Test fun rawServerMessageIsNeverPropagated() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"code":"UNAUTHORIZED","message":"unsafe test-token trace"}"""))
        val error = assertFailsWith<BackendFailure> { repository.trip("trip-1", "test-token") }
        assertEquals("UNAUTHORIZED", error.code)
        assertFalse(error.toString().contains("test-token"))
    }
    @Test fun malformedSuccessIsNotTreatedAsTrip() = runBlocking {
        server.enqueue(MockResponse().setBody("not JSON"))
        assertEquals("INVALID_RESPONSE", assertFailsWith<BackendFailure> { repository.trip("trip-1", "test-token") }.code)
    }
    @Test fun redirectsAreNotFollowedWithCredentials() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", server.url("/elsewhere")))
        assertFailsWith<BackendFailure> { repository.trip("trip-1", "test-token") }
        assertEquals(1, server.requestCount)
    }
    @Test fun uncertainCreateResponseIsNotAutomaticallyRetried() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(sessionResponse())
        assertFailsWith<BackendFailure> { repository.createTrip(Place("시청")) }
        assertEquals(1, server.requestCount)
    }
    @Test fun cancellingForegroundReadCancelsHttpCall() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val operation = launch { repository.trip("trip-1", "test-token") }
        withContext(Dispatchers.IO) { server.takeRequest() }
        operation.cancelAndJoin()
        assertTrue(operation.isCancelled)
    }
    @Test fun emulatorCleartextIsDebugOnly() {
        assertNotNull(BackendConfiguration.permittedUrl("http://10.0.2.2:8080/", true))
        assertNull(BackendConfiguration.permittedUrl("http://10.0.2.2:8080/", false))
        assertNull(BackendConfiguration.permittedUrl("http://example.org/", true))
        assertNull(BackendConfiguration.permittedUrl("", false))
        assertNull(BackendConfiguration.permittedUrl("https://name:password@example.org/", false))
        assertNull(BackendConfiguration.permittedUrl("https://example.org/?token=secret", false))
        assertNotNull(BackendConfiguration.permittedUrl("https://example.org/", false))
    }
    @Test fun unconfiguredReleaseDoesNotAttemptNetwork() = runBlocking {
        assertEquals("CONFIGURATION", assertFailsWith<BackendFailure> { HttpTripRepository.configured("", false).health() }.code)
    }
}
