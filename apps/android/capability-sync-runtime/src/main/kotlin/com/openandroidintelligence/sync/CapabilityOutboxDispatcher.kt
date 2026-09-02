package com.openandroidintelligence.sync

import com.openandroidintelligence.core.model.CapabilityDurableEvent
import com.openandroidintelligence.core.model.CapabilityOutbox
import com.openandroidintelligence.core.model.CapabilityOutboxAckRejected
import com.openandroidintelligence.core.model.PairedBridgeTransport
import com.openandroidintelligence.core.model.TransportCloseReason
import com.openandroidintelligence.core.model.VerifiedPairingTransportBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

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
        } catch (_: CancellationException) {
            // Recovery did not return an insertion-ordered snapshot, so this
            // result deliberately reports no acknowledged or enumerated entries.
            return CapabilityDispatchResult(0, 0, CapabilityDispatchFailure.CANCELLED)
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
            val delivered = deliver(event) { dispatchFailure ->
                failure = failure ?: dispatchFailure
            }
            when (delivered) {
                DeliveryOutcome.DELIVERED -> acknowledged += 1
                DeliveryOutcome.ACKNOWLEDGED_CANCELLED -> {
                    acknowledged += 1
                    return CapabilityDispatchResult(
                        acknowledged = acknowledged,
                        retained = pending.size - acknowledged,
                        failure = CapabilityDispatchFailure.CANCELLED,
                    )
                }
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
            // Each attempt owns its own pre-open fence. A prior recoverable
            // failure must not let the next open race a local revocation.
            if (!egressGate.allows(event)) {
                onFailure(CapabilityDispatchFailure.POLICY_REVOKED)
                return DeliveryOutcome.RETAINED
            }
            val binding = bindingSource.currentBinding()
            if (binding == null) {
                onFailure(CapabilityDispatchFailure.PAIRING_UNAVAILABLE)
                return DeliveryOutcome.RETAINED
            }
            val session = try {
                transport.open(binding)
            } catch (_: CancellationException) {
                cleanup(TransportCloseReason.PROCESS_STOPPED)
                return DeliveryOutcome.CANCELLED
            } catch (_: Exception) {
                onFailure(CapabilityDispatchFailure.TRANSPORT_FAILURE)
                if (cleanup(TransportCloseReason.FAILURE) == CleanupOutcome.CANCELLED) {
                    return DeliveryOutcome.CANCELLED
                }
                continue
            }
            // `open` suspends; a local revoke may race the first fence.
            if (!egressGate.allows(event)) {
                onFailure(CapabilityDispatchFailure.POLICY_REVOKED)
                if (cleanup(TransportCloseReason.POLICY_REVOKED) == CleanupOutcome.CANCELLED) {
                    return DeliveryOutcome.CANCELLED
                }
                return DeliveryOutcome.RETAINED
            }
            // Keep a distinct final fence immediately before bytes leave the device.
            if (!egressGate.allows(event)) {
                onFailure(CapabilityDispatchFailure.POLICY_REVOKED)
                if (cleanup(TransportCloseReason.POLICY_REVOKED) == CleanupOutcome.CANCELLED) {
                    return DeliveryOutcome.CANCELLED
                }
                return DeliveryOutcome.RETAINED
            }
            val ackWire = try {
                session.sendControl(event.eventWire)
                session.receiveControl()
            } catch (_: CancellationException) {
                cleanup(TransportCloseReason.PROCESS_STOPPED)
                return DeliveryOutcome.CANCELLED
            } catch (_: Exception) {
                onFailure(CapabilityDispatchFailure.TRANSPORT_FAILURE)
                if (cleanup(TransportCloseReason.FAILURE) == CleanupOutcome.CANCELLED) {
                    return DeliveryOutcome.CANCELLED
                }
                continue
            }
            val acknowledged = try {
                outbox.acknowledge(event.eventId, ackWire)
                true
            } catch (_: CancellationException) {
                cleanup(TransportCloseReason.PROCESS_STOPPED)
                return DeliveryOutcome.CANCELLED
            } catch (_: CapabilityOutboxAckRejected) {
                onFailure(CapabilityDispatchFailure.ACK_REJECTED)
                if (cleanup(TransportCloseReason.FAILURE) == CleanupOutcome.CANCELLED) {
                    return DeliveryOutcome.CANCELLED
                }
                false
            } catch (_: Exception) {
                onFailure(CapabilityDispatchFailure.OUTBOX_FAILURE)
                if (cleanup(TransportCloseReason.FAILURE) == CleanupOutcome.CANCELLED) {
                    return DeliveryOutcome.CANCELLED
                }
                false
            }
            if (acknowledged) {
                return when (cleanup(TransportCloseReason.PROCESS_STOPPED)) {
                    CleanupOutcome.COMPLETE -> DeliveryOutcome.DELIVERED
                    CleanupOutcome.CANCELLED -> DeliveryOutcome.ACKNOWLEDGED_CANCELLED
                }
            }
        }
        return DeliveryOutcome.RETAINED
    }

    private suspend fun cleanup(reason: TransportCloseReason): CleanupOutcome {
        val callerJob = currentCoroutineContext()[Job]
        return withContext(NonCancellable) {
            val closeOutcome = try {
                transport.close(reason)
                CleanupOutcome.COMPLETE
            } catch (_: CancellationException) {
                // Cleanup cancellation cannot undo an ACK or retention outcome.
                CleanupOutcome.CANCELLED
            } catch (_: Exception) {
                // A recoverable close failure cannot alter an ACK or retention outcome.
                CleanupOutcome.COMPLETE
            }
            if (closeOutcome == CleanupOutcome.CANCELLED || callerJob?.isCancelled == true) {
                CleanupOutcome.CANCELLED
            } else {
                CleanupOutcome.COMPLETE
            }
        }
    }

    private enum class CleanupOutcome { COMPLETE, CANCELLED }

    private enum class DeliveryOutcome { DELIVERED, ACKNOWLEDGED_CANCELLED, RETAINED, CANCELLED }
}
