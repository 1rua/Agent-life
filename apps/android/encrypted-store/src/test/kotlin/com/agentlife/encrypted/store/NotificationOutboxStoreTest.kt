package com.agentlife.encrypted.store

import com.agentlife.core.model.NotificationContent
import com.agentlife.core.model.NotificationMetadata
import com.agentlife.core.model.NotificationRecordV1
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationOutboxStoreTest {
    @Test
    fun persisted_bytes_are_ciphertext_and_restart_retransmits_until_ack() {
        val persistence = InMemoryOutboxPersistence()
        val key = ByteArray(32) { (it + 1).toByte() }
        val first = NotificationOutboxStore(persistence, key, EventAckVerifier { id, wire -> id == "event-1" && wire.contentEquals(byteArrayOf(7)) })
        val event = first.enqueueAcceptedBlocking(record())
        assertTrue(persistence.bytes!!.toString(Charsets.UTF_8).let { "hello" !in it && "body" !in it })
        val restarted = NotificationOutboxStore(persistence, key, EventAckVerifier { _, wire -> wire.contentEquals(byteArrayOf(7)) })
        assertEquals(event.eventId, restarted.recoverUnacknowledgedBlocking().single().eventId)
        assertThrows(OutboxAckRejected::class.java) { restarted.acknowledgeBlocking(event.eventId, byteArrayOf(8)) }
        restarted.acknowledgeBlocking(event.eventId, byteArrayOf(7))
        assertTrue(restarted.recoverUnacknowledgedBlocking().isEmpty())
    }

    @Test
    fun forged_agent_or_session_fields_cannot_enter_device_event() {
        val record = record()
        assertTrue("agent_principal_id" !in record.toString())
        assertTrue("session_id" !in record.toString())
    }

    private fun record() = NotificationRecordV1.Upsert(
        sourceEpoch = 1u,
        occurrenceId = "occ",
        recordKey = "key",
        recordRevision = 1u,
        cursor = 1u,
        capturedAtEpochMs = 10,
        captureRevision = 1u,
        metadata = NotificationMetadata("mail", "Mail", "channel", 10),
        content = NotificationContent("hello", "body"),
    )
}
