package com.openandroidintelligence.calls

enum class CallLogLatencyBucket { LT_100_MS, LT_1_S, LT_10_S, GE_10_S }

enum class CallLogAuditResultCode {
    COMPLETE,
    CALL_LOG_DISABLED,
    CALL_LOG_PERMISSION_REQUIRED,
    CALL_LOG_PLATFORM_UNSUPPORTED,
    CALL_LOG_QUERY_FAILED,
    CALL_LOG_INVALID_ROW,
    CALL_LOG_POLICY_STALE,
    CALL_LOG_OUTBOX_FULL,
    CALL_LOG_OUTBOX_CORRUPTED,
    CALL_LOG_SYNC_STATE_CORRUPTED,
    CALL_LOG_CURSOR_NOT_DURABLE,
    CALL_LOG_POLICY_REVOKED,
    CALL_LOG_PAIRING_UNAVAILABLE,
    CALL_LOG_UNSUPPORTED_CAPABILITY,
    CALL_LOG_TRANSPORT_FAILURE,
    CALL_LOG_ACK_REJECTED,
    CALL_LOG_CANCELLED,
    CALL_LOG_REVOCATION_INCOMPLETE,
    CALL_LOG_SOURCE_EPOCH_EXHAUSTED,
}

data class CallLogAuditEvent(
    val policyRevision: ULong,
    val resultCode: CallLogAuditResultCode,
    val readCount: Int,
    val acceptedCount: Int,
    val acknowledgedCount: Int,
    val latencyBucket: CallLogLatencyBucket,
) {
    init {
        require(readCount >= 0) { "read count must not be negative" }
        require(acceptedCount >= 0) { "accepted count must not be negative" }
        require(acknowledgedCount >= 0) { "acknowledged count must not be negative" }
    }

    val capability: String get() = "calls.metadata"

    override fun toString(): String =
        "CallLogAuditEvent(capability=calls.metadata,policyRevision=$policyRevision,resultCode=$resultCode," +
            "readCount=$readCount,acceptedCount=$acceptedCount,acknowledgedCount=$acknowledgedCount," +
            "latencyBucket=$latencyBucket)"
}

fun interface CallLogAuditSink {
    fun record(event: CallLogAuditEvent)
}

internal fun callLogLatencyBucket(elapsedMs: Long): CallLogLatencyBucket = when {
    elapsedMs < 100L -> CallLogLatencyBucket.LT_100_MS
    elapsedMs < 1_000L -> CallLogLatencyBucket.LT_1_S
    elapsedMs < 10_000L -> CallLogLatencyBucket.LT_10_S
    else -> CallLogLatencyBucket.GE_10_S
}
