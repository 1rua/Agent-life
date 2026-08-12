package com.agentlife.sms

import com.agentlife.capability.AuthorizedAutoSendSubscription
import com.agentlife.capability.MAX_SMS_BATCH_RECORDS
import com.agentlife.capability.MobileDataCapability
import com.agentlife.capability.SmsCapabilityProvider
import com.agentlife.capability.SmsPayload
import com.agentlife.capability.requireAutoSendScope
import com.agentlife.core.model.BridgeSession
import com.agentlife.core.model.CapabilityDurableEvent
import com.agentlife.core.model.CapabilityOutbox
import com.agentlife.core.model.PairedBridgeTransport
import com.agentlife.core.model.TransportCloseReason
import com.agentlife.core.model.VerifiedPairingTransportBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface SmsEventEncoder {
    fun encode(eventId: String, record: SmsPayload, policyRevision: ULong): ByteArray
}

fun interface SmsPairedBridgeBindingSource {
    fun currentBinding(): VerifiedPairingTransportBinding?
}

/** Evaluates the latest local egress policy without exposing SMS content. */
fun interface SmsAutoSendEgressGate {
    fun allows(event: CapabilityDurableEvent): Boolean
}

enum class SmsSyncFailure {
    PAIRING_UNAVAILABLE,
    POLICY_REVOKED,
    ACK_REJECTED,
    TRANSPORT_FAILURE,
    OUTBOX_FAILURE,
    CANCELLED,
}

data class SmsSyncRunResult(
    val captured: Int,
    val enqueued: Int,
    val acknowledged: Int,
    val retained: Int,
    val failure: SmsSyncFailure?,
)

/**
 * Collects a finite, already-authorized SMS batch and advances durable cursor
 * state only after its exact wire was accepted by the encrypted outbox. A
 * separate local dispatcher gives all queued records at-least-once delivery.
 */
class SmsAutoSyncCoordinator(
    private val provider: SmsCapabilityProvider,
    private val outbox: CapabilityOutbox,
    private val cursorStore: SmsCursorStore,
    private val eventEncoder: SmsEventEncoder,
    transport: PairedBridgeTransport,
    bindingSource: SmsPairedBridgeBindingSource,
    egressGate: SmsAutoSendEgressGate,
    maxAttempts: Int = 3,
) {
    private val runLock = Mutex()
    private val dispatcher = SmsCapabilityOutboxDispatcher(outbox, transport, bindingSource, egressGate, maxAttempts)

    suspend fun runOnce(subscription: AuthorizedAutoSendSubscription): SmsSyncRunResult = runLock.withLock {
        var captured = 0
        var enqueued = 0
        var collectionFailure: SmsSyncFailure? = null
        try {
            val scope = subscription.requireAutoSendScope(MobileDataCapability.SMS)
            provider.observeAutoSend(scope).take(MAX_SMS_BATCH_RECORDS).collect { providerEvent ->
                captured += 1
                val providerId = numericProviderId(providerEvent.record.metadata.recordId)
                val eventId = "sms:$providerId"
                require(providerEvent.capability == MobileDataCapability.SMS) { "SMS provider emitted another capability" }
                require(providerEvent.eventId == eventId) { "SMS provider event ID is not deterministic" }
                require(providerEvent.policyRevision == scope.policyRevision) { "SMS provider policy revision is stale" }
                val event = CapabilityDurableEvent(
                    eventId = eventId,
                    capability = "sms",
                    recordId = providerEvent.record.metadata.recordId,
                    policyRevision = scope.policyRevision,
                    eventWire = eventEncoder.encode(eventId, providerEvent.record, scope.policyRevision),
                )
                outbox.enqueueAccepted(event)
                enqueued += 1
                cursorStore.advance(SmsCursor(providerId, providerEvent.record.metadata.messageAtEpochMs))
            }
        } catch (_: CancellationException) {
            return@withLock result(captured, enqueued, 0, 0, SmsSyncFailure.CANCELLED)
        } catch (_: Throwable) {
            collectionFailure = SmsSyncFailure.OUTBOX_FAILURE
        }

        val dispatch = dispatcher.dispatchPending()
        result(
            captured = captured,
            enqueued = enqueued,
            acknowledged = dispatch.acknowledged,
            retained = dispatch.retained,
            failure = collectionFailure ?: dispatch.failure,
        )
    }

    private fun result(
        captured: Int,
        enqueued: Int,
        acknowledged: Int,
        retained: Int,
        failure: SmsSyncFailure?,
    ) = SmsSyncRunResult(captured, enqueued, acknowledged, retained, failure)

    private fun numericProviderId(recordId: String): Long {
        val numeric = recordId.removePrefix("sms:")
        require(recordId != numeric && numeric.matches(Regex("0|[1-9][0-9]*"))) {
            "SMS record ID must be sms:<numericProviderId>"
        }
        return numeric.toLongOrNull() ?: throw IllegalArgumentException("SMS provider ID is out of range")
    }
}

private data class SmsDispatchResult(
    val acknowledged: Int,
    val retained: Int,
    val failure: SmsSyncFailure?,
)

/** The sole SMS egress path: a verified binding opens the closed transport port. */
private class SmsCapabilityOutboxDispatcher(
    private val outbox: CapabilityOutbox,
    private val transport: PairedBridgeTransport,
    private val bindingSource: SmsPairedBridgeBindingSource,
    private val egressGate: SmsAutoSendEgressGate,
    private val maxAttempts: Int,
) {
    init {
        require(maxAttempts in 1..10) { "dispatch attempts must be between 1 and 10" }
    }

    suspend fun dispatchPending(): SmsDispatchResult {
        val pending = try {
            outbox.recoverUnacknowledged()
        } catch (_: Throwable) {
            return SmsDispatchResult(0, 0, SmsSyncFailure.OUTBOX_FAILURE)
        }
        var acknowledged = 0
        var retained = 0
        var failure: SmsSyncFailure? = null

        for (event in pending) {
            if (!egressGate.allows(event)) {
                retained += 1
                failure = failure ?: SmsSyncFailure.POLICY_REVOKED
                continue
            }
            var delivered = false
            for (attempt in 1..maxAttempts) {
                // A policy may have changed while a prior send or retry was in flight.
                if (!egressGate.allows(event)) {
                    failure = failure ?: SmsSyncFailure.POLICY_REVOKED
                    break
                }
                val binding = bindingSource.currentBinding()
                if (binding == null) {
                    failure = failure ?: SmsSyncFailure.PAIRING_UNAVAILABLE
                    break
                }
                var session: BridgeSession? = null
                try {
                    session = transport.open(binding)
                    session.sendControl(event.eventWire)
                    outbox.acknowledge(event.eventId, session.receiveControl())
                    acknowledged += 1
                    close(session, TransportCloseReason.PROCESS_STOPPED)
                    delivered = true
                    break
                } catch (_: CancellationException) {
                    close(session, TransportCloseReason.PROCESS_STOPPED)
                    return SmsDispatchResult(acknowledged, pending.size - acknowledged, SmsSyncFailure.CANCELLED)
                } catch (_: IllegalArgumentException) {
                    failure = failure ?: SmsSyncFailure.ACK_REJECTED
                    close(session, TransportCloseReason.FAILURE)
                } catch (_: Throwable) {
                    failure = failure ?: SmsSyncFailure.TRANSPORT_FAILURE
                    close(session, TransportCloseReason.FAILURE)
                }
            }
            if (!delivered) retained += 1
        }
        return SmsDispatchResult(acknowledged, retained, failure)
    }

    private suspend fun close(session: BridgeSession?, reason: TransportCloseReason) {
        if (session != null) {
            try {
                transport.close(reason)
            } catch (_: Throwable) {
                // Close failure does not alter retention: no ACK removed the event.
            }
        }
    }
}
