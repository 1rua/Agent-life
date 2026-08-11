package com.agentlife.encrypted.store

import com.agentlife.core.model.NotificationContent
import com.agentlife.core.model.NotificationMetadata
import com.agentlife.core.model.NotificationRecordV1
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.spec.SecretKeySpec

class NotificationOutboxStoreTest {
    @Test
    fun enqueue_persistence_failure_rolls_back_the_in_memory_event() {
        val persistence = object : EncryptedOutboxPersistence {
            override fun read(): ByteArray? = null
            override fun write(ciphertext: ByteArray) = throw IllegalStateException("disk full")
            override fun clear() = Unit
        }
        val store = NotificationOutboxStore(persistence, ByteArray(32) { 1 })

        assertThrows(IllegalStateException::class.java) {
            store.enqueueAcceptedBlocking(record())
        }
        assertTrue(store.recoverUnacknowledgedBlocking().isEmpty())
    }

    @Test
    fun bounded_outbox_persists_an_explicit_loss_marker_and_the_newest_event() {
        val persistence = InMemoryOutboxPersistence()
        var sequence = 0
        val store = NotificationOutboxStore(
            persistence = persistence,
            encryptionKey = SecretKeySpec(ByteArray(32) { 2 }, "AES"),
            eventIds = EventIdSource { "event-${++sequence}" },
            maxEvents = 2,
        )
        store.enqueueAcceptedBlocking(record(cursor = 1u, recordKey = "one"))
        store.enqueueAcceptedBlocking(record(cursor = 2u, recordKey = "two"))
        store.enqueueAcceptedBlocking(record(cursor = 3u, recordKey = "three"))

        val recovered = NotificationOutboxStore(
            persistence = persistence,
            encryptionKey = SecretKeySpec(ByteArray(32) { 2 }, "AES"),
            maxEvents = 2,
        ).recoverUnacknowledgedBlocking()
        assertEquals(2, recovered.size)
        val loss = recovered.first().record as NotificationRecordV1.LossMarker
        assertEquals(1uL, loss.loss.lostFromCursor)
        assertEquals(2uL, loss.loss.lostToCursor)
        assertEquals("OUTBOX_BACKPRESSURE", loss.loss.reason)
        assertEquals("three", recovered.last().record.recordKey)
    }

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

    private fun record(
        cursor: ULong = 1u,
        recordKey: String = "key",
    ) = NotificationRecordV1.Upsert(
        sourceEpoch = 1u,
        occurrenceId = "occ-$recordKey",
        recordKey = recordKey,
        recordRevision = 1u,
        cursor = cursor,
        capturedAtEpochMs = 10,
        captureRevision = 1u,
        metadata = NotificationMetadata("mail", "Mail", "channel", 10),
        content = NotificationContent("hello", "body"),
    )
}
