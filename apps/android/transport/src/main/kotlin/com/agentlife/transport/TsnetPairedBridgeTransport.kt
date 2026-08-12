package com.agentlife.transport

import com.agentlife.core.model.BridgeSession
import com.agentlife.core.model.PairedBridgeTransport
import com.agentlife.core.model.PairingTransportStatus
import com.agentlife.core.model.TransportCloseReason
import com.agentlife.core.model.TransportPath
import com.agentlife.core.model.VerifiedPairingTransportBinding
import com.agentlife.tailnet.core.ConnectionGenerationStore
import com.agentlife.tailnet.core.InMemoryConnectionGenerationStore
import com.agentlife.tailnet.core.PairingReconnectStateMachine
import com.agentlife.tailnet.core.TailscaleUserspaceCore
import com.agentlife.tailnet.core.UserspaceBridgeChannel

/**
 * Production-shaped adapter around the pinned tsnet/libtailscale spike. The
 * core itself is the only layer that knows how to establish the userspace
 * path; this class forwards one verified Bridge binding and fences sessions by
 * a persisted connection generation.
 */
class TsnetPairedBridgeTransport(
    private val core: TailscaleUserspaceCore,
    generationStore: ConnectionGenerationStore = InMemoryConnectionGenerationStore(),
    private val path: TransportPath = TransportPath.DIRECT,
) : PairedBridgeTransport {
    private val state = PairingReconnectStateMachine(generationStore)
    private var active: Session? = null

    override suspend fun open(binding: VerifiedPairingTransportBinding): BridgeSession {
        check(active == null) { "a paired session is already open" }
        val generation = state.beginOpen(binding)
        return try {
            val channel = core.openPairedBridge(binding)
            if (!state.markConnected(generation, path)) {
                channel.close()
                error("connection generation was fenced while opening the Bridge session")
            }
            Session(generation, channel).also { active = it }
        } catch (failure: Throwable) {
            if (state.status !is PairingTransportStatus.Failed) {
                state.close(TransportCloseReason.FAILURE)
            }
            throw failure
        }
    }

    /** Fences the current session before opening the next connection generation. */
    suspend fun reconnect(
        binding: VerifiedPairingTransportBinding,
        cause: TransportCloseReason = TransportCloseReason.NETWORK_CHANGED,
        attempt: Int = 1,
    ): BridgeSession {
        require(attempt > 0) { "attempt must be positive" }
        val previous = active
        if (previous != null) {
            active = null
            state.markDisconnected(previous.connectionGeneration, cause, attempt)
            val generationWasFenced = state.status !is PairingTransportStatus.Reconnecting
            try {
                previous.channel.close()
            } catch (failure: Throwable) {
                state.close(TransportCloseReason.FAILURE)
                throw failure
            }
            check(!generationWasFenced) {
                "connection generation was fenced before reconnect"
            }
        }
        return open(binding)
    }

    override suspend fun close(reason: TransportCloseReason) {
        val previous = active
        active = null
        try {
            previous?.channel?.close()
        } finally {
            state.close(reason)
        }
    }

    fun status() = state.status

    private inner class Session(
        override val connectionGeneration: ULong,
        val channel: UserspaceBridgeChannel,
    ) : BridgeSession {
        private fun checkCurrent() {
            check(active === this) { "session is closed or fenced" }
        }

        override suspend fun sendControl(canonicalWire: ByteArray) {
            checkCurrent()
            require(canonicalWire.isNotEmpty()) { "control wire must not be empty" }
            channel.sendControl(canonicalWire.copyOf())
        }

        override suspend fun receiveControl(): ByteArray {
            checkCurrent()
            return channel.receiveControl().copyOf()
        }
    }
}
