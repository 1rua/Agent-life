package com.agentlife.encrypted.store

import com.agentlife.core.model.CapabilityDurableEvent
import com.agentlife.core.model.CapabilityOutbox
import com.agentlife.core.model.CapabilityOutboxAckRejected
import com.agentlife.core.model.CapabilityOutboxConflict
import com.agentlife.core.model.CapabilityOutboxFull
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Generic durable capability outbox. The persistence seam only ever observes
 * an AES-GCM envelope; event identity, policy revision, and wire bytes remain
 * inside it. Entries are caller-IDempotent and never evicted under pressure.
 */
class CapabilityOutboxStore(
    private val persistence: EncryptedOutboxPersistence,
    private val encryptionKey: SecretKey,
    private val ackVerifier: EventAckVerifier = EventAckVerifier { _, _ -> false },
    private val maxEvents: Int = MAX_EVENTS,
) : CapabilityOutbox {
    private val events = LinkedHashMap<String, CapabilityDurableEvent>()
    private val lock = Any()

    init {
        require(encryptionKey.algorithm.equals("AES", ignoreCase = true)) { "outbox key must use AES" }
        require(maxEvents in 1..MAX_EVENTS) { "outbox capacity must be between 1 and $MAX_EVENTS" }
        restore()
    }

    constructor(
        persistence: EncryptedOutboxPersistence,
        encryptionKey: ByteArray,
        ackVerifier: EventAckVerifier = EventAckVerifier { _, _ -> false },
        maxEvents: Int = MAX_EVENTS,
    ) : this(
        persistence = persistence,
        encryptionKey = SecretKeySpec(validateKeyBytes(encryptionKey), "AES"),
        ackVerifier = ackVerifier,
        maxEvents = maxEvents,
    )

    override suspend fun enqueueAccepted(event: CapabilityDurableEvent): CapabilityDurableEvent =
        enqueueAcceptedBlocking(event)

    fun enqueueAcceptedBlocking(event: CapabilityDurableEvent): CapabilityDurableEvent = synchronized(lock) {
        val existing = events[event.eventId]
        if (existing != null) {
            if (existing != event) throw CapabilityOutboxConflict("capability event identity conflicts with durable entry")
            return@synchronized existing.snapshot()
        }
        if (events.size >= maxEvents) throw CapabilityOutboxFull("capability outbox is full")

        val snapshot = LinkedHashMap(events)
        try {
            events[event.eventId] = event.snapshot()
            persist()
            events.getValue(event.eventId).snapshot()
        } catch (failure: RuntimeException) {
            events.clear()
            events.putAll(snapshot)
            throw failure
        }
    }

    override suspend fun acknowledge(eventId: String, eventAckWire: ByteArray) =
        acknowledgeBlocking(eventId, eventAckWire)

    fun acknowledgeBlocking(eventId: String, eventAckWire: ByteArray) = synchronized(lock) {
        val existing = events[eventId] ?: throw CapabilityOutboxAckRejected("unknown capability event")
        if (eventAckWire.isEmpty() || !ackVerifier.verify(existing.eventId, eventAckWire.copyOf())) {
            throw CapabilityOutboxAckRejected("capability event ACK failed verification")
        }
        val snapshot = LinkedHashMap(events)
        events.remove(eventId)
        try {
            persist()
        } catch (failure: RuntimeException) {
            events.clear()
            events.putAll(snapshot)
            throw failure
        }
    }

    override suspend fun recoverUnacknowledged(): List<CapabilityDurableEvent> =
        recoverUnacknowledgedBlocking()

    fun recoverUnacknowledgedBlocking(): List<CapabilityDurableEvent> = synchronized(lock) {
        events.values.map { it.snapshot() }
    }

    private fun restore() {
        val envelope = persistence.read() ?: return
        try {
            DataInputStream(ByteArrayInputStream(decrypt(envelope))).use { input ->
                check(readString(input) == MAGIC) { "capability outbox format mismatch" }
                val count = input.readInt()
                check(count in 0..maxEvents) { "capability outbox event count invalid" }
                repeat(count) {
                    val event = CapabilityDurableEvent(
                        eventId = readString(input),
                        capability = readString(input),
                        recordId = readString(input),
                        policyRevision = input.readLong().toULong(),
                        eventWire = readBytes(input),
                    )
                    check(events.put(event.eventId, event) == null) { "duplicate capability event ID" }
                }
                check(input.available() == 0) { "capability outbox trailing bytes" }
            }
        } catch (failure: Throwable) {
            events.clear()
            throw if (failure is OutboxCorrupted) failure else OutboxCorrupted("capability outbox recovery failed", failure)
        }
    }

    private fun persist() {
        if (events.isEmpty()) {
            persistence.clear()
            return
        }
        val plain = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                writeString(output, MAGIC)
                output.writeInt(events.size)
                events.values.forEach { event ->
                    writeString(output, event.eventId)
                    writeString(output, event.capability)
                    writeString(output, event.recordId)
                    output.writeLong(event.policyRevision.toLong())
                    writeBytes(output, event.eventWire)
                }
            }
            bytes.toByteArray()
        }
        persistence.write(encrypt(plain))
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val iv = ByteArray(IV_BYTES).also(SecureRandom()::nextBytes)
        return try {
            val ciphertext = Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.ENCRYPT_MODE, encryptionKey, GCMParameterSpec(TAG_BITS, iv))
                doFinal(plain)
            }
            iv + ciphertext
        } catch (failure: GeneralSecurityException) {
            throw OutboxCorrupted("capability outbox encryption failed", failure)
        }
    }

    private fun decrypt(envelope: ByteArray): ByteArray {
        require(envelope.size > IV_BYTES) { "capability outbox envelope is truncated" }
        return try {
            Cipher.getInstance(TRANSFORMATION).run {
                init(
                    Cipher.DECRYPT_MODE,
                    encryptionKey,
                    GCMParameterSpec(TAG_BITS, envelope.copyOfRange(0, IV_BYTES)),
                )
                doFinal(envelope, IV_BYTES, envelope.size - IV_BYTES)
            }
        } catch (failure: GeneralSecurityException) {
            throw OutboxCorrupted("capability outbox authentication failed", failure)
        }
    }

    private fun CapabilityDurableEvent.snapshot() = CapabilityDurableEvent(
        eventId = eventId,
        capability = capability,
        recordId = recordId,
        policyRevision = policyRevision,
        eventWire = eventWire,
    )

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val IV_BYTES = 12
        private const val MAX_EVENTS = 10_000
        private const val MAX_STRING_BYTES = 1_048_576
        private const val MAX_WIRE_BYTES = 4 * 1_048_576
        private const val MAGIC = "AGENT_LIFE_CAPABILITY_OUTBOX_V1"
        private val utf8 = StandardCharsets.UTF_8

        private fun validateKeyBytes(value: ByteArray): ByteArray {
            require(value.size == 16 || value.size == 24 || value.size == 32) {
                "outbox key must be 128, 192 or 256 bits"
            }
            return value.copyOf()
        }

        private fun writeString(output: DataOutputStream, value: String) {
            val bytes = value.toByteArray(utf8)
            require(bytes.size <= MAX_STRING_BYTES) { "capability outbox string is too large" }
            output.writeInt(bytes.size)
            output.write(bytes)
        }

        private fun readString(input: DataInputStream): String = String(readBytes(input), utf8)

        private fun writeBytes(output: DataOutputStream, value: ByteArray) {
            require(value.size <= MAX_WIRE_BYTES) { "capability outbox wire is too large" }
            output.writeInt(value.size)
            output.write(value)
        }

        private fun readBytes(input: DataInputStream): ByteArray {
            val length = input.readInt()
            require(length in 0..MAX_WIRE_BYTES) { "capability outbox byte field is invalid" }
            return ByteArray(length).also(input::readFully)
        }
    }
}
