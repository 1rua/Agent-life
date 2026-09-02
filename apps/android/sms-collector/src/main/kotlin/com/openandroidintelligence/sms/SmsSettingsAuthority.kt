package com.openandroidintelligence.sms

import com.openandroidintelligence.capability.CapabilityFilter
import com.openandroidintelligence.capability.CapabilityGrant
import com.openandroidintelligence.capability.MobileDataCapability
import com.openandroidintelligence.capability.SmsHistoryPolicy
import com.openandroidintelligence.capability.SmsSyncInterval
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.CopyOnWriteArrayList

/** App-private byte persistence; values never contain SMS message content. */
interface SmsSettingsPersistence {
    fun read(): ByteArray?
    fun write(value: ByteArray)
}

class InMemorySmsSettingsPersistence(initial: ByteArray? = null) : SmsSettingsPersistence {
    private var value = initial?.copyOf()

    override fun read(): ByteArray? = value?.copyOf()

    override fun write(value: ByteArray) {
        this.value = value.copyOf()
    }
}

data class SmsSettingsSnapshot(
    val historyPolicy: SmsHistoryPolicy,
    val syncInterval: SmsSyncInterval,
    val granted: Boolean,
    val onDemandEnabled: Boolean,
    val autoSendEnabled: Boolean,
    val agentMayRequest: Boolean,
    val policyRevision: ULong,
    val authorizationRevision: ULong,
    val corrupted: Boolean = false,
)

data class SmsFirstEnableDefaults(
    val historyPolicy: SmsHistoryPolicy,
    val syncInterval: SmsSyncInterval,
)

object SmsSettingsDefaults {
    private const val NINETY_DAYS_MS = 90L * 24 * 60 * 60 * 1000

    fun firstEnable(nowEpochMs: Long): SmsFirstEnableDefaults {
        require(nowEpochMs >= NINETY_DAYS_MS) { "first-enable clock must include ninety days" }
        return SmsFirstEnableDefaults(
            historyPolicy = SmsHistoryPolicy(fromEpochMs = nowEpochMs - NINETY_DAYS_MS, maxRecords = 500),
            syncInterval = SmsSyncInterval.MINUTES_30,
        )
    }
}

/**
 * Durable device-local SMS settings authority. The public surface is read-only;
 * only the internally constructed local controller can mutate local consent.
 */
class PersistentSmsSettingsAuthority(
    private val persistence: SmsSettingsPersistence,
) : SmsHistoryPolicySource {
    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<(SmsSettingsSnapshot) -> Unit>()
    private var current: SmsSettingsSnapshot = restore()

    override fun current(): SmsHistoryPolicy = snapshot().historyPolicy

    fun snapshot(): SmsSettingsSnapshot = synchronized(lock) { current }

    fun capabilityGrant(): CapabilityGrant? {
        return capabilityGrant(snapshot())
    }

    internal fun capabilityGrant(state: SmsSettingsSnapshot): CapabilityGrant? {
        if (state.corrupted || !state.granted) return null
        return CapabilityGrant(
            capability = MobileDataCapability.SMS,
            filter = CapabilityFilter.Sms,
            onDemandEnabled = state.onDemandEnabled,
            autoSendEnabled = state.autoSendEnabled,
            agentMayRequest = state.agentMayRequest,
            policyRevision = state.policyRevision,
        )
    }

    fun localController(): LocalSmsSettingsController = LocalSmsSettingsController(this)

    fun addListener(listener: (SmsSettingsSnapshot) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    internal fun updateLocal(
        historyPolicy: SmsHistoryPolicy,
        syncInterval: SmsSyncInterval,
        granted: Boolean,
        onDemandEnabled: Boolean,
        autoSendEnabled: Boolean,
        agentMayRequest: Boolean,
        policyRevision: ULong,
        authorizationRevision: ULong,
    ) {
        val next = synchronized(lock) {
            val previous = current
            check(!previous.corrupted) { "SMS settings evidence is corrupted" }
            require(policyRevision > previous.policyRevision) { "SMS policy revision must advance" }
            require(authorizationRevision > previous.authorizationRevision) {
                "SMS authorization revision must advance"
            }
            val candidate = SmsSettingsSnapshot(
                historyPolicy = historyPolicy,
                syncInterval = syncInterval,
                granted = granted,
                onDemandEnabled = onDemandEnabled,
                autoSendEnabled = autoSendEnabled,
                agentMayRequest = agentMayRequest,
                policyRevision = policyRevision,
                authorizationRevision = authorizationRevision,
            )
            persistence.write(encode(candidate))
            current = candidate
            candidate
        }
        listeners.forEach { it(next) }
    }

    internal fun revokeLocal(authorizationRevision: ULong) {
        val next = synchronized(lock) {
            val previous = current
            check(!previous.corrupted) { "SMS settings evidence is corrupted" }
            require(authorizationRevision > previous.authorizationRevision) {
                "SMS authorization revision must advance"
            }
            SmsSettingsSnapshot(
                historyPolicy = previous.historyPolicy,
                syncInterval = previous.syncInterval,
                granted = false,
                onDemandEnabled = false,
                autoSendEnabled = false,
                agentMayRequest = false,
                policyRevision = previous.policyRevision,
                authorizationRevision = authorizationRevision,
            ).also { candidate ->
                persistence.write(encode(candidate))
                current = candidate
            }
        }
        listeners.forEach { it(next) }
    }

    private fun restore(): SmsSettingsSnapshot {
        val bytes = persistence.read() ?: return DEFAULT
        return try {
            decode(bytes)
        } catch (_: Throwable) {
            DEFAULT.copy(corrupted = true)
        }
    }

    private fun encode(state: SmsSettingsSnapshot): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(MAGIC.size)
            output.write(MAGIC)
            output.writeBoolean(state.granted)
            output.writeBoolean(state.onDemandEnabled)
            output.writeBoolean(state.autoSendEnabled)
            output.writeBoolean(state.agentMayRequest)
            output.writeByte(state.syncInterval.ordinal)
            output.writeBoolean(state.historyPolicy.fromEpochMs != null)
            state.historyPolicy.fromEpochMs?.let(output::writeLong)
            output.writeInt(state.historyPolicy.maxRecords)
            output.writeLong(state.policyRevision.toLong())
            output.writeLong(state.authorizationRevision.toLong())
        }
        bytes.toByteArray()
    }

    private fun decode(bytes: ByteArray): SmsSettingsSnapshot = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        val magicLength = input.readInt()
        require(magicLength == MAGIC.size) { "SMS settings format mismatch" }
        val magic = ByteArray(magicLength)
        input.readFully(magic)
        check(magic.contentEquals(MAGIC)) { "SMS settings format mismatch" }
        val granted = input.readBoolean()
        val onDemandEnabled = input.readBoolean()
        val autoSendEnabled = input.readBoolean()
        val agentMayRequest = input.readBoolean()
        val syncInterval = SmsSyncInterval.entries.getOrNull(input.readUnsignedByte())
            ?: error("invalid SMS sync interval")
        val hasHistoryStart = input.readBoolean()
        val historyStart = if (hasHistoryStart) input.readLong() else null
        val maxRecords = input.readInt()
        val policyRevision = input.readLong().also { require(it >= 0) { "negative SMS policy revision" } }.toULong()
        val authorizationRevision = input.readLong()
            .also { require(it >= 0) { "negative SMS authorization revision" } }
            .toULong()
        check(input.available() == 0) { "SMS settings trailing bytes" }
        SmsSettingsSnapshot(
            historyPolicy = SmsHistoryPolicy(historyStart, maxRecords),
            syncInterval = syncInterval,
            granted = granted,
            onDemandEnabled = onDemandEnabled,
            autoSendEnabled = autoSendEnabled,
            agentMayRequest = agentMayRequest,
            policyRevision = policyRevision,
            authorizationRevision = authorizationRevision,
        )
    }

    private companion object {
        val MAGIC = "OPEN_ANDROID_INTELLIGENCE_SMS_SETTINGS_V1".encodeToByteArray()
        val DEFAULT = SmsSettingsSnapshot(
            historyPolicy = SmsHistoryPolicy(fromEpochMs = null, maxRecords = 500),
            syncInterval = SmsSyncInterval.MANUAL,
            granted = false,
            onDemandEnabled = false,
            autoSendEnabled = false,
            agentMayRequest = false,
            policyRevision = 0u,
            authorizationRevision = 0u,
        )
    }
}

/** Mutation capability intentionally held only by app-local settings composition. */
class LocalSmsSettingsController internal constructor(
    private val authority: PersistentSmsSettingsAuthority,
) {
    fun update(
        historyPolicy: SmsHistoryPolicy,
        syncInterval: SmsSyncInterval,
        granted: Boolean,
        onDemandEnabled: Boolean,
        autoSendEnabled: Boolean,
        agentMayRequest: Boolean,
        policyRevision: ULong = authority.snapshot().policyRevision + 1u,
        authorizationRevision: ULong = authority.snapshot().authorizationRevision + 1u,
    ) = authority.updateLocal(
        historyPolicy,
        syncInterval,
        granted,
        onDemandEnabled,
        autoSendEnabled,
        agentMayRequest,
        policyRevision,
        authorizationRevision,
    )

    fun revoke(authorizationRevision: ULong = authority.snapshot().authorizationRevision + 1u) =
        authority.revokeLocal(authorizationRevision)
}
