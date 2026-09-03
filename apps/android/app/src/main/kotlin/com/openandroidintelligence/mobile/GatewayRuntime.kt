package com.openandroidintelligence.mobile

import android.content.Context
import android.os.Build
import com.openandroidintelligence.conversation.data.GatewayAttachmentDraftCoordinator
import com.openandroidintelligence.conversation.data.GatewayCommandCatalogRepository
import com.openandroidintelligence.conversation.data.GatewayConversationRepository
import com.openandroidintelligence.conversation.ports.ConversationScope
import com.openandroidintelligence.conversation.state.WorkbenchController
import com.openandroidintelligence.gateway.attachments.AttachmentUploader
import com.openandroidintelligence.gateway.attachments.HttpAttachmentTransport
import com.openandroidintelligence.gateway.auth.AndroidKeystoreGatewayCredentialStore
import com.openandroidintelligence.gateway.auth.Ed25519DeviceKeyStore
import com.openandroidintelligence.gateway.auth.GatewayAuthClient
import com.openandroidintelligence.gateway.auth.SessionCredentials
import com.openandroidintelligence.gateway.commands.CommandCatalogClient
import com.openandroidintelligence.gateway.conversations.ConversationClient
import com.openandroidintelligence.gateway.events.InMemoryEventCursorStore
import com.openandroidintelligence.gateway.http.GatewayHttpClient
import com.openandroidintelligence.gateway.http.GatewayProfile
import com.openandroidintelligence.gateway.http.HttpsGatewayTransport
import com.openandroidintelligence.gateway.negotiation.NegotiatedLimits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.Base64

/**
 * The connection lifecycle of the app: disconnected → negotiating →
 * authenticating → connected, with the workbench wiring built only after the
 * Gateway really issued a session.
 *
 * Nothing here fabricates a session. A failed login keeps the phase at Failed
 * with the Gateway's error code, and the login screen stays the honest state.
 */
sealed interface ConnectionPhase {
    data object Disconnected : ConnectionPhase

    data object Negotiating : ConnectionPhase

    data object Authenticating : ConnectionPhase

    data class Connected(
        val gatewayUrl: String,
        val username: String,
        val limits: NegotiatedLimits?,
        val pairingSummary: String?,
    ) : ConnectionPhase

    data class Failed(val code: String) : ConnectionPhase
}

class GatewayRuntime(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val _phase = MutableStateFlow<ConnectionPhase>(ConnectionPhase.Disconnected)
    val phase: StateFlow<ConnectionPhase> = _phase.asStateFlow()

    private val _controller = MutableStateFlow<WorkbenchController?>(null)
    val controller: StateFlow<WorkbenchController?> = _controller.asStateFlow()

    private val activeThread = java.util.concurrent.atomic.AtomicReference<String?>(null)

    /** The login form's authoritative action; the UI only reflects the phase. */
    fun login(gatewayUrl: String, username: String, password: CharArray) {
        val normalized = gatewayUrl.trim().removeSuffix("/")
        if (!normalized.startsWith("https://") && !normalized.startsWith("http://")) {
            _phase.value = ConnectionPhase.Failed("AUTH_INVALID:url-scheme-required")
            return
        }
        val profileId = profileIdFor(normalized, username)
        scope.launch {
            _phase.value = ConnectionPhase.Negotiating
            val auth = authClientFor(normalized)
            val negotiation = runCatching { auth.negotiate("neg_" + newToken()) }
            val negotiated = negotiation.getOrElse { cause ->
                _phase.value = ConnectionPhase.Failed(errorCode(cause))
                return@launch
            }

            _phase.value = ConnectionPhase.Authenticating
            val credentials = runCatching {
                val publicKey = deviceKeys.publicKeyBase64Url(profileId)
                auth.loginWithPassword(
                    negotiationId = negotiated.negotiationId,
                    username = username,
                    password = password,
                    displayName = Build.MODEL ?: "Android",
                    devicePublicKeyBase64Url = publicKey,
                )
            }
            password.fill(' ')
            credentials.fold(
                onSuccess = { session ->
                    val refreshCred = session.refreshCredential
                    if (refreshCred.isNotEmpty()) {
                        runCatching {
                            keystoreCredentials.saveRefresh(profileId, refreshCred)
                            saveLastProfile(normalized, username, profileId, session)
                        }
                    }
                    establish(normalized, username, profileId, session, negotiated.limits)
                },
                onFailure = { cause -> _phase.value = ConnectionPhase.Failed(errorCode(cause)) },
            )
        }
    }

    /** Clears a failed attempt so the login form can be re-entered cleanly. */
    fun resetFailure() {
        if (_phase.value is ConnectionPhase.Failed) {
            _phase.value = ConnectionPhase.Disconnected
        }
    }

    fun logout(revokeRefresh: Boolean) {
        val current = _phase.value as? ConnectionPhase.Connected ?: return
        val profileId = profileIdFor(current.gatewayUrl, current.username)
        val accountId = lastAccountId ?: current.username
        val deviceId = lastDeviceId ?: "pre-auth"
        val sessionId = lastSessionId
        scope.launch {
            runCatching {
                authClientFor(current.gatewayUrl).logout(
                    accessToken = accessTokenHolder ?: "",
                    accountId = accountId,
                    deviceId = deviceId,
                    sessionId = sessionId ?: "",
                    revokeRefresh = revokeRefresh,
                )
            }.onFailure { cause ->
                _phase.value = ConnectionPhase.Failed(errorCode(cause))
                return@launch
            }
            runCatching {
                keystoreCredentials.clearRefresh(profileId)
                clearLastProfile()
            }
            teardown()
        }
    }

    /** Attempts silent session recovery on cold start if valid credentials exist. */
    fun restoreSessionIfAvailable() {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastUrl = prefs.getString(KEY_LAST_GATEWAY, null) ?: return
        val lastUser = prefs.getString(KEY_LAST_USER, null) ?: return
        val lastProfileId = prefs.getString(KEY_LAST_PROFILE, null) ?: return
        val storedAccountId = prefs.getString(KEY_LAST_ACCOUNT, null) ?: return
        val storedDeviceId = prefs.getString(KEY_LAST_DEVICE, null) ?: return
        val storedSessionId = prefs.getString(KEY_LAST_SESSION, null)
        lastAccountId = storedAccountId
        lastDeviceId = storedDeviceId
        lastSessionId = storedSessionId

        val refreshBytes = runCatching { keystoreCredentials.loadRefresh(lastProfileId) }.getOrNull() ?: return
        if (refreshBytes.isEmpty()) return

        scope.launch {
            _phase.value = ConnectionPhase.Negotiating
            val auth = authClientFor(lastUrl)
            val negotiated = runCatching { auth.negotiate("neg_" + newToken()) }.getOrElse {
                _phase.value = ConnectionPhase.Disconnected
                return@launch
            }

            _phase.value = ConnectionPhase.Authenticating
            val session = runCatching {
                auth.refresh(
                    accountId = storedAccountId,
                    deviceId = storedDeviceId,
                    negotiationId = negotiated.negotiationId,
                    refreshCredential = refreshBytes,
                )
            }.getOrElse { cause ->
                // Only an explicit Gateway refusal may destroy the local
                // credential. A 404 from an older Gateway build, a 5xx or a
                // network drop says nothing about whether the stored refresh
                // credential is still valid: wiping it here would turn a
                // recoverable outage into a permanent logout.
                if (credentialRevoked(cause)) {
                    keystoreCredentials.clearRefresh(lastProfileId)
                    clearLastProfile()
                }
                _phase.value = ConnectionPhase.Disconnected
                return@launch
            }

            val newRefresh = session.refreshCredential
            if (newRefresh.isNotEmpty()) {
                runCatching {
                    keystoreCredentials.saveRefresh(lastProfileId, newRefresh)
                }
            }
            establish(lastUrl, lastUser, lastProfileId, session, negotiated.limits)
        }
    }

    /**
     * Whether the Gateway explicitly refused the credential.
     *
     * `GatewayAuthClient` surfaces `AUTHENTICATION_FAILED:<code-or-status>`, so
     * the decisive part is what follows the last colon.
     */
    private fun credentialRevoked(cause: Throwable): Boolean {
        val text = cause.message ?: return false
        if (text.contains("REFRESH_REUSED")) return true
        return text.substringAfterLast(':').trim().toIntOrNull() in setOf(401, 403)
    }

    private fun establish(
        gatewayUrl: String,
        username: String,
        profileId: String,
        session: SessionCredentials,
        limits: NegotiatedLimits?,
    ) {
        val profile = GatewayProfile(
            accountId = session.accountId,
            deviceId = session.deviceId,
            sessionId = session.sessionId,
            gatewayBaseUrl = gatewayUrl,
            accessToken = session.accessToken,
        )
        val transport = HttpsGatewayTransport(profile)
        val http = GatewayHttpClient(
            profile = profile,
            transport = transport,
            signer = { preimage -> deviceKeys.sign(profileId, preimage) },
            cursorStore = InMemoryEventCursorStore(),
        )
        val conversationClient = ConversationClient(http)
        val repository = GatewayConversationRepository(conversationClient) { activeThread.get() }
        val catalogRepository = GatewayCommandCatalogRepository(CommandCatalogClient(http))
        accessTokenHolder = session.accessToken
        lastAccountId = session.accountId
        lastDeviceId = session.deviceId
        lastSessionId = session.sessionId

        val attachmentTransport = HttpAttachmentTransport(http)
        val uploader = AttachmentUploader(attachmentTransport)
        val gate = com.openandroidintelligence.conversation.attachment.AttachmentSubmissionGate(
            onSubmit = { /* submission of attachment-only messages routes through the controller */ },
        )
        val attachmentCoordinator = GatewayAttachmentDraftCoordinator(uploader, gate, scope)

        val conversationScope = ConversationScope(
            profileId = profileId,
            gatewayId = gatewayUrl,
            accountId = session.accountId,
            installId = "install_local",
        )

        _controller.value = WorkbenchController(
            scope = scope,
            repository = repository,
            catalogRepository = catalogRepository,
            scopeFactory = { conversationScope },
            attachmentCoordinator = attachmentCoordinator,
            onActiveThreadChanged = { threadId -> activeThread.set(threadId) },
        )
        _phase.value = ConnectionPhase.Connected(
            gatewayUrl = gatewayUrl,
            username = username,
            limits = limits,
            pairingSummary = session.pairingSummary,
        )
    }

    private fun teardown() {
        _controller.value = null
        accessTokenHolder = null
        activeThread.set(null)
        _phase.value = ConnectionPhase.Disconnected
    }

    private fun authClientFor(gatewayUrl: String): GatewayAuthClient = GatewayAuthClient(
        transport = HttpsGatewayTransport(
            GatewayProfile(
                accountId = "pre-auth",
                deviceId = "pre-auth",
                sessionId = "pre-auth",
                gatewayBaseUrl = gatewayUrl,
            ),
        ),
        installationId = installationId(),
        appVersion = "2.0.0",
        platformApi = Build.VERSION.SDK_INT,
    )

    private val deviceKeys: Ed25519DeviceKeyStore by lazy {
        Ed25519DeviceKeyStore(File(context.filesDir, "gateway-credentials"))
    }

    private val keystoreCredentials: AndroidKeystoreGatewayCredentialStore by lazy {
        AndroidKeystoreGatewayCredentialStore(File(context.filesDir, "keystore-credentials").also { it.mkdirs() })
    }

    private var accessTokenHolder: String? = null

    /** The session identity of the last successful login, for refresh/logout. */
    private var lastAccountId: String? = null
    private var lastDeviceId: String? = null
    private var lastSessionId: String? = null

    private fun installationId(): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_INSTALL, null) ?: "install_" + newToken().also {
            prefs.edit().putString(KEY_INSTALL, it).apply()
        }
    }

    private fun saveLastProfile(gatewayUrl: String, username: String, profileId: String, session: SessionCredentials) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_GATEWAY, gatewayUrl)
            .putString(KEY_LAST_USER, username)
            .putString(KEY_LAST_PROFILE, profileId)
            .putString(KEY_LAST_ACCOUNT, session.accountId)
            .putString(KEY_LAST_DEVICE, session.deviceId)
            .putString(KEY_LAST_SESSION, session.sessionId)
            .apply()
        lastAccountId = session.accountId
        lastDeviceId = session.deviceId
        lastSessionId = session.sessionId
    }

    private fun clearLastProfile() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_LAST_GATEWAY)
            .remove(KEY_LAST_USER)
            .remove(KEY_LAST_PROFILE)
            .remove(KEY_LAST_ACCOUNT)
            .remove(KEY_LAST_DEVICE)
            .remove(KEY_LAST_SESSION)
            .apply()
        lastAccountId = null
        lastDeviceId = null
        lastSessionId = null
    }

    private fun profileIdFor(gatewayUrl: String, username: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString("$gatewayUrl|$username".toByteArray(Charsets.UTF_8))

    private fun newToken(): String =
        java.util.UUID.randomUUID().toString().replace("-", "")

    private fun errorCode(cause: Throwable): String =
        cause.message?.takeIf { it.isNotBlank() } ?: cause::class.java.simpleName

    private companion object {
        const val PREFS_NAME = "open_android_intelligence_runtime"
        const val KEY_INSTALL = "installation_id"
        const val KEY_LAST_GATEWAY = "last_gateway_url"
        const val KEY_LAST_USER = "last_username"
        const val KEY_LAST_PROFILE = "last_profile_id"
        const val KEY_LAST_ACCOUNT = "last_account_id"
        const val KEY_LAST_DEVICE = "last_device_id"
        const val KEY_LAST_SESSION = "last_session_id"
    }
}
