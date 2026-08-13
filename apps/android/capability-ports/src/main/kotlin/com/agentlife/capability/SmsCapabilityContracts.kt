package com.agentlife.capability

const val MAX_SMS_BATCH_RECORDS: Int = 10_000

private val SMS_RECORD_ID = Regex("sms:[1-9][0-9]*")

data class SmsMetadata(
    override val recordId: String,
    val senderAddress: String?,
    val threadId: String?,
    val messageAtEpochMs: Long,
    override val observedAtEpochMs: Long,
    val read: Boolean,
    val subscriptionId: Int?,
) : CapabilityMetadata {
    init {
        requireMetadata(recordId, observedAtEpochMs)
        require(SMS_RECORD_ID.matches(recordId) && recordId.removePrefix("sms:").toLongOrNull() != null) {
            "SMS record ID must be sms:<positiveDecimalLong>"
        }
        require(messageAtEpochMs >= 0) { "message time must not be negative" }
        require(subscriptionId == null || subscriptionId >= 0) { "subscription ID must not be negative" }
    }
}

data class SmsHistoryPolicy(
    val fromEpochMs: Long?,
    val maxRecords: Int,
) {
    init {
        require(fromEpochMs == null || fromEpochMs >= 0) { "history start time must not be negative" }
        require(maxRecords in 1..MAX_SMS_BATCH_RECORDS) {
            "SMS batch record count must be between 1 and $MAX_SMS_BATCH_RECORDS"
        }
    }
}

enum class SmsSyncInterval {
    MANUAL,
    MINUTES_15,
    MINUTES_30,
    MINUTES_60,
    ;

    val periodMs: Long?
        get() = when (this) {
            MANUAL -> null
            MINUTES_15 -> 15 * 60 * 1000L
            MINUTES_30 -> 30 * 60 * 1000L
            MINUTES_60 -> 60 * 60 * 1000L
    }
}

/** A locally scheduled SMS batch is not an Agent-originated request. */
data class LocalSmsAutoSendRequest(
    val policyRevision: ULong,
    val authorizationRevision: ULong,
)

/** Snapshot supplied only by the app-local SMS consent authority. */
data class LocalSmsAutoSendState(
    val grant: CapabilityGrant?,
    val authorizationRevision: ULong,
)

/**
 * Mints the existing typed AUTO_SEND access object for a local scheduler. It
 * deliberately does not inspect [CapabilityGrant.agentMayRequest]: that flag
 * controls remote Agent requests, not device-local periodic consent.
 */
class LocalSmsAutoSendAuthorizer {
    fun authorize(
        request: LocalSmsAutoSendRequest,
        localState: LocalSmsAutoSendState,
        availability: CapabilityAvailability,
    ): AuthorizedAutoSendSubscription? {
        val grant = localState.grant ?: return null
        if (availability != CapabilityAvailability.READY ||
            grant.capability != MobileDataCapability.SMS ||
            grant.filter != CapabilityFilter.Sms ||
            !grant.autoSendEnabled ||
            grant.policyRevision != request.policyRevision ||
            localState.authorizationRevision != request.authorizationRevision
        ) {
            return null
        }
        return AuthorizedCapabilityAccess.AutoSend(
            AgentDataRequest(
                requestId = "local-sms-auto-sync",
                capability = MobileDataCapability.SMS,
                mode = DataSyncMode.AUTO_SEND,
                filter = CapabilityFilter.Sms,
                policyRevision = request.policyRevision,
            ),
            grant.policyRevision,
        )
    }
}
