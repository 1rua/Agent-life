package com.agentlife.sync

import com.agentlife.core.model.BridgeSession
import com.agentlife.core.model.CapabilityDurableEvent
import com.agentlife.core.model.CapabilityOutbox
import com.agentlife.core.model.CapabilityOutboxAckRejected
import com.agentlife.core.model.PairedBridgeTransport
import com.agentlife.core.model.TransportCloseReason
import com.agentlife.core.model.VerifiedPairingTransportBinding
import kotlinx.coroutines.CancellationException

fun interface CapabilityPairedBridgeBindingSource {
    fun currentBinding(): VerifiedPairingTransportBinding?
}

/** Evaluates the latest phone-local egress policy without exposing wire content. */
fun interface CapabilityEventEgressGate {
    fun allows(event: CapabilityDurableEvent): Boolean
}

enum class CapabilityDispatchFailure {
    PAIRING_UNAVAILABLE,
    POLICY_REVOKED,
    UNSUPPORTED_CAPABILITY,
    ACK_REJECTED,
    OUTBOX_FAILURE,
    TRANSPORT_FAILURE,
    CANCELLED,
}

data class CapabilityDispatchResult(
    val acknowledged: Int,
    val retained: Int,
    val failure: CapabilityDispatchFailure?,
)

fun interface CapabilityPendingDispatcher {
    suspend fun dispatchPending(blockedEventIds: Set<String>): CapabilityDispatchResult

    suspend fun dispatchPending(): CapabilityDispatchResult = dispatchPending(emptySet())
}

/**
 * The sole generic capability egress path. It accepts only a verified pairing
 * binding and checks phone-local policy at every point where a suspended open
 * or send could otherwise race a local revocation.
 */
class CapabilityOutboxDispatcher(
    private val expectedCapability: String,
    private val outbox: CapabilityOutbox,
    private val transport: PairedBridgeTransport,
    private val bindingSource: CapabilityPairedBridgeBindingSource,
    private val egressGate: CapabilityEventEgressGate,
    private val maxAttempts: Int,
) : CapabilityPendingDispatcher {
    init {
        require(expectedCapability.isNotBlank()) { "expected capability must not be blank" }
        require(maxAttempts in 1..10) { "dispatch attempts must be between 1 and 10" }
    }

    override suspend fun dispatchPending(blockedEventIds: Set<String>): CapabilityDispatchResult {
        val pending = try {
            outbox.recoverUnacknowledged()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            return CapabilityDispatchResult(0, 0, CapabilityDispatchFailure.OUTBOX_FAILURE)
        }
        var acknowledged = 0
        var retained = 0
        var failure: CapabilityDispatchFailure? = null

        for (event in pending) {
            if (event.capability != expectedCapability) {
                retained += 1
                failure = failure ?: CapabilityDispatchFailure.UNSUPPORTED_CAPABILITY
                continue
            }
            if (event.eventId in blockedEventIds) {
                retained += 1
                break
            }
            if (!egressGate.allows(event)) {
                retained += 1
                failure = failure ?: CapabilityDispatchFailure.POLICY_REVOKED
                break
            }

            val delivered = deliver(event) { dispatchFailure ->
                failure = failure ?: dispatchFailure
            }
            when (delivered) {
                DeliveryOutcome.DELIVERED -> acknowledged += 1
                DeliveryOutcome.RETAINED -> {
                    retained += 1
                    break
                }
                DeliveryOutcome.CANCELLED -> {
                    return CapabilityDispatchResult(
                        acknowledged = acknowledged,
                        retained = pending.size - acknowledged,
                        failure = CapabilityDispatchFailure.CANCELLED,
                    )
                }
            }
        }
        return CapabilityDispatchResult(acknowledged, retained, failure)
    }

    private suspend fun deliver(
        event: CapabilityDurableEvent,
        onFailure: (CapabilityDispatchFailure) -> Unit,
    ): DeliveryOutcome {
        for (attempt in 1..maxAttempts) {
            val binding = bindingSource.currentBinding()
            if (binding == null) {
                onFailure(CapabilityDispatchFailure.PAIRING_UNAVAILABLE)
                return DeliveryOutcome.RETAINED
            }
            val session = try {
                transport.open(binding)
            } catch (_: CancellationException) {
                return DeliveryOutcome.CANCELLED
            } catch (_: Exception) {
                onFailure(CapabilityDispatchFailure.TRANSPORT_FAILURE)
                continue
            }
            // `open` suspends; a local revoke may race the first fence.
            if (!egressGate.allows(event)) {
                onFailure(CapabilityDispatchFailure.POLICY_REVOKED)
                close(session, TransportCloseReason.POLICY_REVOKED)
                return DeliveryOutcome.RETAINED
            }
            // Keep a distinct final fence immediately before bytes leave the device.
            if (!egressGate.allows(event)) {
                onFailure(CapabilityDispatchFailure.POLICY_REVOKED)
                close(session, TransportCloseReason.POLICY_REVOKED)
                return DeliveryOutcome.RETAINED
            }
            val ackWire = try {
                session.sendControl(event.eventWire)
                session.receiveControl()
            } catch (_: CancellationException) {
                close(session, TransportCloseReason.PROCESS_STOPPED)
                return DeliveryOutcome.CANCELLED
            } catch (_: Exception) {
                onFailure(CapabilityDispatchFailure.TRANSPORT_FAILURE)
                close(session, TransportCloseReason.FAILURE)
                continue
            }
            try {
                outbox.acknowledge(event.eventId, ackWire)
                close(session, TransportCloseReason.PROCESS_STOPPED)
                return DeliveryOutcome.DELIVERED
            } catch (_: CancellationException) {
                close(session, TransportCloseReason.PROCESS_STOPPED)
                return DeliveryOutcome.CANCELLED
            } catch (_: CapabilityOutboxAckRejected) {
                onFailure(CapabilityDispatchFailure.ACK_REJECTED)
                close(session, TransportCloseReason.FAILURE)
            } catch (_: Exception) {
                onFailure(CapabilityDispatchFailure.OUTBOX_FAILURE)
                close(session, TransportCloseReason.FAILURE)
            }
        }
        return DeliveryOutcome.RETAINED
    }

    private suspend fun close(session: BridgeSession?, reason: TransportCloseReason) {
        if (session == null) return
        try {
            transport.close(reason)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            // No ACK removed the event, so close failure cannot alter retention.
        }
    }

    private enum class DeliveryOutcome { DELIVERED, RETAINED, CANCELLED }
}
