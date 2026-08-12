package com.agentlife.mobile

import android.app.Application
import com.agentlife.core.model.BridgeSession
import com.agentlife.core.model.DurableEvent
import com.agentlife.core.model.NotificationOutbox
import com.agentlife.core.model.NotificationRecordV1
import com.agentlife.core.model.PairedBridgeTransport
import com.agentlife.core.model.TransportCloseReason
import com.agentlife.core.model.VerifiedPairingTransportBinding
import com.agentlife.encrypted.store.AndroidKeystoreOutboxKeyProvider
import com.agentlife.encrypted.store.EventAckVerifier
import com.agentlife.encrypted.store.FileEncryptedOutboxPersistence
import com.agentlife.encrypted.store.NotificationOutboxStore
import com.agentlife.notifications.AndroidNotificationCollector
import com.agentlife.notifications.NotificationBridgeDispatcher
import com.agentlife.notifications.NotificationRecordEgressGate
import com.agentlife.notifications.NotificationRuntime
import com.agentlife.notifications.NotificationRuntimeFactory
import com.agentlife.notifications.NotificationRuntimeFactoryRegistry
import com.agentlife.notifications.PairedBridgeBindingSource
import com.agentlife.policy.FileNotificationPolicyPersistence
import com.agentlife.policy.LocalNotificationPolicyController
import com.agentlife.policy.NotificationAuthoritySnapshot
import com.agentlife.policy.PersistentNotificationPolicyAuthority
import java.io.File

/**
 * Verifier supplied by the authenticated pairing/control-wire subsystem. Its
 * implementation must bind an ACK to the current device, event and connection
 * generation; an opaque Boolean or unsigned event ID is not sufficient.
 */
fun interface AuthenticatedBoundEventAckVerifier {
    fun verify(eventId: String, eventAckWire: ByteArray): Boolean
}

data class PairedNotificationBridgeRuntime(
    val transport: PairedBridgeTransport,
    val binding: VerifiedPairingTransportBinding,
    val ackVerifier: AuthenticatedBoundEventAckVerifier,
)

/** Pairing lifecycle installs only already verified material, never an endpoint. */
object PairedNotificationBridgeRegistry {
    @Volatile
    private var runtime: PairedNotificationBridgeRuntime? = null

    fun install(value: PairedNotificationBridgeRuntime) {
        runtime = value
    }

    fun clear() {
        runtime = null
    }

    internal fun current(): PairedNotificationBridgeRuntime? = runtime
}

class AgentLifeApplication : Application() {
    private lateinit var notificationAuthority: PersistentNotificationPolicyAuthority
    private lateinit var notificationOutbox: NotificationOutbox

    override fun onCreate() {
        super.onCreate()
        notificationAuthority = PersistentNotificationPolicyAuthority(
            FileNotificationPolicyPersistence(
                File(noBackupFilesDir, "notification-authority-v1.bin"),
            ),
        )
        notificationOutbox = createOutboxFailClosed()

        NotificationRuntimeFactoryRegistry.install(NotificationRuntimeFactory { scope ->
            val collector = AndroidNotificationCollector(authorization = notificationAuthority)
            collector.applyPolicyBlocking(notificationAuthority.snapshot().policy)
            val dispatcher = NotificationBridgeDispatcher(
                outbox = notificationOutbox,
                transport = RegistryPairedBridgeTransport,
                bindingSource = PairedBridgeBindingSource {
                    PairedNotificationBridgeRegistry.current()?.binding
                },
                egressGate = NotificationRecordEgressGate(notificationAuthority::allows),
            )
            NotificationRuntime(
                initialCollector = collector,
                outbox = notificationOutbox,
                scope = scope,
                egressGate = NotificationRecordEgressGate(notificationAuthority::allows),
                dispatcher = dispatcher,
                policyAuthority = notificationAuthority,
            )
        })
    }

    /** The local settings UI owns this capability; no Agent/wire type exposes it. */
    fun localNotificationPolicyController(): LocalNotificationPolicyController =
        notificationAuthority.localController()

    /** Read-only local snapshot for refreshing the settings view on resume. */
    fun localNotificationAuthoritySnapshot(): NotificationAuthoritySnapshot =
        notificationAuthority.snapshot()

    private fun createOutboxFailClosed(): NotificationOutbox = try {
        NotificationOutboxStore(
            persistence = FileEncryptedOutboxPersistence(
                File(noBackupFilesDir, "notification-outbox-v1.aesgcm"),
            ),
            encryptionKey = AndroidKeystoreOutboxKeyProvider().getOrCreate(),
            ackVerifier = EventAckVerifier { eventId, ackWire ->
                PairedNotificationBridgeRegistry.current()
                    ?.ackVerifier
                    ?.verify(eventId, ackWire) == true
            },
        )
    } catch (failure: Throwable) {
        // Preserve corrupt ciphertext/key evidence. The listener still starts,
        // but every storage/dispatch operation fails closed and emits no data.
        FailClosedNotificationOutbox(failure)
    }
}

private object RegistryPairedBridgeTransport : PairedBridgeTransport {
    @Volatile
    private var active: PairedNotificationBridgeRuntime? = null

    override suspend fun open(binding: VerifiedPairingTransportBinding): BridgeSession {
        val selected = checkNotNull(PairedNotificationBridgeRegistry.current()) {
            "paired Bridge is unavailable"
        }
        check(selected.binding.sameIdentity(binding)) { "paired Bridge binding changed" }
        return selected.transport.open(selected.binding).also { active = selected }
    }

    override suspend fun close(reason: TransportCloseReason) {
        active?.transport?.close(reason)
        active = null
    }
}

private fun VerifiedPairingTransportBinding.sameIdentity(other: VerifiedPairingTransportBinding): Boolean =
    deviceId == other.deviceId &&
        bridgeIdentity == other.bridgeIdentity &&
        pairingGeneration == other.pairingGeneration &&
        policyAttestationRevision == other.policyAttestationRevision &&
        enrollmentTicketId == other.enrollmentTicketId

private class FailClosedNotificationOutbox(
    private val failure: Throwable,
) : NotificationOutbox {
    override suspend fun enqueueAccepted(record: NotificationRecordV1): DurableEvent = unavailable()

    override suspend fun acknowledge(eventId: String, eventAckWire: ByteArray): Unit = unavailable()

    override suspend fun recoverUnacknowledged(): List<DurableEvent> = unavailable()

    private fun <T> unavailable(): T = throw IllegalStateException("notification outbox unavailable", failure)
}
