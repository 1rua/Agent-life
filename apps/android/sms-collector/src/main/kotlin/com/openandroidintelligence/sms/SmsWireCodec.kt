package com.openandroidintelligence.sms

import com.openandroidintelligence.capability.NormalizedContent
import com.openandroidintelligence.capability.SmsPayload

/** Encodes released SMS records into the closed v1 JSON wire representation. */
class SmsWireCodec : SmsEventEncoder {
    override fun encode(eventId: String, record: SmsPayload, policyRevision: ULong): ByteArray {
        require(eventId == record.metadata.recordId) { "SMS wire record ID mismatch" }
        val providerId = numericProviderId(eventId)
        return encodeRecord(
            record = record,
            recordId = eventId,
            sourceEpoch = 1uL,
            recordRevision = 1uL,
            cursor = SmsCursor(providerId, record.metadata.messageAtEpochMs),
            capturedAtEpochMs = record.metadata.observedAtEpochMs,
            captureRevision = policyRevision,
            policyRevision = policyRevision,
        )
    }

    private fun encodeRecord(
        record: SmsPayload,
        recordId: String,
        sourceEpoch: ULong,
        recordRevision: ULong,
        cursor: SmsCursor,
        capturedAtEpochMs: Long,
        captureRevision: ULong,
        policyRevision: ULong,
    ): ByteArray {
        require(recordRevision > 0u) { "SMS wire record revision must be positive" }
        require(capturedAtEpochMs >= 0) { "SMS wire capture time must not be negative" }
        val body = (record.content as? NormalizedContent.Released<String>)?.value
            ?: throw IllegalArgumentException("SMS wire content is withheld")
        val metadata = record.metadata
        val subscriptionId = metadata.subscriptionId
        require(subscriptionId == null || subscriptionId >= 0) {
            "SMS subscription ID must not be negative"
        }

        return buildString {
            append('{')
            appendField("kind", "upsert")
            append(',')
            appendField("record_id", recordId)
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
            appendField("policy_revision", policyRevision.toString())
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
            if (subscriptionId == null) append("null") else append(subscriptionId)
            append("},\"content\":{")
            appendField("body", body)
            append("}}")
        }.encodeToByteArray()
    }

    private fun numericProviderId(eventId: String): Long {
        val numeric = eventId.removePrefix("sms:")
        require(eventId != numeric && numeric.matches(Regex("[1-9][0-9]*"))) {
            "SMS event ID must be sms:<numericProviderId>"
        }
        return numeric.toLongOrNull() ?: throw IllegalArgumentException("SMS provider ID is out of range")
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
