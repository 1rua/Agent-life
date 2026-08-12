package com.agentlife.encrypted.store

import com.agentlife.core.model.CapabilityDurableEvent
import com.agentlife.core.model.CapabilityOutboxConflict
import com.agentlife.core.model.CapabilityOutboxFull
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityOutboxStoreTest {
    @Test
    fun complete_event_wire_is_encrypted_and_restored_after_restart() {
        val persistence = InMemoryOutboxPersistence()
        val key = ByteArray(32) { (it + 1).toByte() }
        val wire = "sms body: private message".encodeToByteArray()
        val event = event(wire = wire)

        runSuspend {
            CapabilityOutboxStore(persistence, key).enqueueAccepted(event)
        }

        assertFalse(persistence.bytes!!.decodeToString().contains("private message"))
        val restored = CapabilityOutboxStore(persistence, key)
        val recovered = runSuspend { restored.recoverUnacknowledged().single() }
        assertEquals("sms:42", recovered.eventId)
        assertEquals("sms:42", recovered.recordId)
        assertEquals(7uL, recovered.policyRevision)
        assertArrayEquals(wire, recovered.eventWire)
    }

    @Test
    fun invalid_ack_retains_event_but_verified_ack_removes_it() {
        val persistence = InMemoryOutboxPersistence()
        val store = CapabilityOutboxStore(
            persistence = persistence,
            encryptionKey = ByteArray(32) { 4 },
            ackVerifier = EventAckVerifier { id, ack -> id == "sms:42" && ack.contentEquals(byteArrayOf(9)) },
        )
        runSuspend { store.enqueueAccepted(event()) }

        assertThrows(OutboxAckRejected::class.java) {
            runSuspend { store.acknowledge("sms:42", byteArrayOf(8)) }
        }
        assertEquals(1, runSuspend { store.recoverUnacknowledged() }.size)

        runSuspend { store.acknowledge("sms:42", byteArrayOf(9)) }
        assertTrue(runSuspend { store.recoverUnacknowledged() }.isEmpty())
    }

    @Test
    fun byte_identical_enqueue_is_idempotent_but_conflicting_identity_is_rejected() {
        val store = CapabilityOutboxStore(InMemoryOutboxPersistence(), ByteArray(32) { 5 })
        runSuspend { store.enqueueAccepted(event()) }
        runSuspend { store.enqueueAccepted(event()) }

        assertEquals(1, runSuspend { store.recoverUnacknowledged() }.size)
        assertThrows(CapabilityOutboxConflict::class.java) {
            runSuspend { store.enqueueAccepted(event(recordId = "sms:other")) }
        }
        assertEquals("sms:42", runSuspend { store.recoverUnacknowledged() }.single().recordId)
    }

    @Test
    fun capacity_overflow_rejects_new_event_without_evicting_older_sms() {
        val store = CapabilityOutboxStore(
            persistence = InMemoryOutboxPersistence(),
            encryptionKey = ByteArray(32) { 6 },
            maxEvents = 2,
        )
        runSuspend { store.enqueueAccepted(event(eventId = "sms:1", recordId = "sms:1")) }
        runSuspend { store.enqueueAccepted(event(eventId = "sms:2", recordId = "sms:2")) }

        assertThrows(CapabilityOutboxFull::class.java) {
            runSuspend { store.enqueueAccepted(event(eventId = "sms:3", recordId = "sms:3")) }
        }
        assertEquals(listOf("sms:1", "sms:2"), runSuspend { store.recoverUnacknowledged() }.map { it.eventId })
    }

    @Test
    fun input_and_returned_event_wires_are_defensive_copies() {
        val persistence = InMemoryOutboxPersistence()
        val inputWire = byteArrayOf(1, 2, 3)
        val input = event(wire = inputWire)
        val store = CapabilityOutboxStore(persistence, ByteArray(32) { 7 })

        val accepted = runSuspend { store.enqueueAccepted(input) }
        inputWire[0] = 99
        accepted.eventWire[1] = 99
        val recovered = runSuspend { store.recoverUnacknowledged().single() }
        recovered.eventWire[2] = 99

        assertArrayEquals(byteArrayOf(1, 2, 3), runSuspend { store.recoverUnacknowledged().single() }.eventWire)
        assertArrayEquals(byteArrayOf(1, 2, 3), CapabilityOutboxStore(persistence, ByteArray(32) { 7 })
            .let { runSuspend { it.recoverUnacknowledged().single() } }.eventWire)
    }

    private fun event(
        eventId: String = "sms:42",
        recordId: String = "sms:42",
        wire: ByteArray = byteArrayOf(1, 2, 3),
    ) = CapabilityDurableEvent(
        eventId = eventId,
        capability = "sms",
        recordId = recordId,
        policyRevision = 7u,
        eventWire = wire,
    )
}

private fun <T> runSuspend(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(object : Continuation<T> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(value: Result<T>) { result = value }
    })
    return checkNotNull(result).getOrThrow()
}
