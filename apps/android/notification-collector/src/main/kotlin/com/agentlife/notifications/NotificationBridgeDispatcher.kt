package com.agentlife.notifications

import com.agentlife.core.model.BridgeSession
import com.agentlife.core.model.NotificationOutbox
import com.agentlife.core.model.NotificationRecordV1
import com.agentlife.core.model.PairedBridgeTransport
import com.agentlife.core.model.TransportCloseReason
import com.agentlife.core.model.VerifiedPairingTransportBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

fun interface PairedBridgeBindingSource {
    fun currentBinding(): VerifiedPairingTransportBinding?
}

fun interface NotificationRecordEgressGate {
    fun allows(record: NotificationRecordV1): Boolean
}

enum class NotificationDispatchIssue {
    PAIRING_UNAVAILABLE,
    POLICY_REVOKED,
    ACK_REJECTED,
    RETRY_PENDING,
}

fun interface NotificationDispatchReporter {
    fun report(issue: NotificationDispatchIssue)
}

data class NotificationDispatchResult(
    val recovered: Int,
    val sent: Int,
    val acknowledged: Int,
    val retainedByPolicy: Int,
    val retryPending: Int,
)

/**
 * Narrow at-least-once outbox drain. It never accepts an address or arbitrary
 * payload: every send is the exact canonical wire recovered from the durable
 * outbox and every deletion goes through the outbox's authenticated ACK gate.
 */
class NotificationBridgeDispatcher(
    private val outbox: NotificationOutbox,
    private val transport: PairedBridgeTransport,
    private val bindingSource: PairedBridgeBindingSource,
    private val egressGate: NotificationRecordEgressGate,
    private val maxAttempts: Int = 3,
    private val reporter: NotificationDispatchReporter = NotificationDispatchReporter { },
) {
    private val dispatchLock = Mutex()
    private var session: BridgeSession? = null

    init {
        require(maxAttempts in 1..10) { "dispatch attempts must be between 1 and 10" }
    }

    suspend fun dispatchPending(): NotificationDispatchResult = dispatchLock.withLock {
        val pending = outbox.recoverUnacknowledged()
        var sent = 0
        var acknowledged = 0
        var retainedByPolicy = 0
        var retryPending = 0

        if (pending.none { egressGate.allows(it.record) }) {
            retainedByPolicy = pending.size
            if (pending.isNotEmpty()) reporter.report(NotificationDispatchIssue.POLICY_REVOKED)
            return@withLock NotificationDispatchResult(
                pending.size,
                sent,
                acknowledged,
                retainedByPolicy,
                retryPending,
            )
        }

        val binding = bindingSource.currentBinding()
        if (binding == null) {
            reporter.report(NotificationDispatchIssue.PAIRING_UNAVAILABLE)
            return@withLock NotificationDispatchResult(pending.size, 0, 0, 0, pending.size)
        }

        for (event in pending) {
            if (!egressGate.allows(event.record)) {
                retainedByPolicy += 1
                reporter.report(NotificationDispatchIssue.POLICY_REVOKED)
                continue
            }
            var completed = false
            for (attempt in 1..maxAttempts) {
                // Re-check immediately before every send, including retries.
                if (!egressGate.allows(event.record)) {
                    retainedByPolicy += 1
                    reporter.report(NotificationDispatchIssue.POLICY_REVOKED)
                    completed = true
                    break
                }
                try {
                    val active = session ?: transport.open(binding).also { session = it }
                    active.sendControl(event.canonicalWire)
                    sent += 1
                    val ackWire = active.receiveControl()
                    // The outbox removes only if its authenticated/bound verifier accepts this ACK.
                    outbox.acknowledge(event.eventId, ackWire)
                    acknowledged += 1
                    completed = true
                    break
                } catch (_: IllegalArgumentException) {
                    // Invalid/forged ACK: retain the event and retry the same bytes.
                    reporter.report(NotificationDispatchIssue.ACK_REJECTED)
                } catch (failure: Throwable) {
                    if (failure is CancellationException) throw failure
                    closeSession(TransportCloseReason.FAILURE)
                }
                if (attempt < maxAttempts) reporter.report(NotificationDispatchIssue.RETRY_PENDING)
            }
            if (!completed) {
                retryPending += 1
                reporter.report(NotificationDispatchIssue.RETRY_PENDING)
            }
        }

        NotificationDispatchResult(pending.size, sent, acknowledged, retainedByPolicy, retryPending)
    }

    suspend fun stop() = dispatchLock.withLock {
        closeSession(TransportCloseReason.PROCESS_STOPPED)
    }

    private suspend fun closeSession(reason: TransportCloseReason) {
        if (session != null) transport.close(reason)
        session = null
    }
}
