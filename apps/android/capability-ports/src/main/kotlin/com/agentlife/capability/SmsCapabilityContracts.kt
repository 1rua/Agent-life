package com.agentlife.capability

const val MAX_SMS_BATCH_RECORDS: Int = 10_000

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
