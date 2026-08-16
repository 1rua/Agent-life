package com.agentlife.calls

import com.agentlife.capability.CallDirection
import com.agentlife.capability.CallNumberPresentation
import com.agentlife.capability.CallsPayload
import com.agentlife.capability.NormalizedContent

interface CallLogEventCodec {
    fun encode(
        eventId: String,
        record: CallsPayload,
        sourceEpoch: ULong,
        policyRevision: ULong,
    ): ByteArray

    fun decode(wire: ByteArray): DecodedCallLogRecord
}

data class DecodedCallLogRecord(
    val recordId: String,
    val sourceEpoch: ULong,
    val cursor: CallLogCursor,
    val captureRevision: ULong,
    val policyRevision: ULong,
) {
    override fun toString(): String = "DecodedCallLogRecord(<redacted>)"
}

class CallLogWireFormatException : IllegalArgumentException("WIRE_RECORD_UNREPRESENTABLE")

/** The only v1 emitter: key order is intentionally written, never inferred from a map. */
class DeterministicCallLogEventCodec : CallLogEventCodec {
    override fun encode(
        eventId: String,
        record: CallsPayload,
        sourceEpoch: ULong,
        policyRevision: ULong,
    ): ByteArray {
        val metadata = record.metadata
        require(sourceEpoch > 0u) { "WIRE_RECORD_UNREPRESENTABLE" }
        val providerId = metadata.recordId.removePrefix("call:").toLongOrNull()
            ?.takeIf { metadata.recordId == "call:$it" && it > 0 }
            ?: throw CallLogWireFormatException()
        require(eventId == "call:$sourceEpoch:$providerId") { "WIRE_RECORD_UNREPRESENTABLE" }
        val counterparty = when (val value = record.counterpartyNumber) {
            NormalizedContent.Withheld -> "{\"state\":\"withheld\"}"
            is NormalizedContent.Released -> {
                require(metadata.numberPresentation == CallNumberPresentation.ALLOWED) { "WIRE_RECORD_UNREPRESENTABLE" }
                require(value.value.toByteArray(Charsets.UTF_8).size in 1..256) { "WIRE_RECORD_UNREPRESENTABLE" }
                "{\"state\":\"released\",\"value\":${jsonString(value.value)}}"
            }
        }
        val direction = when (metadata.direction) {
            CallDirection.INCOMING -> "incoming"
            CallDirection.OUTGOING -> "outgoing"
            CallDirection.MISSED -> "missed"
            CallDirection.REJECTED -> "rejected"
        }
        val presentation = when (metadata.numberPresentation) {
            CallNumberPresentation.ALLOWED -> "allowed"
            CallNumberPresentation.RESTRICTED -> "restricted"
            CallNumberPresentation.UNKNOWN -> "unknown"
            CallNumberPresentation.PAYPHONE -> "payphone"
            CallNumberPresentation.UNAVAILABLE -> "unavailable"
        }
        return ("{\"kind\":\"upsert\",\"record_id\":${jsonString(metadata.recordId)}," +
            "\"source_epoch\":${jsonString(sourceEpoch.toString())},\"record_revision\":\"1\"," +
            "\"cursor_started_at_epoch_ms\":${jsonString(metadata.startedAtEpochMs.toString())}," +
            "\"cursor_provider_id\":${jsonString(providerId.toString())}," +
            "\"captured_at_epoch_ms\":${jsonString(metadata.observedAtEpochMs.toString())}," +
            "\"capture_revision\":${jsonString(policyRevision.toString())},\"policy_revision\":${jsonString(policyRevision.toString())}," +
            "\"metadata\":{\"direction\":${jsonString(direction)}," +
            "\"started_at_epoch_ms\":${jsonString(metadata.startedAtEpochMs.toString())}," +
            "\"ended_at_epoch_ms\":${jsonString(metadata.endedAtEpochMs.toString())}," +
            "\"duration_seconds\":${jsonString(metadata.durationSeconds.toString())}," +
            "\"observed_at_epoch_ms\":${jsonString(metadata.observedAtEpochMs.toString())}," +
            "\"number_presentation\":${jsonString(presentation)}},\"counterparty_number\":$counterparty}")
            .toByteArray(Charsets.UTF_8)
    }

    override fun decode(wire: ByteArray): DecodedCallLogRecord = StrictCallLogWireDecoder(wire).decode()

    private fun jsonString(value: String): String = buildString {
        append('"')
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character.isHighSurrogate()) {
                require(index + 1 < value.length && value[index + 1].isLowSurrogate()) { "WIRE_RECORD_UNREPRESENTABLE" }
                append(character); append(value[index + 1]); index += 2; continue
            }
            require(!character.isLowSurrogate()) { "WIRE_RECORD_UNREPRESENTABLE" }
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
            }
            index += 1
        }
        append('"')
    }
}
