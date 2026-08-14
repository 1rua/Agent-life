package com.agentlife.core.model

/**
 * An immutable encrypted-outbox entry. The wire is intentionally not exposed
 * as backing storage so an adapter cannot mutate queued cleartext after
 * acceptance or recover it through a returned array.
 */
class CapabilityDurableEvent(
    val eventId: String,
    val capability: String,
    val recordId: String,
    val policyRevision: ULong,
    eventWire: ByteArray,
) {
    private val storedEventWire = eventWire.copyOf()

    init {
        require(eventId.isNotBlank()) { "capability event ID must not be blank" }
        require(capability.isNotBlank()) { "capability must not be blank" }
        require(recordId.isNotBlank()) { "capability record ID must not be blank" }
    }

    val eventWire: ByteArray get() = storedEventWire.copyOf()

    override fun equals(other: Any?): Boolean =
        other is CapabilityDurableEvent &&
            eventId == other.eventId &&
            capability == other.capability &&
            recordId == other.recordId &&
            policyRevision == other.policyRevision &&
            storedEventWire.contentEquals(other.storedEventWire)

    override fun hashCode(): Int = arrayOf(
        eventId,
        capability,
        recordId,
        policyRevision,
        storedEventWire.contentHashCode(),
    ).contentHashCode()

    /** Never include potentially sensitive wire bytes in diagnostics. */
    override fun toString(): String =
        "CapabilityDurableEvent(capability=$capability,policyRevision=$policyRevision,identity=<redacted>,wire=<redacted>)"
}

interface CapabilityOutbox {
    suspend fun enqueueAccepted(event: CapabilityDurableEvent): CapabilityDurableEvent
    suspend fun acknowledge(eventId: String, eventAckWire: ByteArray)
    suspend fun recoverUnacknowledged(): List<CapabilityDurableEvent>
    suspend fun clear()
}

class CapabilityOutboxConflict(message: String) : IllegalArgumentException(message)
class CapabilityOutboxFull(message: String) : IllegalStateException(message)
class CapabilityOutboxAckRejected(message: String) : IllegalArgumentException(message)
