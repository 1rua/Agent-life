package com.agentlife.transport

import com.agentlife.core.model.PairedBridgeTransport
import com.agentlife.core.model.PairingTransportStatus
import com.agentlife.core.model.VerifiedPairingTransportBinding
import com.agentlife.tailnet.core.AndroidTsnetBinding
import com.agentlife.tailnet.core.ConnectionGenerationPersistence
import com.agentlife.tailnet.core.EncryptedNoBackupState
import com.agentlife.tailnet.core.NativeEnrollmentSource
import com.agentlife.tailnet.core.NoBackupTailnetStateStore
import com.agentlife.tailnet.core.PersistentConnectionGenerationStore

/**
 * Opaque production transport returned by [ProductionTailnetTransportFactory].
 * The app can observe status through this interface but cannot construct an
 * alternative implementation or inject an endpoint/fake.
 */
sealed interface ProductionPairedBridgeTransport : PairedBridgeTransport {
    fun status(): PairingTransportStatus
}

/**
 * Sealed production composition. All concrete state paths and key aliases are
 * resolved here; callers only supply the verified enrollment source and the
 * app-private state primitives.
 */
class ProductionTailnetTransportFactory(
    private val enrollmentSource: NativeEnrollmentSource,
    private val nodeIdentity: String,
    private val nodeState: EncryptedNoBackupState,
    private val generationPersistence: ConnectionGenerationPersistence,
) {
    fun create(): ProductionPairedBridgeTransport {
        val stateStore = NoBackupTailnetStateStore(nodeState)
        val core = AndroidTsnetBinding(enrollmentSource)
        val generations = PersistentConnectionGenerationStore(generationPersistence)
        val coordinator = PairedBridgeSessionCoordinator(
            core = core,
            nodeIdentity = nodeIdentity,
            stateStore = stateStore,
            generationStore = generations,
        )
        return SealedProductionPairedBridgeTransport(coordinator)
    }

    private class SealedProductionPairedBridgeTransport(
        private val delegate: PairedBridgeSessionCoordinator,
    ) : ProductionPairedBridgeTransport {
        override fun status(): PairingTransportStatus = delegate.status()

        override suspend fun open(binding: VerifiedPairingTransportBinding) = delegate.open(binding)

        override suspend fun close(reason: com.agentlife.core.model.TransportCloseReason) = delegate.close(reason)
    }
}
