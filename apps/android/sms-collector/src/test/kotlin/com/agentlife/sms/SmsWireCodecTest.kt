package com.agentlife.sms

import com.agentlife.capability.NormalizedContent
import com.agentlife.capability.SmsMetadata
import com.agentlife.capability.SmsPayload
import com.agentlife.core.model.CapabilityDurableEvent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SmsWireCodecTest {
    @Test
    fun encodes_the_closed_sms_upsert_as_exact_utf8_in_fixed_key_order() {
        val encoder = SmsEventEncoder()
        val payload = SmsPayload(
            metadata = SmsMetadata(
                recordId = "sms:42",
                senderAddress = "张\"\\\u0001",
                threadId = null,
                messageAtEpochMs = 1_700_000_000_000L,
                observedAtEpochMs = 1_700_000_000_100L,
                read = false,
                subscriptionId = null,
            ),
            content = NormalizedContent.Released(""),
        )
        val event = CapabilityDurableEvent(
            eventId = "event:sms:42",
            capability = "sms",
            recordId = "sms:42",
            policyRevision = 7u,
            eventWire = byteArrayOf(1),
        )

        val actual = encoder.encode(
            payload = payload,
            event = event,
            sourceEpoch = 1u,
            recordRevision = 1u,
            cursor = SmsCursor(providerId = 42, messageAtEpochMs = 1_700_000_000_000L),
            capturedAtEpochMs = 1_700_000_000_100L,
            captureRevision = 7u,
        )

        val expected = """
            {"kind":"upsert","record_id":"sms:42","source_epoch":"1","record_revision":"1","cursor_message_at_epoch_ms":"1700000000000","cursor_provider_id":"42","captured_at_epoch_ms":"1700000000100","capture_revision":"7","policy_revision":"7","metadata":{"sender_address":"张\"\\\u0001","thread_id":null,"message_at_epoch_ms":"1700000000000","observed_at_epoch_ms":"1700000000100","read":false,"subscription_id":null},"content":{"body":""}}
        """.trimIndent().encodeToByteArray()
        assertArrayEquals(expected, actual)

        val changedField = actual.decodeToString().replace("\"read\":false", "\"read\":true").encodeToByteArray()
        val changedKey = actual.decodeToString().replace("\"record_id\"", "\"recordId\"").encodeToByteArray()
        assertFalse(actual.contentEquals(changedField))
        assertFalse(actual.contentEquals(changedKey))
    }
}
