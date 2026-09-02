package com.openandroidintelligence.calls

import com.openandroidintelligence.capability.CallDirection
import com.openandroidintelligence.capability.CallHistoryPolicy
import com.openandroidintelligence.capability.CallNumberPresentation
import com.openandroidintelligence.capability.CapabilityAvailability
import com.openandroidintelligence.capability.callEndEpochMs

data class CallLogCursor(
    val startedAtEpochMs: Long,
    val providerId: Long,
) : Comparable<CallLogCursor> {
    init {
        require(startedAtEpochMs >= 0)
        require(providerId > 0)
    }

    override fun compareTo(other: CallLogCursor): Int =
        compareValuesBy(this, other, CallLogCursor::startedAtEpochMs, CallLogCursor::providerId)

    override fun toString(): String = "CallLogCursor(<redacted>)"
}

class CallLogRow(
    val providerId: Long,
    val direction: CallDirection,
    val startedAtEpochMs: Long,
    val durationSeconds: Long,
    val number: String?,
    val numberPresentation: CallNumberPresentation,
) {
    init {
        require(providerId > 0)
        require(startedAtEpochMs >= 0)
        require(durationSeconds >= 0)
    }

    val endedAtEpochMs = callEndEpochMs(startedAtEpochMs, durationSeconds)
    val cursor = CallLogCursor(startedAtEpochMs, providerId)

    override fun toString(): String =
        "CallLogRow(direction=$direction,durationSeconds=$durationSeconds,numberPresentation=$numberPresentation)"
}

data class CallLogQuery(
    val history: CallHistoryPolicy,
    val directions: Set<CallDirection>,
    val cursor: CallLogCursor? = null,
) {
    init {
        require(directions.isNotEmpty())
    }

    override fun toString(): String =
        "CallLogQuery(directions=${directions.size},maxRecords=${history.maxRecords}," +
            "cursorPresent=${cursor != null})"
}

interface CallLogReader {
    fun query(request: CallLogQuery): List<CallLogRow>
    fun probe()
}

fun interface CallLogAvailabilitySource {
    fun current(): CapabilityAvailability
}

class CallLogInvalidRowException : RuntimeException("CALL_LOG_INVALID_ROW")

class CallLogQueryException : RuntimeException("CALL_LOG_QUERY_FAILED")

class CallLogPermissionRequiredException : RuntimeException("CALL_LOG_PERMISSION_REQUIRED")
