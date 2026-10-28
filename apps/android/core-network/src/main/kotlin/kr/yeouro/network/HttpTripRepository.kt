package kr.yeouro.network

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kr.yeouro.domain.BackendFailure
import kr.yeouro.domain.TripRepository
import kr.yeouro.model.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object BackendConfiguration {
    /** Cleartext is restricted to Android's loopback-to-host emulator address, debug only. */
    fun permittedUrl(value: String, debug: Boolean): HttpUrl? {
        val url = value.toHttpUrlOrNull() ?: return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) return null
        return url.takeIf { it.scheme == "https" || (debug && it.scheme == "http" && it.host == "10.0.2.2") }
    }
}

class HttpTripRepository internal constructor(
    private val baseUrl: HttpUrl?,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build(),
) : TripRepository {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
    companion object {
        fun configured(baseUrl: String, debug: Boolean): HttpTripRepository =
            HttpTripRepository(BackendConfiguration.permittedUrl(baseUrl, debug))
    }
    override suspend fun health() { raw("GET", listOf("health")) }
    override suspend fun createTrip(place: Place): SessionResponse =
        request("POST", listOf("trips"), body = json.encodeToString(CreateTripRequest(place)))
    override suspend fun join(code: String, displayName: String, consent: Boolean): SessionResponse =
        request("POST", listOf("invites", "join"), body = json.encodeToString(JoinRequest(code, displayName, consent)))
    override suspend fun trip(id: String, token: String): Trip = request("GET", listOf("trips", id), token)
    override suspend fun invite(id: String, token: String, consent: Boolean, role: Role): Invite =
        request("POST", listOf("trips", id, "invites"), token, json.encodeToString(CreateInviteRequest(consent, role)))
    override suspend fun ready(id: String, token: String, ready: Boolean): Trip =
        request("PUT", listOf("trips", id, "readiness"), token, json.encodeToString(ReadyRequest(ready)))
    override suspend fun propose(id: String, token: String, requestId: String, place: Place): Trip =
        request("POST", listOf("trips", id, "proposals"), token, json.encodeToString(ProposalRequest(requestId, place)))
    override suspend fun decide(id: String, token: String, proposal: Proposal, decision: Decision): Trip =
        request("POST", listOf("trips", id, "proposals", proposal.id, "decision"), token,
            json.encodeToString(DecisionRequest(decision, proposal.version)))
    override suspend fun report(id: String, token: String, proposalId: String, result: NavigationResultRequest): Trip =
        request("POST", listOf("trips", id, "proposals", proposalId, "navigation-result"), token, json.encodeToString(result))
    override suspend fun location(id: String, token: String, location: SharedLocation): Trip =
        request("PUT", listOf("trips", id, "location"), token, json.encodeToString(location))
    override suspend fun revoke(id: String, token: String): Trip = request("POST", listOf("trips", id, "sharing", "revoke"), token, "{}")
    override suspend fun end(id: String, token: String): Trip = request("POST", listOf("trips", id, "end"), token, "{}")

    private suspend inline fun <reified T> request(method: String, path: List<String>, token: String? = null, body: String? = null): T {
        val raw = raw(method, path, token, body)
        return try { json.decodeFromString<T>(raw) }
        catch (_: SerializationException) { throw BackendFailure("INVALID_RESPONSE") }
        catch (_: IllegalArgumentException) { throw BackendFailure("INVALID_RESPONSE") }
    }

    private suspend fun raw(method: String, path: List<String>, token: String? = null, body: String? = null): String {
        val root = baseUrl ?: throw BackendFailure("CONFIGURATION")
        val url = root.newBuilder().apply {
            addPathSegments("api/v1")
            path.forEach { addPathSegment(it) }
        }.build()
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        if (token != null) builder.header("Authorization", "Bearer $token")
        builder.method(method, body?.toRequestBody("application/json; charset=utf-8".toMediaType()))
        val call = client.newCall(builder.build())
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(BackendFailure("NETWORK"))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use {
                            val source = it.body?.source()
                            source?.request(1_048_577)
                            if ((source?.buffer?.size ?: 0) > 1_048_576) throw BackendFailure("INVALID_RESPONSE")
                            val text = source?.readUtf8() ?: ""
                            if (!it.isSuccessful) {
                                val error = runCatching { json.decodeFromString<ApiError>(text) }.getOrNull()
                                throw BackendFailure(error?.code ?: "HTTP_ERROR", it.code)
                            }
                            text
                        }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(
                            if (error is BackendFailure) error else BackendFailure("NETWORK"))
                    }
                }
            })
        }
    }
}
