package com.agentlife.transport

import com.agentlife.core.model.BridgeIdentity
import com.agentlife.core.model.BridgeSession
import com.agentlife.core.model.EnrollmentTicket
import com.agentlife.core.model.PolicyAttestation
import com.agentlife.core.model.TransportCloseReason
import com.agentlife.core.model.TransportFailure
import com.agentlife.core.model.TransportPath
import com.agentlife.core.model.VerifiedPairingTransportBinding
import com.agentlife.tailnet.core.InMemoryConnectionGenerationStore
import com.agentlife.tailnet.core.InMemoryEncryptedNoBackupState
import com.agentlife.tailnet.core.LibTailscaleBinding
import com.agentlife.tailnet.core.NoBackupTailnetStateStore
import com.agentlife.tailnet.core.TsnetLibTailscaleCore
import com.agentlife.tailnet.core.TailscaleUserspaceCore
import com.agentlife.tailnet.core.UserspaceBridgeChannel
import com.agentlife.tailnet.core.VerifiedPairingTransportBindingFactory
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TransportBoundaryTest {
    @Test
    fun fake_transport_round_trips_control_bytes_and_fences_after_disconnect() {
        val transport = FakeUserspaceTransport()
        val binding = validBinding()
        val first = runSuspend { transport.open(binding) }
        transport.enqueueInbound(byteArrayOf(3, 4))
        runSuspend { first.sendControl(byteArrayOf(1, 2)) }
        assertArrayEquals(byteArrayOf(1, 2), transport.sentControl().single())
        assertArrayEquals(byteArrayOf(3, 4), runSuspend { first.receiveControl() })

        transport.disconnect()
        assertThrows(IllegalStateException::class.java) {
            runSuspend { first.sendControl(byteArrayOf(9)) }
        }
        val second = runSuspend { transport.open(binding) }
        assertEquals(first.connectionGeneration + 1u, second.connectionGeneration)
    }

    @Test
    fun binding_rejects_expired_or_wrong_bridge_material() {
        val ticket = EnrollmentTicket("ticket", "device", "bridge-a", 7u, 2u, 100)
        assertThrows(IllegalArgumentException::class.java) {
            VerifiedPairingTransportBindingFactory.mint(
                ticket,
                BridgeIdentity("bridge-b"),
                PolicyAttestation(2u, "digest"),
                expectedPairingGeneration = 7u,
                nowEpochSeconds = 10,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            VerifiedPairingTransportBindingFactory.mint(
                ticket,
                BridgeIdentity("bridge-a"),
                PolicyAttestation(2u, "digest"),
                expectedPairingGeneration = 7u,
                nowEpochSeconds = 101,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            VerifiedPairingTransportBindingFactory.mint(
                ticket.copy(used = true),
                BridgeIdentity("bridge-a"),
                PolicyAttestation(2u, "digest"),
                expectedPairingGeneration = 7u,
                nowEpochSeconds = 10,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            VerifiedPairingTransportBindingFactory.mint(
                ticket,
                BridgeIdentity("bridge-a"),
                PolicyAttestation(1u, "stale"),
                expectedPairingGeneration = 7u,
                nowEpochSeconds = 10,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            VerifiedPairingTransportBindingFactory.mint(
                ticket,
                BridgeIdentity("bridge-a"),
                PolicyAttestation(2u, "digest"),
                expectedPairingGeneration = 8u,
                nowEpochSeconds = 10,
            )
        }
    }

    @Test
    fun tsnet_adapter_calls_only_bound_bridge_operation() {
        val core = RecordingCore()
        val transport = TsnetPairedBridgeTransport(core)
        val session = runSuspend { transport.open(validBinding()) }
        runSuspend { session.sendControl(byteArrayOf(8)) }
        assertEquals(1, core.openCalls)
        assertArrayEquals(byteArrayOf(8), core.sent.single())
        runSuspend { transport.close(TransportCloseReason.USER_REQUEST) }
    }

    @Test
    fun tsnet_adapter_rejects_and_closes_a_session_when_generation_is_fenced_during_open() {
        val generations = InMemoryConnectionGenerationStore()
        val core = RecordingCore(onOpen = { generations.reserveNext() })
        val transport = TsnetPairedBridgeTransport(core, generations)

        assertThrows(IllegalStateException::class.java) {
            runSuspend { transport.open(validBinding()) }
        }
        assertEquals(1, core.closeCalls)
        assertEquals(
            com.agentlife.core.model.PairingTransportStatus.Failed(TransportFailure.STALE_GENERATION),
            transport.status(),
        )
    }

    @Test
    fun no_backup_state_is_cleared_after_enrollment() {
        val raw = InMemoryEncryptedNoBackupState()
        val authKeys = RecordingEnrollmentAuthKeyStore()
        val store = NoBackupTailnetStateStore(raw, authKeys)
        store.persistNodeState(byteArrayOf(5, 6))
        assertArrayEquals(byteArrayOf(5, 6), store.restoreNodeState())
        store.clearAfterEnrollment()
        assertArrayEquals(byteArrayOf(5, 6), store.restoreNodeState())
        assertEquals(1, authKeys.clearCalls)
    }

    @Test
    fun stale_generation_is_reported_as_fenced() {
        val generations = InMemoryConnectionGenerationStore()
        val state = com.agentlife.tailnet.core.PairingReconnectStateMachine(generations)
        val binding = validBinding()
        val first = state.beginOpen(binding)
        generations.reserveNext()
        state.markConnected(first, TransportPath.DIRECT)
        assertEquals(
            com.agentlife.core.model.PairingTransportStatus.Failed(TransportFailure.STALE_GENERATION),
            state.status,
        )
    }

    @Test
    fun persistent_generation_store_restores_and_advances_across_instances() {
        val persisted = RecordingGenerationPersistence()
        val first = com.agentlife.tailnet.core.PersistentConnectionGenerationStore(persisted)
        assertEquals(1uL, first.reserveNext())

        val restored = com.agentlife.tailnet.core.PersistentConnectionGenerationStore(persisted)
        assertEquals(1uL, restored.current())
        assertEquals(2uL, restored.reserveNext())
        assertEquals(2uL, persisted.value)
    }

    @Test
    fun tsnet_core_uses_its_constructor_state_and_clears_enrollment_auth_key() {
        val constructorRaw = InMemoryEncryptedNoBackupState()
        val constructorAuth = RecordingEnrollmentAuthKeyStore()
        val constructorState = NoBackupTailnetStateStore(constructorRaw, constructorAuth)
        constructorState.persistNodeState(byteArrayOf(1, 2))

        val passedRaw = InMemoryEncryptedNoBackupState()
        val passedState = NoBackupTailnetStateStore(passedRaw)
        passedState.persistNodeState(byteArrayOf(9))

        val binding = RecordingLibTailscaleBinding()
        val core = TsnetLibTailscaleCore(binding, constructorState)
        runSuspend { core.start("node-a", passedState) }

        assertArrayEquals(byteArrayOf(1, 2), binding.restoredState)
        assertEquals(1, constructorAuth.clearCalls)
        assertArrayEquals(byteArrayOf(9), passedState.restoreNodeState())
    }

    private fun validBinding(): VerifiedPairingTransportBinding =
        VerifiedPairingTransportBindingFactory.mint(
            ticket = EnrollmentTicket("ticket", "device", "bridge-a", 7u, 2u, 100),
            bridge = BridgeIdentity("bridge-a"),
            policy = PolicyAttestation(2u, "digest"),
            expectedPairingGeneration = 7u,
            nowEpochSeconds = 10,
        )

    private class RecordingCore(
        private val onOpen: () -> Unit = {},
    ) : TailscaleUserspaceCore {
        var openCalls = 0
        var closeCalls = 0
        val sent = mutableListOf<ByteArray>()
        private val channel = object : UserspaceBridgeChannel {
            override suspend fun sendControl(canonicalWire: ByteArray) { sent += canonicalWire.copyOf() }
            override suspend fun receiveControl(): ByteArray = byteArrayOf(0)
            override suspend fun close() { closeCalls += 1 }
        }

        override suspend fun start(nodeIdentity: String, stateStore: com.agentlife.tailnet.core.NoBackupTailnetStateStore) = Unit

        override suspend fun openPairedBridge(binding: VerifiedPairingTransportBinding): UserspaceBridgeChannel {
            openCalls += 1
            onOpen()
            return channel
        }

        override suspend fun stop() = Unit
    }

    private class RecordingGenerationPersistence : com.agentlife.tailnet.core.ConnectionGenerationPersistence {
        var value: ULong? = null

        override fun load(): ULong? = value

        override fun save(generation: ULong) {
            value = generation
        }
    }

    private class RecordingEnrollmentAuthKeyStore : com.agentlife.tailnet.core.EnrollmentAuthKeyStore {
        var clearCalls = 0

        override fun clear() {
            clearCalls += 1
        }
    }

    private class RecordingLibTailscaleBinding : LibTailscaleBinding {
        var restoredState: ByteArray? = null

        override fun startNode(nodeIdentity: String, state: ByteArray?): Any {
            restoredState = state?.copyOf()
            return Any()
        }

        override fun openPairedBridge(
            node: Any,
            binding: VerifiedPairingTransportBinding,
        ): UserspaceBridgeChannel = error("not used")

        override fun stopNode(node: Any) = Unit
    }
}

private fun <T> runSuspend(block: suspend () -> T): T {
    var result: Result<T>? = null
    block.startCoroutine(object : Continuation<T> {
        override val context = EmptyCoroutineContext
        override fun resumeWith(value: Result<T>) { result = value }
    })
    return checkNotNull(result).getOrThrow()
}
