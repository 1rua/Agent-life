package com.openandroidintelligence.gateway.negotiation

import com.openandroidintelligence.gateway.http.GatewayResponse
import com.openandroidintelligence.gateway.http.RawHeader
import com.openandroidintelligence.gateway.http.SignedGatewayRequest
import com.openandroidintelligence.gateway.schema.Json
import com.openandroidintelligence.gateway.schema.JsonFields

/** What one connection actually negotiated. */
data class NegotiatedLimits(
    val maxSingleAttachmentBytes: Long?,
    val maxMessageAttachmentBytes: Long?,
    val allowedMediaTypes: List<String>,
    val attachmentTtlSeconds: Long?,
    val eventRetentionSeconds: Long?,
)

data class NegotiationResult(
    val negotiationId: String,
    val protocolMajor: Int,
    val protocolMinor: Int,
    val deploymentId: String?,
    val tlsSpkiSha256: String?,
    val messages: String?,
    val attachments: String?,
    val events: String?,
    val deviceRequests: String?,
    val limits: NegotiatedLimits,
)

/**
 * Protocol negotiation, run before authentication.
 *
 * The endpoint carries no secrets, so the executor is injectable: pre-auth the
 * caller backs it with the plain HTTPS transport, because the signed client
 * cannot exist before a session does.
 */
class NegotiationClient(
    private val execute: suspend (SignedGatewayRequest) -> GatewayResponse,
    private val installationId: String,
    private val appVersion: String,
    private val platformApi: Int,
) {

    suspend fun negotiate(negotiationId: String): NegotiationResult {
        val payload = mapOf(
            "negotiationId" to negotiationId,
            "protocol" to mapOf("major" to PROTOCOL_MAJOR, "minor" to PROTOCOL_MINOR),
            "client" to mapOf(
                "installationId" to installationId,
                "appVersion" to appVersion,
                "platform" to "android",
                "platformApi" to platformApi,
            ),
            "features" to mapOf(
                "auth" to listOf("password", "account-invitation", "refresh", "device-key"),
                "messages" to listOf("chat-v1", "message-batches-v1"),
                "attachments" to listOf("staged-sha256-v1", "screen-selection-v1"),
                "events" to listOf("sse-cursor-v1"),
                "deviceRequests" to listOf("risk-queue-v1"),
            ),
        )
        val response = execute(
            SignedGatewayRequest(
                method = "POST",
                target = "/open-android-intelligence/v2/negotiate",
                headers = listOf(
                    RawHeader("Content-Type", "application/json"),
                    RawHeader("Accept", "application/json"),
                ),
                body = Json.canonical(Json.of(payload)).toByteArray(Charsets.UTF_8),
            ),
        )
        if (response.status == 406 || response.status == 409) {
            throw IllegalStateException("PROTOCOL_INCOMPATIBLE:${response.status}")
        }
        if (response.status !in 200..299) {
            throw IllegalStateException("NEGOTIATION_FAILED:${response.status}")
        }
        val body = JsonFields.obj(
            runCatching { Json.parse(String(response.body, Charsets.UTF_8)) }.getOrNull(),
        ) ?: throw IllegalStateException("NEGOTIATION_FAILED:malformed")

        val result = JsonFields.obj(JsonFields.field(body, "data")) ?: body
        val protocol = JsonFields.obj(JsonFields.field(result, "protocol"))
        val features = JsonFields.obj(JsonFields.field(result, "features"))
        val limits = JsonFields.obj(JsonFields.field(result, "limits"))
        val identity = JsonFields.obj(JsonFields.field(result, "gatewayIdentity"))

        return NegotiationResult(
            negotiationId = JsonFields.string(result, "negotiationId") ?: negotiationId,
            protocolMajor = JsonFields.int(protocol, "major") ?: PROTOCOL_MAJOR,
            protocolMinor = JsonFields.int(protocol, "minor") ?: PROTOCOL_MINOR,
            deploymentId = JsonFields.string(identity, "deploymentId"),
            tlsSpkiSha256 = JsonFields.string(identity, "tlsSpkiSha256"),
            messages = JsonFields.string(features, "messages"),
            attachments = JsonFields.string(features, "attachments"),
            events = JsonFields.string(features, "events"),
            deviceRequests = JsonFields.string(features, "deviceRequests"),
            limits = NegotiatedLimits(
                maxSingleAttachmentBytes = JsonFields.long(limits, "maxSingleAttachmentBytes"),
                maxMessageAttachmentBytes = JsonFields.long(limits, "maxMessageAttachmentBytes"),
                allowedMediaTypes = JsonFields.strings(limits, "allowedMediaTypes"),
                attachmentTtlSeconds = JsonFields.long(limits, "attachmentTtlSeconds"),
                eventRetentionSeconds = JsonFields.long(limits, "eventRetentionSeconds"),
            ),
        )
    }

    private companion object {
        const val PROTOCOL_MAJOR = 2
        const val PROTOCOL_MINOR = 0
    }
}
