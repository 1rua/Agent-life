package com.agentlife.gateway.auth

import com.agentlife.gateway.http.GatewayResponse
import com.agentlife.gateway.http.HttpsGatewayTransport
import com.agentlife.gateway.http.RawHeader
import com.agentlife.gateway.http.SignedGatewayRequest
import com.agentlife.gateway.http.WireRequest
import com.agentlife.gateway.http.WireResponse
import com.agentlife.gateway.negotiation.NegotiationClient
import com.agentlife.gateway.negotiation.NegotiationResult
import com.agentlife.gateway.schema.Json
import com.agentlife.gateway.schema.JsonFields
import com.agentlife.gateway.schema.JsonValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Everything the app needs to build an authenticated client after login. */
data class SessionCredentials(
    val accountId: String,
    val deviceId: String,
    val sessionId: String,
    val accessToken: String,
    val refreshCredential: ByteArray,
    val pairingSummary: String?,
)

/**
 * Password login, refresh rotation and session termination over real HTTPS.
 *
 * These endpoints run before a signed session exists, so they ride the plain
 * [HttpsGatewayTransport]: negotiate and login carry no signature, and logout
 * presents the bearer token the login already returned. The password exists
 * only inside [loginWithPassword] and is scrubbed before returning, mirroring
 * [GatewaySessionManager]'s rules.
 */
class GatewayAuthClient(
    private val transport: HttpsGatewayTransport,
    private val installationId: String,
    private val appVersion: String,
    private val platformApi: Int,
) {
    private val negotiation = NegotiationClient(
        execute = { request ->
            val wire = transport.execute(request.toWire())
            GatewayResponse(status = wire.status, headers = wire.headers, body = wire.body)
        },
        installationId = installationId,
        appVersion = appVersion,
        platformApi = platformApi,
    )

    suspend fun negotiate(negotiationId: String): NegotiationResult =
        withContext(Dispatchers.IO) { negotiation.negotiate(negotiationId) }

    suspend fun loginWithPassword(
        negotiationId: String,
        username: String,
        password: CharArray,
        displayName: String,
        devicePublicKeyBase64Url: String,
    ): SessionCredentials = withContext(Dispatchers.IO) {
        require(username.isNotBlank()) { "AUTH_INVALID:username" }
        require(password.isNotEmpty()) { "AUTH_INVALID:password" }
        try {
            val payload = mapOf(
                "negotiationId" to negotiationId,
                "username" to username,
                "password" to String(password),
                "installation" to mapOf(
                    "installationId" to installationId,
                    "displayName" to displayName,
                    "devicePublicKey" to devicePublicKeyBase64Url,
                ),
            )
            val body = postJson("/agent-life/v2/sessions/password", payload)
            parseSession(body, "LOGIN_FAILED")
        } finally {
            password.fill('\u0000')
        }
    }

    suspend fun refresh(refreshCredential: ByteArray): SessionCredentials =
        withContext(Dispatchers.IO) {
            val payload = mapOf(
                "installationId" to installationId,
                "refreshCredential" to String(refreshCredential, Charsets.ISO_8859_1),
            )
            val body = postJson("/agent-life/v2/sessions/refresh", payload)
            parseSession(body, "REFRESH_FAILED")
        }

    suspend fun logout(accessToken: String, revokeRefresh: Boolean) {
        withContext(Dispatchers.IO) {
            val response = transport.execute(
                WireRequest(
                    method = "DELETE",
                    target = "/agent-life/v2/sessions/current?revokeRefresh=$revokeRefresh",
                    headers = listOf(
                        RawHeader("Authorization", "Bearer $accessToken"),
                        RawHeader("Accept", "application/json"),
                    ),
                ),
            )
            if (response.status !in 200..299) {
                throw IllegalStateException("LOGOUT_FAILED:${response.status}")
            }
        }
    }

    private suspend fun postJson(target: String, payload: Map<String, Any?>): JsonValue.JObject {
        val response = transport.execute(
            WireRequest(
                method = "POST",
                target = target,
                headers = listOf(
                    RawHeader("Content-Type", "application/json"),
                    RawHeader("Accept", "application/json"),
                ),
                body = Json.canonical(Json.of(payload)).toByteArray(Charsets.UTF_8),
            ),
        )
        if (response.status !in 200..299) {
            throw authError("AUTHENTICATION_FAILED", response)
        }
        return JsonFields.obj(
            runCatching { Json.parse(String(response.body, Charsets.UTF_8)) }.getOrNull(),
        ) ?: throw IllegalStateException("AUTHENTICATION_FAILED:malformed")
    }

    private suspend fun authError(prefix: String, response: WireResponse): IllegalStateException {
        val body = runCatching { Json.parse(String(response.body, Charsets.UTF_8)) }.getOrNull()
        val code = JsonFields.string(JsonFields.obj(body), "errorCode")
        return IllegalStateException("$prefix:${code ?: response.status}")
    }

    private fun parseSession(body: JsonValue.JObject, prefix: String): SessionCredentials {
        val accountId = JsonFields.string(body, "accountId")
            ?: throw IllegalStateException("$prefix:missing-account")
        val deviceId = JsonFields.string(body, "deviceId")
            ?: throw IllegalStateException("$prefix:missing-device")
        val sessionId = JsonFields.string(body, "sessionId")
            ?: throw IllegalStateException("$prefix:missing-session")
        val accessToken = JsonFields.string(body, "accessToken")
            ?: JsonFields.string(body, "token")
            ?: throw IllegalStateException("$prefix:missing-token")
        val refresh = (JsonFields.field(body, "refreshCredential") as? JsonValue.JString)
            ?.value?.toByteArray(Charsets.ISO_8859_1)
            ?: throw IllegalStateException("$prefix:missing-refresh")
        return SessionCredentials(
            accountId = accountId,
            deviceId = deviceId,
            sessionId = sessionId,
            accessToken = accessToken,
            refreshCredential = refresh,
            pairingSummary = JsonFields.string(body, "pairingSummary"),
        )
    }

    private fun newNegotiationId(): String =
        "neg_" + java.util.UUID.randomUUID().toString().replace("-", "")
    private fun SignedGatewayRequest.toWire() = WireRequest(
        method = method,
        target = target,
        headers = headers,
        body = body,
    )
}
