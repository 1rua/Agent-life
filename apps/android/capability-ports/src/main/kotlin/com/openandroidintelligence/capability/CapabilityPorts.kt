package com.openandroidintelligence.capability

import com.openandroidintelligence.core.model.NotificationFieldAccess
import kotlinx.coroutines.flow.Flow

/**
 * Closed set of device data sources exposed by the Android boundary.
 *
 * This enum is intentionally about data reads only.  Write, UI-control and
 * command execution capabilities are not ports in this module.
 */
enum class MobileDataCapability {
    NOTIFICATIONS,
    SMS,
    CALLS,
    CONTACTS,
    CLIPBOARD,
    LOCATION,
    HEALTH,
    SENSORS,
    CALENDAR,
    ALARMS,
    CURRENT_WINDOW,
    SCREEN_CONTENT,
}

enum class DataSyncMode { ON_DEMAND, AUTO_SEND }

/** Availability is reported by a platform adapter; it never implies consent. */
enum class CapabilityAvailability {
    READY,
    PERMISSION_REQUIRED,
    PLATFORM_UNSUPPORTED,
    DISABLED,
}

/**
 * Every capability has a concrete filter type.  There is no open-ended
 * string/map filter at this boundary.
 */
sealed interface CapabilityFilter {
    val capability: MobileDataCapability

    /** The notification filter mirrors the existing closed package/field policy. */
    data class Notifications(
        val packageIds: List<String>,
        val fieldAccess: NotificationFieldAccess,
    ) : CapabilityFilter {
        override val capability: MobileDataCapability = MobileDataCapability.NOTIFICATIONS

        init {
            require(packageIds.none { it.isBlank() }) { "package IDs must not be blank" }
            require(packageIds.size == packageIds.toSet().size) { "package IDs must be unique" }
            require(packageIds == packageIds.sortedWith(Comparator { left, right ->
                compareUnicodeCodePoints(left, right)
            })) {
                "package IDs must be sorted by Unicode code point"
            }
        }
    }

    /** These sources currently have no additional user-selectable filter. */
    data object Sms : CapabilityFilter {
        override val capability: MobileDataCapability = MobileDataCapability.SMS
    }

    data class Calls(
        val directions: Set<CallDirection>,
        val counterpartyAccess: CallCounterpartyAccess,
    ) : CapabilityFilter {
        override val capability: MobileDataCapability = MobileDataCapability.CALLS

        init {
            require(directions.isNotEmpty()) { "call directions must not be empty" }
        }

        fun canonicalDirections(): List<CallDirection> =
            CallDirection.entries.filter(directions::contains)
    }

    data object Contacts : CapabilityFilter {
        override val capability: MobileDataCapability = MobileDataCapability.CONTACTS
    }

    data object Clipboard : CapabilityFilter {
        override val capability: MobileDataCapability = MobileDataCapability.CLIPBOARD
    }

    data object Location : CapabilityFilter {
        override val capability: MobileDataCapability = MobileDataCapability.LOCATION
    }

    data object Health : CapabilityFilter {
        override val capability: MobileDataCapability = MobileDataCapability.HEALTH
    }

    data object Sensors : CapabilityFilter {
        override val capability: MobileDataCapability = MobileDataCapability.SENSORS
    }

    data object Calendar : CapabilityFilter {
        override val capability: MobileDataCapability = MobileDataCapability.CALENDAR
    }

    data object Alarms : CapabilityFilter {
        override val capability: MobileDataCapability = MobileDataCapability.ALARMS
    }

    data object CurrentWindow : CapabilityFilter {
        override val capability: MobileDataCapability = MobileDataCapability.CURRENT_WINDOW
    }

    data object ScreenContent : CapabilityFilter {
        override val capability: MobileDataCapability = MobileDataCapability.SCREEN_CONTENT
    }
}

private fun compareUnicodeCodePoints(left: String, right: String): Int {
    val leftCodePoints = left.codePoints().toArray()
    val rightCodePoints = right.codePoints().toArray()
    val commonLength = minOf(leftCodePoints.size, rightCodePoints.size)
    for (index in 0 until commonLength) {
        if (leftCodePoints[index] != rightCodePoints[index]) {
            return leftCodePoints[index].compareTo(rightCodePoints[index])
        }
    }
    return leftCodePoints.size.compareTo(rightCodePoints.size)
}

/** A user-controlled local grant; it is not created by an Agent request. */
data class CapabilityGrant(
    val capability: MobileDataCapability,
    val filter: CapabilityFilter,
    val onDemandEnabled: Boolean,
    val autoSendEnabled: Boolean,
    val agentMayRequest: Boolean,
    val policyRevision: ULong,
) {
    init {
        require(filter.capability == capability) { "grant filter capability mismatch" }
    }
}

data class AgentDataRequest(
    val requestId: String,
    val capability: MobileDataCapability,
    val mode: DataSyncMode,
    val filter: CapabilityFilter,
    val policyRevision: ULong,
) {
    init {
        require(requestId.isNotBlank()) { "request ID must not be blank" }
        require(filter.capability == capability) { "request filter capability mismatch" }
    }
}

enum class AgentRequestDenialReason {
    DEFAULT_DENY,
    NO_LOCAL_GRANT,
    AGENT_REQUESTS_DISABLED,
    MODE_NOT_GRANTED,
    FILTER_NOT_GRANTED,
    POLICY_REVISION_STALE,
    CAPABILITY_MISMATCH,
    CAPABILITY_UNAVAILABLE,
}

/** Explicit result; an absent grant is never treated as approval. */
sealed interface AgentRequestAuthorization {
    data class Allowed internal constructor(val access: AuthorizedCapabilityAccess) : AgentRequestAuthorization
    data class Denied(val reason: AgentRequestDenialReason) : AgentRequestAuthorization
}

/** Only the authorizer in this module can mint these access objects. */
sealed interface AuthorizedCapabilityAccess {
    val request: AgentDataRequest
    val grantRevision: ULong

    class OnDemand internal constructor(
        override val request: AgentDataRequest,
        override val grantRevision: ULong,
    ) : AuthorizedCapabilityAccess

    class AutoSend internal constructor(
        override val request: AgentDataRequest,
        override val grantRevision: ULong,
    ) : AuthorizedCapabilityAccess
}

typealias AuthorizedOnDemandRequest = AuthorizedCapabilityAccess.OnDemand
typealias AuthorizedAutoSendSubscription = AuthorizedCapabilityAccess.AutoSend

fun interface AgentRequestAuthorizer {
    fun authorize(
        request: AgentDataRequest,
        grant: CapabilityGrant?,
        availability: CapabilityAvailability,
    ): AgentRequestAuthorization
}

/** Deterministic local gate. It only checks a pre-existing user grant. */
class DefaultAgentRequestAuthorizer : AgentRequestAuthorizer {
    override fun authorize(
        request: AgentDataRequest,
        grant: CapabilityGrant?,
        availability: CapabilityAvailability,
    ): AgentRequestAuthorization {
        if (grant == null) return AgentRequestAuthorization.Denied(AgentRequestDenialReason.NO_LOCAL_GRANT)
        if (availability != CapabilityAvailability.READY) {
            return AgentRequestAuthorization.Denied(AgentRequestDenialReason.CAPABILITY_UNAVAILABLE)
        }
        if (grant.capability != request.capability) {
            return AgentRequestAuthorization.Denied(AgentRequestDenialReason.CAPABILITY_MISMATCH)
        }
        if (!grant.agentMayRequest) {
            return AgentRequestAuthorization.Denied(AgentRequestDenialReason.AGENT_REQUESTS_DISABLED)
        }
        if (grant.policyRevision != request.policyRevision) {
            return AgentRequestAuthorization.Denied(AgentRequestDenialReason.POLICY_REVISION_STALE)
        }
        if (grant.filter != request.filter) {
            return AgentRequestAuthorization.Denied(AgentRequestDenialReason.FILTER_NOT_GRANTED)
        }

        return when (request.mode) {
            DataSyncMode.ON_DEMAND -> if (grant.onDemandEnabled) {
                AgentRequestAuthorization.Allowed(
                    AuthorizedCapabilityAccess.OnDemand(request, grant.policyRevision),
                )
            } else {
                AgentRequestAuthorization.Denied(AgentRequestDenialReason.MODE_NOT_GRANTED)
            }

            DataSyncMode.AUTO_SEND -> if (grant.autoSendEnabled) {
                AgentRequestAuthorization.Allowed(
                    AuthorizedCapabilityAccess.AutoSend(request, grant.policyRevision),
                )
            } else {
                AgentRequestAuthorization.Denied(AgentRequestDenialReason.MODE_NOT_GRANTED)
            }
        }
    }
}

/** Marker implemented by a future typed adapter payload. */
interface CapabilityPayload

enum class CapabilityReadStatus { COMPLETE, WAITING_DEVICE, FAILED }

data class CapabilityReadResult<T : CapabilityPayload>(
    val records: List<T>,
    val status: CapabilityReadStatus,
    val policyRevision: ULong,
    val failureReason: String? = null,
) {
    init {
        require(status == CapabilityReadStatus.FAILED || failureReason == null) {
            "only failed reads have a failure reason"
        }
        if (status == CapabilityReadStatus.FAILED) require(!failureReason.isNullOrBlank())
    }
}

data class CapabilityEvent<T : CapabilityPayload>(
    val capability: MobileDataCapability,
    val eventId: String,
    val record: T,
    val policyRevision: ULong,
) {
    init {
        require(eventId.isNotBlank()) { "event ID must not be blank" }
    }

    override fun toString(): String =
        "CapabilityEvent(capability=$capability,eventId=<redacted>,record=<redacted>,policyRevision=$policyRevision)"
}

/**
 * Android-side port only. Implementations must be one typed adapter per
 * capability; this module deliberately contains no provider access.
 */
interface CapabilityPort<T : CapabilityPayload> {
    val capability: MobileDataCapability

    suspend fun read(request: AuthorizedOnDemandRequest): CapabilityReadResult<T>

    fun observeAutoSend(subscription: AuthorizedAutoSendSubscription): Flow<CapabilityEvent<T>>
}

interface CapabilityPortRegistry {
    fun find(capability: MobileDataCapability): CapabilityPort<out CapabilityPayload>?
}
