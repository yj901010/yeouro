package kr.yeouro.network.v2

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kr.yeouro.domain.BackendFailure
import kr.yeouro.network.BackendConfiguration
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Supplies a current phone access JWT; the repository never persists or refreshes credentials itself. */
fun interface PhoneAccessTokenProvider { suspend fun accessToken(): String? }

/**
 * Additive v2 phone client. The existing UI remains on v1 until real sign-in is configured.
 * No anonymous JWT, secret input screen, automatic mutation retry, or redirect following.
 * Callers own lifecycle cancellation, UI consent, cache clearing on 401/403 and stable request keys.
 */
class V2PhoneRepository internal constructor(
    private val baseUrl: HttpUrl?,
    private val tokens: PhoneAccessTokenProvider,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS).retryOnConnectionFailure(false)
        .followRedirects(false).followSslRedirects(false).build(),
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

    companion object {
        fun configured(endpoint: String, debug: Boolean, tokens: PhoneAccessTokenProvider) =
            V2PhoneRepository(BackendConfiguration.permittedUrl(endpoint, debug), tokens)
    }

    suspend fun createTrip(plan: TripPlan, requestKey: String): V2Trip =
        request("POST", listOf("trips"), json.encodeToString(plan), requestKey)

    suspend fun trip(tripId: String): V2Trip = request("GET", listOf("trips", tripId))

    suspend fun updatePlan(tripId: String, plan: TripPlan, planRevision: Long): V2Trip {
        require(planRevision >= 0)
        // The server's PATCH is a full plan replacement, and never accepts role updates.
        return request("PATCH", listOf("trips", tripId),
            json.encodeToString(plan.copy(ownerRole = null)), etag = "\"plan-$planRevision\"")
    }

    suspend fun startSharing(tripId: String, consent: SharingConsent, requestKey: String): JsonObject =
        request("POST", listOf("trips", tripId, "sharing-sessions"), json.encodeToString(consent), requestKey)

    suspend fun invite(tripId: String, invite: InviteRequest, requestKey: String): V2Invite =
        request("POST", listOf("trips", tripId, "invites"), json.encodeToString(invite), requestKey)

    suspend fun join(join: JoinRequest, requestKey: String): Membership =
        request("POST", listOf("invites", "join"), json.encodeToString(join), requestKey)

    suspend fun setReadiness(tripId: String, readiness: ReadinessRequest): ReadinessResult =
        request("PUT", listOf("trips", tripId, "readiness", "me"), json.encodeToString(readiness))

    suspend fun startTrip(tripId: String, version: Long, requestKey: String): TransitionResult =
        transition(tripId, "start", version, requestKey)

    suspend fun endTrip(tripId: String, version: Long, requestKey: String): TransitionResult =
        transition(tripId, "end", version, requestKey)

    suspend fun proposals(tripId: String, cursor: String? = null): ProposalPage =
        request("GET", listOf("trips", tripId, "proposals"), cursor = cursor)

    suspend fun propose(tripId: String, proposal: ProposalRequest, requestKey: String): V2Proposal =
        request("POST", listOf("trips", tripId, "proposals"), json.encodeToString(proposal), requestKey)

    suspend fun decide(tripId: String, proposalId: String, decision: ProposalDecisionRequest, requestKey: String): JsonObject =
        request("POST", listOf("trips", tripId, "proposals", proposalId, "decision"), json.encodeToString(decision), requestKey)

    private suspend fun transition(tripId: String, action: String, version: Long, key: String): TransitionResult =
        request("POST", listOf("trips", tripId, action), json.encodeToString(TransitionRequest(version)), key)

    private suspend inline fun <reified T> request(
        method: String, path: List<String>, body: String? = null,
        requestKey: String? = null, etag: String? = null, cursor: String? = null,
    ): T = try {
        json.decodeFromString(raw(method, path, body, requestKey, etag, cursor))
    } catch (_: SerializationException) {
        throw BackendFailure("INVALID_RESPONSE")
    } catch (_: IllegalArgumentException) {
        throw BackendFailure("INVALID_RESPONSE")
    }

    private suspend fun raw(method: String, path: List<String>, body: String?, key: String?, etag: String?, cursor: String?): String {
        val root = baseUrl ?: throw BackendFailure("CONFIGURATION")
        val token = tokens.accessToken()?.takeIf { it.isNotBlank() } ?: throw BackendFailure("AUTH_REQUIRED", 401)
        require(!token.contains('\n') && !token.contains('\r'))
        if (key != null) require(key.isNotBlank() && key.length <= 128)
        val url = root.newBuilder().apply {
            addPathSegments("api/v2")
            path.forEach { addPathSegment(it) }
            cursor?.let { addQueryParameter("cursor", it) }
        }.build()
        val builder = Request.Builder().url(url).header("Accept", "application/json")
            .header("Authorization", "Bearer $token")
        key?.let { builder.header("Idempotency-Key", it) }
        etag?.let { builder.header("If-Match", it) }
        builder.method(method, body?.toRequestBody("application/json; charset=utf-8".toMediaType()))
        return execute(client.newCall(builder.build()))
    }

    private suspend fun execute(call: Call): String = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(BackendFailure("NETWORK"))
            }
            override fun onResponse(call: Call, response: Response) {
                try {
                    val text = response.use {
                        val source = it.body?.source()
                        source?.request(1_048_577)
                        if ((source?.buffer?.size ?: 0) > 1_048_576) throw BackendFailure("INVALID_RESPONSE")
                        val text = source?.readUtf8() ?: ""
                        if (!it.isSuccessful) {
                            val code = runCatching { json.decodeFromString<V2Error>(text).code }.getOrDefault("HTTP_ERROR")
                            throw BackendFailure(code, it.code)
                        }
                        text
                    }
                    if (continuation.isActive) continuation.resume(text)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(
                        if (error is BackendFailure) error else BackendFailure("NETWORK"))
                }
            }
        })
    }
}
