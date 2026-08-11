package com.agentlife.control

/** Closed risk capabilities. No arbitrary command or UI capability exists. */
enum class ControlCapability {
    CLIPBOARD_WRITE,
    SMS_SEND,
    CALENDAR_WRITE,
    ALARMS_WRITE,
    DEVICE_NOTIFY,
    SCREEN_CONTROL,
    SHELL_RESTRICTED,
}

enum class ControlActionKind {
    TYPED_WRITE,
    SCREEN_SEMANTIC,
    RESTRICTED_TEMPLATE,
}

/** A canonical revision snapshot used for every high-risk request. */
data class ControlRevision(
    val pairingGeneration: ULong,
    val connectionGeneration: ULong,
    val authorizationEpoch: ULong,
    val policyRevision: ULong,
)

data class UserConfirmation(
    val confirmationId: String,
    val parameterDigest: String,
    val confirmedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
) {
    init {
        require(confirmationId.isNotBlank())
        require(parameterDigest.isNotBlank())
        require(expiresAtEpochMs >= confirmedAtEpochMs)
    }
}

sealed interface ControlAction {
    val capability: ControlCapability
    val kind: ControlActionKind
    val parameterDigest: String
}

/** Closed, typed write actions. Fields are deliberately not a dynamic map. */
sealed interface TypedWriteAction : ControlAction {
    override val kind: ControlActionKind get() = ControlActionKind.TYPED_WRITE

    data class ClipboardWrite(
        val text: String,
        override val parameterDigest: String,
    ) : TypedWriteAction {
        override val capability: ControlCapability = ControlCapability.CLIPBOARD_WRITE
    }

    data class SmsSend(
        val recipient: String,
        val body: String,
        val subscriptionId: String?,
        override val parameterDigest: String,
    ) : TypedWriteAction {
        override val capability: ControlCapability = ControlCapability.SMS_SEND
    }

    data class CalendarCreate(
        val calendarId: String,
        val title: String,
        val startsAtEpochMs: Long,
        val endsAtEpochMs: Long,
        val timeZone: String,
        override val parameterDigest: String,
    ) : TypedWriteAction {
        override val capability: ControlCapability = ControlCapability.CALENDAR_WRITE
    }

    data class CalendarUpdate(
        val calendarId: String,
        val eventId: String,
        val title: String,
        val startsAtEpochMs: Long,
        val endsAtEpochMs: Long,
        val timeZone: String,
        override val parameterDigest: String,
    ) : TypedWriteAction {
        override val capability: ControlCapability = ControlCapability.CALENDAR_WRITE
    }

    data class AlarmCreate(
        val triggerAtEpochMs: Long,
        val label: String,
        override val parameterDigest: String,
    ) : TypedWriteAction {
        override val capability: ControlCapability = ControlCapability.ALARMS_WRITE
    }

    data class AlarmModifyOwned(
        val ownedAlarmId: String,
        val triggerAtEpochMs: Long,
        val label: String,
        override val parameterDigest: String,
    ) : TypedWriteAction {
        override val capability: ControlCapability = ControlCapability.ALARMS_WRITE
    }

    data class DeviceNotify(
        val title: String,
        val body: String,
        override val parameterDigest: String,
    ) : TypedWriteAction {
        override val capability: ControlCapability = ControlCapability.DEVICE_NOTIFY
    }
}

data class ScreenTargetIdentity(
    val packageName: String,
    val windowId: String,
    val windowGeneration: ULong,
) {
    init {
        require(packageName.isNotBlank())
        require(windowId.isNotBlank())
    }
}

sealed interface ScreenSemanticAction : ControlAction {
    override val capability: ControlCapability get() = ControlCapability.SCREEN_CONTROL
    override val kind: ControlActionKind get() = ControlActionKind.SCREEN_SEMANTIC

    data class TapResource(
        val resourceId: String,
        override val parameterDigest: String,
    ) : ScreenSemanticAction

    data class SetText(
        val resourceId: String,
        val text: String,
        override val parameterDigest: String,
    ) : ScreenSemanticAction

    data class Scroll(
        val resourceId: String,
        val direction: ScrollDirection,
        override val parameterDigest: String,
    ) : ScreenSemanticAction

    data class Back(override val parameterDigest: String) : ScreenSemanticAction
}

enum class ScrollDirection { FORWARD, BACKWARD }

const val MAX_SCREEN_SESSION_TTL_MS: Long = 30 * 60 * 1000L

data class ScreenControlSession(
    val screenSessionId: String,
    val target: ScreenTargetIdentity,
    val startedAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val windowGeneration: ULong,
) {
    init {
        require(screenSessionId.isNotBlank())
        require(expiresAtEpochMs >= startedAtEpochMs)
        require(expiresAtEpochMs - startedAtEpochMs <= MAX_SCREEN_SESSION_TTL_MS)
        require(windowGeneration == target.windowGeneration)
    }

    fun isUsable(nowEpochMs: Long, currentWindow: ScreenTargetIdentity): Boolean =
        nowEpochMs < expiresAtEpochMs && target == currentWindow && windowGeneration == currentWindow.windowGeneration
}

enum class RestrictedCommandTemplateId {
    RECONNECT_BRIDGE,
    PURGE_EXPIRED_LOCAL_DATA,
    OPEN_APP_SETTINGS,
}

/** Reviewed templates contain no executable, argv, environment or script. */
sealed interface RestrictedCommandTemplate : ControlAction {
    override val capability: ControlCapability get() = ControlCapability.SHELL_RESTRICTED
    override val kind: ControlActionKind get() = ControlActionKind.RESTRICTED_TEMPLATE
    val templateId: RestrictedCommandTemplateId

    data object ReconnectBridge : RestrictedCommandTemplate {
        override val templateId = RestrictedCommandTemplateId.RECONNECT_BRIDGE
        override val parameterDigest = "template:reconnect_bridge"
    }

    data object PurgeExpiredLocalData : RestrictedCommandTemplate {
        override val templateId = RestrictedCommandTemplateId.PURGE_EXPIRED_LOCAL_DATA
        override val parameterDigest = "template:purge_expired_local_data"
    }

    data object OpenAppSettings : RestrictedCommandTemplate {
        override val templateId = RestrictedCommandTemplateId.OPEN_APP_SETTINGS
        override val parameterDigest = "template:open_app_settings"
    }
}

data class ControlAuthorizationRequest(
    val requestId: String,
    val action: ControlAction,
    val revision: ControlRevision,
    val confirmation: UserConfirmation?,
    val expiresAtEpochMs: Long,
    val screenSession: ScreenControlSession? = null,
) {
    init {
        require(requestId.isNotBlank())
        require(expiresAtEpochMs >= 0L)
    }
}

data class ControlGrant(
    val agentMayRequest: Boolean,
    val capabilities: Set<ControlCapability>,
    val revision: ControlRevision,
)

enum class ControlDenialReason {
    NO_LOCAL_GRANT,
    AGENT_REQUESTS_DISABLED,
    CONFIRMATION_REQUIRED,
    CONFIRMATION_MISMATCH,
    REQUEST_EXPIRED,
    PAIRING_GENERATION_STALE,
    CONNECTION_GENERATION_STALE,
    AUTHORIZATION_EPOCH_STALE,
    POLICY_REVISION_STALE,
    SESSION_NOT_ACTIVE,
    WINDOW_CHANGED,
    TEMPLATE_NOT_GRANTED,
}

data class AuthorizedControlRequest internal constructor(
    val request: ControlAuthorizationRequest,
)

sealed interface ControlAuthorization {
    data class Allowed internal constructor(val request: AuthorizedControlRequest) : ControlAuthorization
    data class Denied(val reason: ControlDenialReason) : ControlAuthorization
}

interface ControlAuthorizer {
    fun authorize(
        request: ControlAuthorizationRequest,
        grant: ControlGrant?,
        revision: ControlRevision,
        activeSession: Boolean,
        currentWindow: ScreenTargetIdentity?,
        nowEpochMs: Long,
    ): ControlAuthorization
}

/** Deny-first local gate. It only mints an opaque authorized request. */
class DefaultControlAuthorizer : ControlAuthorizer {
    override fun authorize(
        request: ControlAuthorizationRequest,
        grant: ControlGrant?,
        revision: ControlRevision,
        activeSession: Boolean,
        currentWindow: ScreenTargetIdentity?,
        nowEpochMs: Long,
    ): ControlAuthorization {
        if (grant == null) return ControlAuthorization.Denied(ControlDenialReason.NO_LOCAL_GRANT)
        if (!grant.agentMayRequest) return ControlAuthorization.Denied(ControlDenialReason.AGENT_REQUESTS_DISABLED)
        if (!activeSession) return ControlAuthorization.Denied(ControlDenialReason.SESSION_NOT_ACTIVE)
        if (revision == request.revision) {
            // Exact equality is required; the branch is intentionally explicit
            // so a future caller cannot compare only one generation.
        } else {
            if (revision.pairingGeneration != request.revision.pairingGeneration) return ControlAuthorization.Denied(ControlDenialReason.PAIRING_GENERATION_STALE)
            if (revision.connectionGeneration != request.revision.connectionGeneration) return ControlAuthorization.Denied(ControlDenialReason.CONNECTION_GENERATION_STALE)
            if (revision.authorizationEpoch != request.revision.authorizationEpoch) return ControlAuthorization.Denied(ControlDenialReason.AUTHORIZATION_EPOCH_STALE)
            return ControlAuthorization.Denied(ControlDenialReason.POLICY_REVISION_STALE)
        }
        if (grant.revision != request.revision) return ControlAuthorization.Denied(ControlDenialReason.POLICY_REVISION_STALE)
        if (!grant.capabilities.contains(request.action.capability)) return ControlAuthorization.Denied(ControlDenialReason.TEMPLATE_NOT_GRANTED)
        if (request.expiresAtEpochMs <= nowEpochMs) return ControlAuthorization.Denied(ControlDenialReason.REQUEST_EXPIRED)

        val confirmation = request.confirmation ?: return ControlAuthorization.Denied(ControlDenialReason.CONFIRMATION_REQUIRED)
        if (confirmation.parameterDigest != request.action.parameterDigest
            || confirmation.expiresAtEpochMs <= nowEpochMs
            || confirmation.expiresAtEpochMs < request.expiresAtEpochMs) {
            return ControlAuthorization.Denied(ControlDenialReason.CONFIRMATION_MISMATCH)
        }

        val screenSession = request.screenSession
        if (request.action.kind == ControlActionKind.SCREEN_SEMANTIC) {
            if (screenSession == null || currentWindow == null || !screenSession.isUsable(nowEpochMs, currentWindow)) {
                return ControlAuthorization.Denied(ControlDenialReason.WINDOW_CHANGED)
            }
        }
        return ControlAuthorization.Allowed(AuthorizedControlRequest(request))
    }
}

sealed interface ControlResult {
    data object Accepted : ControlResult
    data class Failed(val reason: String) : ControlResult
}

interface TypedWritePort {
    suspend fun apply(request: AuthorizedControlRequest): ControlResult
}

interface ScreenSemanticControlPort {
    suspend fun apply(request: AuthorizedControlRequest, session: ScreenControlSession): ControlResult
}

interface RestrictedCommandPort {
    suspend fun apply(request: AuthorizedControlRequest): ControlResult
}
