package com.agentlife.gateway.http

import com.agentlife.gateway.events.EventCursorStore
import com.agentlife.gateway.events.GatewayEvent
import com.agentlife.gateway.events.SseParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

data class GatewayProfile(
    val accountId: String,
    val deviceId: String,
    val sessionId: String,
    val gatewayBaseUrl: String,
    /** SPKI SHA-256 pins, in base64. Empty means system trust only. */
    val pinnedSpkiSha256: Set<String> = emptySet(),
)

data class SignedGatewayRequest(
    val method: String,
    val target: String,
    val body: ByteArray = ByteArray(0),
    val headers: List<RawHeader> = emptyList(),
)

data class GatewayResponse(
    val status: Int,
    val headers: List<RawHeader>,
    val body: ByteArray,
)

/**
 * The app's Gateway client.
 *
 * Requests are signed over the canonical target actually sent and the exact body
 * bytes, and response headers are validated from the raw list before use, so a
 * duplicated or folded singleton cannot resolve to whichever value the parser
 * happened to keep.
 */
class GatewayHttpClient(
    private val profile: GatewayProfile,
    private val transport: GatewayByteTransport,
    private val signer: (ByteArray) -> ByteArray,
    private val cursorStore: EventCursorStore,
) {

    suspend fun execute(request: SignedGatewayRequest): GatewayResponse {
        val validatedHeaders = RawHeaders.validate(request.headers)

        val input = SignedRequestInput(
            method = request.method,
            target = request.target,
            accountId = profile.accountId,
            deviceId = profile.deviceId,
            sessionId = profile.sessionId,
            requestId = newRequestId(),
            timestamp = RequestSigner.formatTimestamp(
                java.time.Instant.ofEpochMilli(java.time.Instant.now().toEpochMilli()),
            ),
            nonce = newNonce(),
            body = request.body,
        )
        val signature = signer(RequestSigner.preimage(input))

        val headers = validatedHeaders + listOf(
            RawHeader("Authorization", "Agent-Life-Ed25519 ${java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(signature)}"),
            RawHeader("X-Agent-Life-Request-Id", input.requestId),
            RawHeader("X-Agent-Life-Timestamp", input.timestamp),
            RawHeader("X-Agent-Life-Nonce", input.nonce),
        )

        return withContext(Dispatchers.IO) {
            val response = transport.execute(
                WireRequest(input.method, input.target, headers, request.body),
            )
            GatewayResponse(response.status, RawHeaders.validate(response.headers), response.body)
        }
    }

    /**
     * Opens the event stream from the stored cursor.
     *
     * The cursor is only advanced for a fully framed event, so a disconnect in
     * the middle of a frame resumes from the previous complete one rather than
     * skipping the remainder.
     */
    fun events(): Flow<GatewayEvent> = flow {
        val cursor = cursorStore.load(profile.accountId)
        val target = if (cursor == null) {
            "/agent-life/v2/events"
        } else {
            "/agent-life/v2/events?cursor=$cursor"
        }

        val parser = SseParser { event ->
            event.id?.let { cursorStore.save(profile.accountId, it) }
        }

        transport.eventStream(WireRequest("GET", target)).collect { chunk ->
            for (event in parser.feedBytes(chunk)) emit(event)
        }
    }.flowOn(Dispatchers.IO)

    private fun newRequestId(): String =
        "req" + java.util.UUID.randomUUID().toString().replace("-", "").take(20)

    private fun newNonce(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
