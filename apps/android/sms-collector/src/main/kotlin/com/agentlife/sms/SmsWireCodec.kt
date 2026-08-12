package com.agentlife.sms

import com.agentlife.capability.NormalizedContent
import com.agentlife.capability.SmsPayload
import com.agentlife.core.model.CapabilityDurableEvent

/** Encodes released SMS records into the closed v1 JSON wire representation. */
class SmsEventEncoder {
    fun encode(
        payload: SmsPayload,
        event: CapabilityDurableEvent,
        sourceEpoch: ULong,
        recordRevision: ULong,
        cursor: SmsCursor,
        capturedAtEpochMs: Long,
        captureRevision: ULong,
    ): ByteArray {
        require(event.capability == "sms") { "SMS wire event capability mismatch" }
        require(event.recordId == payload.metadata.recordId) { "SMS wire record ID mismatch" }
        require(recordRevision > 0u) { "SMS wire record revision must be positive" }
        require(capturedAtEpochMs >= 0) { "SMS wire capture time must not be negative" }
        val body = (payload.content as? NormalizedContent.Released<String>)?.value
            ?: throw IllegalArgumentException("SMS wire content is withheld")
        val metadata = payload.metadata

        return buildString {
            append('{')
            appendField("kind", "upsert")
            append(',')
            appendField("record_id", metadata.recordId)
            append(',')
            appendField("source_epoch", sourceEpoch.toString())
            append(',')
            appendField("record_revision", recordRevision.toString())
            append(',')
            appendField("cursor_message_at_epoch_ms", cursor.messageAtEpochMs.toString())
            append(',')
            appendField("cursor_provider_id", cursor.providerId.toString())
            append(',')
            appendField("captured_at_epoch_ms", capturedAtEpochMs.toString())
            append(',')
            appendField("capture_revision", captureRevision.toString())
            append(',')
            appendField("policy_revision", event.policyRevision.toString())
            append(",\"metadata\":{")
            appendNullableField("sender_address", metadata.senderAddress)
            append(',')
            appendNullableField("thread_id", metadata.threadId)
            append(',')
            appendField("message_at_epoch_ms", metadata.messageAtEpochMs.toString())
            append(',')
            appendField("observed_at_epoch_ms", metadata.observedAtEpochMs.toString())
            append(",\"read\":${metadata.read}")
            append(",\"subscription_id\":")
            if (metadata.subscriptionId == null) append("null") else append(metadata.subscriptionId)
            append("},\"content\":{")
            appendField("body", body)
            append("}}")
        }.encodeToByteArray()
    }

    private fun StringBuilder.appendField(name: String, value: String) {
        appendJsonString(name)
        append(':')
        appendJsonString(value)
    }

    private fun StringBuilder.appendNullableField(name: String, value: String?) {
        appendJsonString(name)
        append(':')
        if (value == null) append("null") else appendJsonString(value)
    }

    /** Writes RFC 8259 JSON string escaping without depending on map iteration or a serializer. */
    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) {
                    append("\\u")
                    append(character.code.toString(16).padStart(4, '0'))
                } else {
                    append(character)
                }
            }
        }
        append('"')
    }
}
