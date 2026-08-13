package com.agentlife.sms

import com.agentlife.capability.SmsMetadata
import com.agentlife.capability.SmsPayload
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class SmsWireCodecTest {
    @Test
    fun encodes_the_maximum_signed_long_sms_provider_id() {
        val payload = SmsPayload(
            metadata = SmsMetadata(
                recordId = "sms:${Long.MAX_VALUE}",
                senderAddress = null,
                threadId = null,
                messageAtEpochMs = 1L,
                observedAtEpochMs = 2L,
                read = false,
                subscriptionId = null,
            ),
            content = releasedSmsContent("complete body"),
        )

        val actual = SmsWireCodec().encode("sms:${Long.MAX_VALUE}", payload, 7u).decodeToString()

        org.junit.Assert.assertTrue(actual.contains("\"cursor_provider_id\":\"${Long.MAX_VALUE}\""))
    }

    @Test
    fun rejects_an_event_id_that_does_not_match_the_sms_metadata_record_id() {
        val payload = SmsPayload(
            metadata = SmsMetadata(
                recordId = "sms:42",
                senderAddress = null,
                threadId = null,
                messageAtEpochMs = 1L,
                observedAtEpochMs = 2L,
                read = false,
                subscriptionId = null,
            ),
            content = releasedSmsContent("complete body"),
        )

        assertThrows(IllegalArgumentException::class.java) {
            SmsWireCodec().encode("sms:43", payload, 7u)
        }
    }

    @Test
    fun encodes_the_closed_sms_upsert_as_exact_utf8_in_fixed_key_order() {
        val encoder: SmsEventEncoder = SmsWireCodec()
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
            content = releasedSmsContent(""),
        )
        val actual = encoder.encode("sms:42", payload, 7u)

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
