package com.agentlife.transport

import com.agentlife.core.model.BridgeIdentity
import com.agentlife.core.model.BridgeSession
import com.agentlife.core.model.EnrollmentTicket
import com.agentlife.core.model.PairedBridgeTransport
import com.agentlife.core.model.PairingTransportStatus
import com.agentlife.core.model.PolicyAttestation
import com.agentlife.core.model.TransportCloseReason
import com.agentlife.core.model.TransportPath
import com.agentlife.core.model.VerifiedPairingTransportBinding
import com.agentlife.tailnet.core.ConnectionGenerationStore
import com.agentlife.tailnet.core.NoBackupTailnetStateStore
import com.agentlife.tailnet.core.TailscaleUserspaceCore
import com.agentlife.tailnet.core.VerifiedPairingTransportBindingFactory
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Bridge-issued values required to mint a ticket-bound transport binding. */
data class PairingMaterial(
    val ticket: EnrollmentTicket,
    val bridge: BridgeIdentity,
    val policy: PolicyAttestation,
    val expectedPairingGeneration: ULong,
    val nowEpochSeconds: Long,
)

/**
 * Owns one userspace core lifecycle and one fenced Bridge session lifecycle.
 * Binding validation is deliberately performed before taking the lifecycle
 * mutex so rejected reconnect material cannot disturb an active session.
 */
class PairedBridgeSessionCoordinator(
    private val core: TailscaleUserspaceCore,
    private val nodeIdentity: String,
    private val stateStore: NoBackupTailnetStateStore,
    generationStore: ConnectionGenerationStore,
    path: TransportPath = TransportPath.DIRECT,
) : PairedBridgeTransport {
    private val transport = TsnetPairedBridgeTransport(core, generationStore, path)
    private val lifecycle = Mutex()
    private var coreStarted = false

    suspend fun connect(material: PairingMaterial): BridgeSession =
        open(mintBinding(material))

    suspend fun reconnect(
        material: PairingMaterial,
        cause: TransportCloseReason = TransportCloseReason.NETWORK_CHANGED,
        attempt: Int = 1,
    ): BridgeSession {
        val binding = mintBinding(material)
        return lifecycle.withLock {
            try {
                ensureCoreStarted()
                transport.reconnect(binding, cause, attempt)
            } catch (failure: Throwable) {
                cleanupAfterFailure(failure)
                throw failure
            }
        }
    }

    override suspend fun open(binding: VerifiedPairingTransportBinding): BridgeSession =
        lifecycle.withLock {
            try {
                ensureCoreStarted()
                transport.open(binding)
            } catch (failure: Throwable) {
                cleanupAfterFailure(failure)
                throw failure
            }
        }

    override suspend fun close(reason: TransportCloseReason) {
        lifecycle.withLock {
            var failure: Throwable? = null
            try {
                transport.close(reason)
            } catch (caught: Throwable) {
                failure = caught
            } finally {
                if (coreStarted) {
                    try {
                        core.stop()
                    } catch (caught: Throwable) {
                        if (failure == null) {
                            failure = caught
                        } else {
                            failure?.addSuppressed(caught)
                        }
                    } finally {
                        coreStarted = false
                    }
                }
            }
            failure?.let { throw it }
        }
    }

    fun status(): PairingTransportStatus = transport.status()

    private suspend fun ensureCoreStarted() {
        if (coreStarted) return
        // Mark the lifecycle as owned before calling into the adapter so a
        // partially-started core is still stopped by failure cleanup.
        coreStarted = true
        core.start(nodeIdentity, stateStore)
    }

    private suspend fun cleanupAfterFailure(failure: Throwable) {
        val currentStatus = transport.status()
        if (currentStatus !is PairingTransportStatus.Closed &&
            currentStatus !is PairingTransportStatus.Failed
        ) {
            try {
                transport.close(TransportCloseReason.FAILURE)
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            }
        }
        if (coreStarted) {
            try {
                core.stop()
            } catch (cleanupFailure: Throwable) {
                failure.addSuppressed(cleanupFailure)
            } finally {
                coreStarted = false
            }
        }
    }

    private fun mintBinding(material: PairingMaterial): VerifiedPairingTransportBinding =
        VerifiedPairingTransportBindingFactory.mint(
            ticket = material.ticket,
            bridge = material.bridge,
            policy = material.policy,
            expectedPairingGeneration = material.expectedPairingGeneration,
            nowEpochSeconds = material.nowEpochSeconds,
        )
}
