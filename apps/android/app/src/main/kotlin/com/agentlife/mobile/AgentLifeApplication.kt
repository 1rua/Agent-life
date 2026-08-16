package com.agentlife.mobile

import android.app.Application
import com.agentlife.core.model.BridgeSession
import com.agentlife.core.model.DurableEvent
import com.agentlife.core.model.NotificationOutbox
import com.agentlife.core.model.NotificationRecordV1
import com.agentlife.core.model.PairedBridgeTransport
import com.agentlife.core.model.TransportCloseReason
import com.agentlife.tailnet.core.FileConnectionGenerationPersistence
import com.agentlife.tailnet.core.KeystoreEncryptedNoBackupState
import com.agentlife.tailnet.core.NativeEnrollmentSource
import com.agentlife.transport.ProductionPairedBridgeTransport
import com.agentlife.transport.ProductionTailnetTransportFactory
import com.agentlife.core.model.VerifiedPairingTransportBinding
import com.agentlife.encrypted.store.AndroidKeystoreOutboxKeyProvider
import com.agentlife.encrypted.store.CapabilityOutboxStore
import com.agentlife.encrypted.store.EventAckVerifier
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
import com.agentlife.capability.CapabilityAvailability
import com.agentlife.encrypted.store.FileEncryptedOutboxPersistence
import com.agentlife.sms.AndroidSmsCapabilityProvider
import com.agentlife.sms.AndroidSmsInboxReader
import com.agentlife.sms.AndroidSmsSyncScheduler
import com.agentlife.sms.FileSmsCursorStore
import com.agentlife.sms.InMemorySmsSettingsPersistence
import com.agentlife.sms.LocalSmsSettingsController
import com.agentlife.sms.PersistentSmsSettingsAuthority
import com.agentlife.sms.SmsAutoSendEgressGate
import com.agentlife.sms.SmsAutoSyncCoordinator
import com.agentlife.sms.SmsAutoSyncRunner
import com.agentlife.sms.SmsJobScheduler
import com.agentlife.sms.SmsPairedBridgeBindingSource
import com.agentlife.sms.SmsRuntime
import com.agentlife.sms.SmsRuntimeFactory
import com.agentlife.sms.SmsRuntimeFactoryRegistry
import com.agentlife.sms.SmsSettingsPersistence
import com.agentlife.sms.SmsSettingsSnapshot
import com.agentlife.sms.SmsWireCodec
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
    val transport: ProductionPairedBridgeTransport,
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
    private lateinit var smsAuthority: PersistentSmsSettingsAuthority
    private lateinit var smsScheduler: SmsJobScheduler

    /** Sealed production composition for the userspace Bridge. */
    fun createTailnetTransportFactory(
        enrollmentSource: NativeEnrollmentSource,
    ): ProductionTailnetTransportFactory = ProductionTailnetTransportFactory(
        enrollmentSource = enrollmentSource,
        nodeIdentity = NODE_IDENTITY,
        nodeState = KeystoreEncryptedNoBackupState(
            file = File(noBackupFilesDir, NODE_STATE_FILE),
            alias = NODE_STATE_KEY_ALIAS,
        ),
        generationPersistence = FileConnectionGenerationPersistence(
            File(noBackupFilesDir, CONNECTION_GENERATION_FILE),
        ),
    )

    override fun onCreate() {
        super.onCreate()
        notificationAuthority = PersistentNotificationPolicyAuthority(
            FileNotificationPolicyPersistence(
                File(noBackupFilesDir, "notification-authority-v1.bin"),
            ),
        )
        notificationOutbox = createOutboxFailClosed()

        smsAuthority = createSmsAuthorityFailClosed()
        smsScheduler = createSmsSchedulerFailClosed()
        restoreSmsScheduling()
        val smsRuntime = createSmsRuntimeFailClosed()
        SmsRuntimeFactoryRegistry.install(SmsRuntimeFactory { smsRuntime })

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

    /** The SMS local-settings screen is the only caller handed this mutating capability. */
    fun localSmsSettingsController(): LocalSmsSettingsController = smsAuthority.localController()

    fun smsSettingsSnapshot(): SmsSettingsSnapshot = smsAuthority.snapshot()

    fun smsPermissionAvailability(readSmsPermissionGranted: Boolean): CapabilityAvailability {
        val snapshot = smsAuthority.snapshot()
        return when {
            snapshot.corrupted || !snapshot.granted -> CapabilityAvailability.DISABLED
            !readSmsPermissionGranted -> CapabilityAvailability.PERMISSION_REQUIRED
            else -> CapabilityAvailability.READY
        }
    }

    fun smsJobScheduler(): SmsJobScheduler = smsScheduler

    private fun restoreSmsScheduling() {
        try {
            val snapshot = smsAuthority.snapshot()
            if (snapshot.corrupted || !snapshot.granted || !snapshot.autoSendEnabled) return
            val interval = snapshot.syncInterval
            if (interval.periodMs == null) return
            smsScheduler.schedule(interval)
        } catch (_: Throwable) {
            // Scheduling restoration is best-effort; failure must not block app start.
        }
    }

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

    private fun createSmsAuthorityFailClosed(): PersistentSmsSettingsAuthority = try {
        PersistentSmsSettingsAuthority(
            FileSmsSettingsPersistence(File(noBackupFilesDir, SMS_SETTINGS_FILE)),
        )
    } catch (_: Throwable) {
        PersistentSmsSettingsAuthority(InMemorySmsSettingsPersistence(byteArrayOf(0)))
    }

    private fun createSmsSchedulerFailClosed(): SmsJobScheduler = try {
        AndroidSmsSyncScheduler(this)
    } catch (_: Throwable) {
        object : SmsJobScheduler {
            override fun schedule(interval: com.agentlife.capability.SmsSyncInterval) = Unit
            override fun cancel() = Unit
        }
    }

    private fun createSmsRuntimeFailClosed(): SmsRuntime = try {
        val cursorStore = FileSmsCursorStore.fromContext(this)
        val provider = AndroidSmsCapabilityProvider(
            reader = AndroidSmsInboxReader(contentResolver),
            historyPolicySource = smsAuthority,
            cursorSource = cursorStore,
        )
        val outbox = CapabilityOutboxStore(
            persistence = FileEncryptedOutboxPersistence(File(noBackupFilesDir, SMS_OUTBOX_FILE)),
            encryptionKey = AndroidKeystoreOutboxKeyProvider(SMS_OUTBOX_KEY_ALIAS).getOrCreate(),
            ackVerifier = EventAckVerifier { eventId, ackWire ->
                PairedNotificationBridgeRegistry.current()
                    ?.ackVerifier
                    ?.verify(eventId, ackWire) == true
            },
        )
        val coordinator = SmsAutoSyncCoordinator(
            provider = provider,
            outbox = outbox,
            cursorStore = cursorStore,
            eventEncoder = SmsWireCodec(),
            transport = RegistryPairedBridgeTransport,
            bindingSource = SmsPairedBridgeBindingSource {
                PairedNotificationBridgeRegistry.current()?.binding
            },
            egressGate = SmsAutoSendEgressGate { event ->
                val settings = smsAuthority.snapshot()
                !settings.corrupted && settings.granted && settings.autoSendEnabled &&
                    event.policyRevision == settings.policyRevision
            },
        )
        SmsRuntime(smsAuthority, SmsAutoSyncRunner { subscription -> coordinator.runOnce(subscription) })
    } catch (_: Throwable) {
        SmsRuntime.denyFirst()
    }

    private companion object {
        const val NODE_IDENTITY = "agent-life-android"
        const val NODE_STATE_FILE = "tailnet-node-state-v1.aesgcm"
        const val NODE_STATE_KEY_ALIAS = "agent_life_tailnet_node_state_v1"
        const val CONNECTION_GENERATION_FILE = "tailnet-connection-generation-v1.txt"
        const val SMS_SETTINGS_FILE = "sms-settings-v1.bin"
        const val SMS_OUTBOX_FILE = "sms-outbox-v1.aesgcm"
        const val SMS_OUTBOX_KEY_ALIAS = "agent_life_sms_outbox_v1"
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

/** App-private SMS consent evidence; the file contains no SMS body text. */
private class FileSmsSettingsPersistence(private val file: File) : SmsSettingsPersistence {
    override fun read(): ByteArray? = if (file.isFile) file.readBytes() else null

    override fun write(value: ByteArray) {
        file.parentFile?.mkdirs()
        val parent = file.parentFile ?: file.absoluteFile.parentFile ?: error("SMS settings file has no parent")
        val temporary = File(parent, "${file.name}.tmp")
        temporary.writeBytes(value)
        check(temporary.renameTo(file)) { "unable to atomically persist SMS settings" }
    }
}