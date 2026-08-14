package com.agentlife.sms

import com.agentlife.capability.AuthorizedAutoSendSubscription
import com.agentlife.capability.MAX_SMS_BATCH_RECORDS
import com.agentlife.capability.MobileDataCapability
import com.agentlife.capability.SmsCapabilityProvider
import com.agentlife.capability.SmsPayload
import com.agentlife.capability.requireAutoSendScope
import com.agentlife.core.model.CapabilityDurableEvent
import com.agentlife.core.model.CapabilityOutbox
import com.agentlife.core.model.PairedBridgeTransport
import com.agentlife.sync.CapabilityDispatchFailure
import com.agentlife.sync.CapabilityEventEgressGate
import com.agentlife.sync.CapabilityOutboxDispatcher
import com.agentlife.sync.CapabilityPairedBridgeBindingSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface SmsEventEncoder {
    fun encode(eventId: String, record: SmsPayload, policyRevision: ULong): ByteArray
}

enum class SmsSyncFailure {
    PAIRING_UNAVAILABLE,
    POLICY_REVOKED,
    CURSOR_NOT_DURABLE,
    UNSUPPORTED_CAPABILITY,
    ACK_REJECTED,
    TRANSPORT_FAILURE,
    OUTBOX_FAILURE,
    CAPTURE_FAILURE,
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
    bindingSource: CapabilityPairedBridgeBindingSource,
    egressGate: CapabilityEventEgressGate,
    maxAttempts: Int = 3,
) {
    private val runLock = Mutex()
    private val dispatcher = CapabilityOutboxDispatcher(
        expectedCapability = SMS_CAPABILITY,
        outbox = outbox,
        transport = transport,
        bindingSource = bindingSource,
        egressGate = egressGate,
        maxAttempts = maxAttempts,
    )

    suspend fun runOnce(subscription: AuthorizedAutoSendSubscription): SmsSyncRunResult = runLock.withLock {
        var captured = 0
        var enqueued = 0
        var collectionFailure: SmsSyncFailure? = null
        var collectionHalted = false
        val acceptedEventIds = mutableSetOf<String>()
        val blockedEventIds = mutableSetOf<String>()
        try {
            val scope = subscription.requireAutoSendScope(MobileDataCapability.SMS)
            provider.observeAutoSend(scope).take(MAX_SMS_BATCH_RECORDS).collect { providerEvent ->
                if (collectionHalted) return@collect
                captured += 1
                val cursor = try {
                    val providerId = numericProviderId(providerEvent.record.metadata.recordId)
                    val eventId = "sms:$providerId"
                    require(providerEvent.capability == MobileDataCapability.SMS) { "SMS provider emitted another capability" }
                    require(providerEvent.eventId == eventId) { "SMS provider event ID is not deterministic" }
                    require(providerEvent.policyRevision == scope.policyRevision) { "SMS provider policy revision is stale" }
                    val event = CapabilityDurableEvent(
                        eventId = eventId,
                        capability = SMS_CAPABILITY,
                        recordId = providerEvent.record.metadata.recordId,
                        policyRevision = scope.policyRevision,
                        eventWire = eventEncoder.encode(eventId, providerEvent.record, scope.policyRevision),
                    )
                    try {
                        outbox.enqueueAccepted(event)
                    } catch (failure: CancellationException) {
                        throw failure
                    } catch (_: Throwable) {
                        collectionFailure = SmsSyncFailure.OUTBOX_FAILURE
                        collectionHalted = true
                        return@collect
                    }
                    acceptedEventIds += eventId
                    enqueued += 1
                    SmsCursor(providerId, providerEvent.record.metadata.messageAtEpochMs)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Throwable) {
                    collectionFailure = SmsSyncFailure.CAPTURE_FAILURE
                    collectionHalted = true
                    return@collect
                }
                try {
                    if (!cursorStore.advance(cursor)) {
                        blockedEventIds += "sms:${cursor.providerId}"
                        collectionFailure = SmsSyncFailure.CURSOR_NOT_DURABLE
                        collectionHalted = true
                    }
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Throwable) {
                    blockedEventIds += "sms:${cursor.providerId}"
                    collectionFailure = SmsSyncFailure.CURSOR_NOT_DURABLE
                    collectionHalted = true
                }
            }
        } catch (_: CancellationException) {
            return@withLock result(captured, enqueued, 0, acceptedEventIds.size, SmsSyncFailure.CANCELLED)
        } catch (_: Throwable) {
            collectionFailure = SmsSyncFailure.CAPTURE_FAILURE
        }

        val dispatch = try {
            dispatcher.dispatchPending(blockedEventIds)
        } catch (_: CancellationException) {
            return@withLock result(captured, enqueued, 0, acceptedEventIds.size, SmsSyncFailure.CANCELLED)
        }
        result(
            captured = captured,
            enqueued = enqueued,
            acknowledged = dispatch.acknowledged,
            retained = dispatch.retained,
            failure = collectionFailure ?: dispatch.failure?.toSmsSyncFailure(),
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
        require(recordId != numeric && numeric.matches(Regex("[1-9][0-9]*"))) {
            "SMS record ID must be sms:<numericProviderId>"
        }
        return numeric.toLongOrNull() ?: throw IllegalArgumentException("SMS provider ID is out of range")
    }
}

private fun CapabilityDispatchFailure.toSmsSyncFailure(): SmsSyncFailure = when (this) {
    CapabilityDispatchFailure.PAIRING_UNAVAILABLE -> SmsSyncFailure.PAIRING_UNAVAILABLE
    CapabilityDispatchFailure.POLICY_REVOKED -> SmsSyncFailure.POLICY_REVOKED
    CapabilityDispatchFailure.UNSUPPORTED_CAPABILITY -> SmsSyncFailure.UNSUPPORTED_CAPABILITY
    CapabilityDispatchFailure.ACK_REJECTED -> SmsSyncFailure.ACK_REJECTED
    CapabilityDispatchFailure.OUTBOX_FAILURE -> SmsSyncFailure.OUTBOX_FAILURE
    CapabilityDispatchFailure.TRANSPORT_FAILURE -> SmsSyncFailure.TRANSPORT_FAILURE
    CapabilityDispatchFailure.CANCELLED -> SmsSyncFailure.CANCELLED
}

private const val SMS_CAPABILITY = "sms"
