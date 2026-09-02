package com.openandroidintelligence.capability

const val MAX_CALL_LOG_BATCH_RECORDS: Int = 10_000
const val MAX_CALL_COUNTERPARTY_UTF8_BYTES: Int = 256

private val CALL_RECORD_ID = Regex("call:[1-9][0-9]*")
private val LOCAL_CALL_LOG_AUTO_SEND_FILTER = CapabilityFilter.Calls(
    directions = CallDirection.entries.toSet(),
    counterpartyAccess = CallCounterpartyAccess.WITHHELD,
)

enum class CallDirection { INCOMING, OUTGOING, MISSED, REJECTED }

enum class CallNumberPresentation { ALLOWED, RESTRICTED, UNKNOWN, PAYPHONE, UNAVAILABLE }

enum class CallCounterpartyAccess { WITHHELD, NUMBER }

data class CallHistoryPolicy(
    val fromEpochMs: Long?,
    val maxRecords: Int,
) {
    init {
        require(fromEpochMs == null || fromEpochMs >= 0) { "history start time must not be negative" }
        require(maxRecords in 1..MAX_CALL_LOG_BATCH_RECORDS) {
            "call batch record count must be between 1 and $MAX_CALL_LOG_BATCH_RECORDS"
        }
    }

    override fun toString(): String =
        "CallHistoryPolicy(fromEpochPresent=${fromEpochMs != null},maxRecords=$maxRecords)"
}

enum class CallLogSyncInterval(val periodMs: Long?) {
    MANUAL(null),
    MINUTES_15(15 * 60 * 1000L),
    MINUTES_30(30 * 60 * 1000L),
    MINUTES_60(60 * 60 * 1000L),
}

fun callEndEpochMs(startedAtEpochMs: Long, durationSeconds: Long): Long =
    Math.addExact(startedAtEpochMs, Math.multiplyExact(durationSeconds, 1_000L))

data class CallsMetadata(
    override val recordId: String,
    override val observedAtEpochMs: Long,
    val direction: CallDirection,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long,
    val durationSeconds: Long,
    val numberPresentation: CallNumberPresentation,
) : CapabilityMetadata {
    init {
        requireMetadata(recordId, observedAtEpochMs)
        require(CALL_RECORD_ID.matches(recordId) && recordId.removePrefix("call:").toLongOrNull() != null) {
            "call record ID must be call:<positiveDecimalLong>"
        }
        require(startedAtEpochMs >= 0) { "call start time must not be negative" }
        require(durationSeconds >= 0) { "call duration must not be negative" }
        require(endedAtEpochMs == callEndEpochMs(startedAtEpochMs, durationSeconds)) {
            "call end time must equal start time plus duration"
        }
    }

    override fun toString(): String =
        "CallsMetadata(direction=$direction,durationSeconds=$durationSeconds,numberPresentation=$numberPresentation)"
}

data class CallsPayload(
    val metadata: CallsMetadata,
    val counterpartyNumber: NormalizedContent<String>,
) : CapabilityPayload {
    override fun toString(): String =
        "CallsPayload(metadata=$metadata,counterpartyNumber=<redacted>)"
}

fun normalizeCallCounterpartyNumber(
    rawNumber: String?,
    numberPresentation: CallNumberPresentation,
    scope: AuthorizedReadScope,
): NormalizedContent<String> = normalizeCallCounterpartyNumber(
    rawNumber,
    numberPresentation,
    scope.capability,
    scope.filter,
)

fun normalizeCallCounterpartyNumber(
    rawNumber: String?,
    numberPresentation: CallNumberPresentation,
    scope: AuthorizedAutoSendScope,
): NormalizedContent<String> = normalizeCallCounterpartyNumber(
    rawNumber,
    numberPresentation,
    scope.capability,
    scope.filter,
)

private fun normalizeCallCounterpartyNumber(
    rawNumber: String?,
    numberPresentation: CallNumberPresentation,
    capability: MobileDataCapability,
    filter: CapabilityFilter,
): NormalizedContent<String> {
    require(capability == MobileDataCapability.CALLS) { "call number normalization requires CALLS scope" }
    val callsFilter = filter as? CapabilityFilter.Calls
        ?: throw IllegalArgumentException("call number normalization requires Calls filter")
    return if (
        callsFilter.counterpartyAccess == CallCounterpartyAccess.NUMBER &&
        numberPresentation == CallNumberPresentation.ALLOWED &&
        rawNumber != null &&
        rawNumber.isNotBlank() &&
        rawNumber.toByteArray(Charsets.UTF_8).size <= MAX_CALL_COUNTERPARTY_UTF8_BYTES
    ) {
        NormalizedContent.Released(rawNumber)
    } else {
        NormalizedContent.Withheld
    }
}

/** A locally scheduled call-log batch is not an Agent-originated request. */
data class LocalCallLogAutoSendRequest(
    val policyRevision: ULong,
    val authorizationRevision: ULong,
)

/** Snapshot supplied only by the app-local call-log consent authority. */
data class LocalCallLogAutoSendState(
    val grant: CapabilityGrant?,
    val authorizationRevision: ULong,
)

class LocalCallLogAutoSendAuthorizer {
    fun authorize(
        request: LocalCallLogAutoSendRequest,
        localState: LocalCallLogAutoSendState,
        availability: CapabilityAvailability,
    ): AuthorizedAutoSendSubscription? {
        val grant = localState.grant ?: return null
        if (
            availability != CapabilityAvailability.READY ||
            grant.capability != MobileDataCapability.CALLS ||
            grant.filter != LOCAL_CALL_LOG_AUTO_SEND_FILTER ||
            !grant.autoSendEnabled ||
            grant.policyRevision != request.policyRevision ||
            localState.authorizationRevision != request.authorizationRevision
        ) {
            return null
        }
        return AuthorizedCapabilityAccess.AutoSend(
            AgentDataRequest(
                requestId = "local-call-log-auto-sync",
                capability = MobileDataCapability.CALLS,
                mode = DataSyncMode.AUTO_SEND,
                filter = LOCAL_CALL_LOG_AUTO_SEND_FILTER,
                policyRevision = request.policyRevision,
            ),
            grant.policyRevision,
        )
    }
}
