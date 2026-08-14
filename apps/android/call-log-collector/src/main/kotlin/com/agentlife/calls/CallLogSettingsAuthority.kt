package com.agentlife.calls

import com.agentlife.capability.CallCounterpartyAccess
import com.agentlife.capability.CallDirection
import com.agentlife.capability.CallHistoryPolicy
import com.agentlife.capability.CallLogSyncInterval
import com.agentlife.capability.CapabilityFilter
import com.agentlife.capability.CapabilityGrant
import com.agentlife.capability.MobileDataCapability
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** App-private persistence seam. Persisted bytes never contain call records. */
interface CallLogSettingsPersistence {
    fun read(): ByteArray?
    fun write(value: ByteArray)
}

class InMemoryCallLogSettingsPersistence(initial: ByteArray? = null) : CallLogSettingsPersistence {
    private var value = initial?.copyOf()

    override fun read(): ByteArray? = value?.copyOf()

    override fun write(value: ByteArray) {
        this.value = value.copyOf()
    }
}

/** Atomic file persistence for [File] `Context.noBackupFilesDir` children. */
class FileCallLogSettingsPersistence private constructor(private val file: File) : CallLogSettingsPersistence {
    override fun read(): ByteArray? = if (file.isFile) file.readBytes() else null

    override fun write(value: ByteArray) {
        file.parentFile?.mkdirs()
        val parent = file.parentFile ?: file.absoluteFile.parentFile
            ?: error("call-log settings file has no parent")
        val temporary = File.createTempFile("${file.name}.", ".tmp", parent)
        try {
            temporary.outputStream().use { output ->
                output.write(value)
                output.fd.sync()
            }
            Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    companion object {
        const val FILE_NAME = "call-log-settings-v1.bin"

        fun fromNoBackupFilesDir(noBackupFilesDir: File): FileCallLogSettingsPersistence =
            FileCallLogSettingsPersistence(File(noBackupFilesDir, FILE_NAME))
    }
}

/**
 * Immutable local policy. The public constructor copies the supplied direction
 * set so callers cannot mutate a persisted or active authorization policy.
 */
@ConsistentCopyVisibility
data class CallLogLocalPolicy private constructor(
    val historyPolicy: CallHistoryPolicy,
    private val directionSnapshot: Set<CallDirection>,
    val counterpartyAccess: CallCounterpartyAccess,
    val syncInterval: CallLogSyncInterval,
    val onDemandEnabled: Boolean,
    val autoSendEnabled: Boolean,
    val agentMayRequest: Boolean,
    val policyRevision: ULong,
) {
    constructor(
        historyPolicy: CallHistoryPolicy,
        directions: Collection<CallDirection>,
        counterpartyAccess: CallCounterpartyAccess,
        syncInterval: CallLogSyncInterval,
        onDemandEnabled: Boolean,
        autoSendEnabled: Boolean,
        agentMayRequest: Boolean,
        policyRevision: ULong,
    ) : this(
        historyPolicy = historyPolicy,
        directionSnapshot = directions.toSet(),
        counterpartyAccess = counterpartyAccess,
        syncInterval = syncInterval,
        onDemandEnabled = onDemandEnabled,
        autoSendEnabled = autoSendEnabled,
        agentMayRequest = agentMayRequest,
        policyRevision = policyRevision,
    )

    init {
        require(directionSnapshot.isNotEmpty()) { "call directions must not be empty" }
        require(policyRevision > 0u) { "call policy revision must be positive" }
    }

    val directions: Set<CallDirection>
        get() = directionSnapshot.toSet()

    fun filter(): CapabilityFilter.Calls = CapabilityFilter.Calls(directions, counterpartyAccess)

    override fun toString(): String =
        "CallLogLocalPolicy(directions=${directionSnapshot.size},counterpartyAccess=$counterpartyAccess," +
            "syncInterval=$syncInterval,onDemand=$onDemandEnabled,autoSend=$autoSendEnabled," +
            "agentMayRequest=$agentMayRequest,policyRevision=$policyRevision)"
}

sealed interface CallLogSettingsPhase {
    data object Disabled : CallLogSettingsPhase

    data class Enabled(val policy: CallLogLocalPolicy) : CallLogSettingsPhase

    data class Revoking(
        val targetEpoch: ULong,
        val targetPolicyRevision: ULong,
        val targetPolicy: CallLogLocalPolicy?,
    ) : CallLogSettingsPhase {
        override fun toString(): String =
            "Revoking(targetPolicyRevision=$targetPolicyRevision,targetEnabled=${targetPolicy != null})"
    }
}

data class CallLogSettingsSnapshot(
    val phase: CallLogSettingsPhase,
    val authorizationRevision: ULong,
    val corrupted: Boolean = false,
    val epochExhausted: Boolean = false,
    val policyRevisionFloor: ULong = phase.policyRevision(),
) {
    override fun toString(): String =
        "CallLogSettingsSnapshot(phase=${phase::class.simpleName},authorizationRevision=$authorizationRevision," +
            "policyRevisionFloor=$policyRevisionFloor,corrupted=$corrupted,epochExhausted=$epochExhausted)"
}

/**
 * Durable deny-first call-log settings authority. Its public surface cannot
 * create, change, or re-enable a local grant; Task 11 wires local controls
 * after the revocation cleanup effects exist.
 */
class PersistentCallLogSettingsAuthority(
    private val persistence: CallLogSettingsPersistence,
) {
    private val lock = Any()
    private var current: CallLogSettingsSnapshot = restore()

    fun snapshot(): CallLogSettingsSnapshot = synchronized(lock) { current.copy(phase = current.phase.copyForSnapshot()) }

    fun capabilityGrant(): CapabilityGrant? = synchronized(lock) {
        val state = current
        if (state.corrupted || state.epochExhausted) return@synchronized null
        val policy = (state.phase as? CallLogSettingsPhase.Enabled)?.policy ?: return@synchronized null
        CapabilityGrant(
            capability = MobileDataCapability.CALLS,
            filter = policy.filter(),
            onDemandEnabled = policy.onDemandEnabled,
            autoSendEnabled = policy.autoSendEnabled,
            agentMayRequest = policy.agentMayRequest,
            policyRevision = policy.policyRevision,
        )
    }

    /** Persist a grant removal before callers perform its cleanup effects. */
    internal fun beginRevocation(
        targetEpoch: ULong,
        targetPolicyRevision: ULong,
        targetPolicy: CallLogLocalPolicy?,
        authorizationRevision: ULong,
        epochExhausted: Boolean = false,
    ) {
        synchronized(lock) {
            val previous = current
            check(!previous.corrupted) { "call-log settings evidence is corrupted" }
            check(!previous.epochExhausted) { "call-log settings epoch is exhausted" }
            check(previous.phase !is CallLogSettingsPhase.Revoking) { "call-log revocation is already in progress" }
            require(authorizationRevision > previous.authorizationRevision) {
                "call-log authorization revision must advance"
            }
            require(targetPolicyRevision > previous.policyRevisionFloor) {
                "call-log policy revision must advance"
            }
            if (previous.phase is CallLogSettingsPhase.Disabled) {
                require(targetPolicy != null) { "disabled state may only bootstrap an enabled policy" }
            }
            targetPolicy?.let { policy ->
                require(policy.policyRevision == targetPolicyRevision) {
                    "call-log revocation target policy revision mismatch"
                }
            }

            val candidate = CallLogSettingsSnapshot(
                phase = CallLogSettingsPhase.Revoking(
                    targetEpoch = targetEpoch,
                    targetPolicyRevision = targetPolicyRevision,
                    targetPolicy = targetPolicy?.copyForStorage(),
                ),
                authorizationRevision = authorizationRevision,
                epochExhausted = epochExhausted,
                policyRevisionFloor = targetPolicyRevision,
            )
            persistence.write(CallLogSettingsCodec.encode(candidate))
            current = candidate
        }
    }

    /** Finalizes exactly the target persisted by [beginRevocation]; no replacement is accepted. */
    internal fun commitRevocationTarget() {
        synchronized(lock) {
            val previous = current
            check(!previous.corrupted) { "call-log settings evidence is corrupted" }
            val revoking = previous.phase as? CallLogSettingsPhase.Revoking
                ?: throw IllegalStateException("call-log settings are not revoking")
            val candidate = CallLogSettingsSnapshot(
                phase = revoking.targetPolicy?.let { CallLogSettingsPhase.Enabled(it.copyForStorage()) }
                    ?: CallLogSettingsPhase.Disabled,
                authorizationRevision = previous.authorizationRevision,
                epochExhausted = previous.epochExhausted,
                policyRevisionFloor = previous.policyRevisionFloor,
            )
            persistence.write(CallLogSettingsCodec.encode(candidate))
            current = candidate
        }
    }

    private fun restore(): CallLogSettingsSnapshot {
        val bytes = try {
            persistence.read()
        } catch (_: Exception) {
            return CORRUPTED_DEFAULT
        }
        if (bytes == null) return DEFAULT
        return try {
            CallLogSettingsCodec.decode(bytes)
        } catch (_: Exception) {
            CORRUPTED_DEFAULT
        }
    }

    private companion object {
        val DEFAULT = CallLogSettingsSnapshot(
            phase = CallLogSettingsPhase.Disabled,
            authorizationRevision = 0u,
        )
        val CORRUPTED_DEFAULT = DEFAULT.copy(corrupted = true)
    }
}

private fun CallLogSettingsPhase.policyRevision(): ULong = when (this) {
    CallLogSettingsPhase.Disabled -> 0u
    is CallLogSettingsPhase.Enabled -> policy.policyRevision
    is CallLogSettingsPhase.Revoking -> targetPolicyRevision
}

private fun CallLogSettingsPhase.copyForSnapshot(): CallLogSettingsPhase = when (this) {
    CallLogSettingsPhase.Disabled -> CallLogSettingsPhase.Disabled
    is CallLogSettingsPhase.Enabled -> CallLogSettingsPhase.Enabled(policy.copyForStorage())
    is CallLogSettingsPhase.Revoking -> CallLogSettingsPhase.Revoking(
        targetEpoch,
        targetPolicyRevision,
        targetPolicy?.copyForStorage(),
    )
}

private fun CallLogLocalPolicy.copyForStorage(): CallLogLocalPolicy = CallLogLocalPolicy(
    historyPolicy = historyPolicy,
    directions = directions,
    counterpartyAccess = counterpartyAccess,
    syncInterval = syncInterval,
    onDemandEnabled = onDemandEnabled,
    autoSendEnabled = autoSendEnabled,
    agentMayRequest = agentMayRequest,
    policyRevision = policyRevision,
)

/** Strict binary codec; malformed, unknown, or trailing representations are rejected. */
internal object CallLogSettingsCodec {
    private val magic = "AGENT_LIFE_CALL_SETTINGS_V1".encodeToByteArray()
    internal val phaseOffset: Int
        get() = magic.size
    internal val authorizationRevisionOffset: Int
        get() = phaseOffset + 1
    internal val enabledModeBooleanOffset: Int
        get() = phaseOffset + 1 + Long.SIZE_BYTES + 1 + Long.SIZE_BYTES + 1 + Long.SIZE_BYTES + Int.SIZE_BYTES + 3

    fun encode(snapshot: CallLogSettingsSnapshot): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.write(magic)
            when (val phase = snapshot.phase) {
                CallLogSettingsPhase.Disabled -> output.writeByte(DISABLED_TAG)
                is CallLogSettingsPhase.Enabled -> {
                    output.writeByte(ENABLED_TAG)
                    output.writeULong(snapshot.authorizationRevision)
                    output.writeBoolean(snapshot.epochExhausted)
                    output.writeULong(snapshot.policyRevisionFloor)
                    output.writePolicy(phase.policy)
                    return@use
                }
                is CallLogSettingsPhase.Revoking -> {
                    output.writeByte(REVOKING_TAG)
                    output.writeULong(snapshot.authorizationRevision)
                    output.writeBoolean(snapshot.epochExhausted)
                    output.writeULong(snapshot.policyRevisionFloor)
                    output.writeULong(phase.targetEpoch)
                    output.writeULong(phase.targetPolicyRevision)
                    output.writeBoolean(phase.targetPolicy != null)
                    phase.targetPolicy?.let { policy -> output.writePolicy(policy) }
                    return@use
                }
            }
            output.writeULong(snapshot.authorizationRevision)
            output.writeBoolean(snapshot.epochExhausted)
            output.writeULong(snapshot.policyRevisionFloor)
        }
        bytes.toByteArray()
    }

    fun decode(bytes: ByteArray): CallLogSettingsSnapshot = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
        val storedMagic = ByteArray(magic.size)
        input.readFully(storedMagic)
        require(storedMagic.contentEquals(magic)) { "call-log settings format mismatch" }
        when (input.readUnsignedByte()) {
            DISABLED_TAG -> CallLogSettingsSnapshot(
                phase = CallLogSettingsPhase.Disabled,
                authorizationRevision = input.readULong(),
                epochExhausted = input.readCanonicalBoolean(),
                policyRevisionFloor = input.readULong(),
            )
            ENABLED_TAG -> {
                val authorizationRevision = input.readULong()
                val exhausted = input.readCanonicalBoolean()
                val policyRevisionFloor = input.readULong()
                require(authorizationRevision > 0u) { "enabled authorization revision must be positive" }
                CallLogSettingsSnapshot(
                    phase = CallLogSettingsPhase.Enabled(input.readPolicy()),
                    authorizationRevision = authorizationRevision,
                    epochExhausted = exhausted,
                    policyRevisionFloor = policyRevisionFloor,
                ).also { state ->
                    val policy = (state.phase as CallLogSettingsPhase.Enabled).policy
                    require(policyRevisionFloor > 0u && policy.policyRevision == policyRevisionFloor) {
                        "enabled policy revision floor mismatch"
                    }
                }
            }
            REVOKING_TAG -> {
                val authorizationRevision = input.readULong()
                val exhausted = input.readCanonicalBoolean()
                val policyRevisionFloor = input.readULong()
                val targetEpoch = input.readULong()
                val targetRevision = input.readULong()
                val target = if (input.readCanonicalBoolean()) input.readPolicy() else null
                require(authorizationRevision > 0u) { "revoking authorization revision must be positive" }
                require(targetRevision > 0u) { "revoking target policy revision must be positive" }
                require(policyRevisionFloor == targetRevision) {
                    "revoking policy revision floor mismatch"
                }
                require(target == null || target.policyRevision == targetRevision) {
                    "call-log revocation target policy revision mismatch"
                }
                CallLogSettingsSnapshot(
                    phase = CallLogSettingsPhase.Revoking(targetEpoch, targetRevision, target),
                    authorizationRevision = authorizationRevision,
                    epochExhausted = exhausted,
                    policyRevisionFloor = policyRevisionFloor,
                )
            }
            else -> throw IllegalArgumentException("unknown call-log settings phase")
        }.also {
            require(input.available() == 0) { "call-log settings trailing bytes" }
        }
    }

    private fun DataOutputStream.writePolicy(policy: CallLogLocalPolicy) {
        outputHistory(policy.historyPolicy)
        val mask = CallDirection.entries.fold(0) { current, direction ->
            if (direction in policy.directions) current or (1 shl direction.ordinal) else current
        }
        writeByte(mask)
        writeByte(policy.counterpartyAccess.ordinal)
        writeByte(policy.syncInterval.ordinal)
        writeBoolean(policy.onDemandEnabled)
        writeBoolean(policy.autoSendEnabled)
        writeBoolean(policy.agentMayRequest)
        writeULong(policy.policyRevision)
    }

    private fun DataOutputStream.outputHistory(history: CallHistoryPolicy) {
        writeBoolean(history.fromEpochMs != null)
        history.fromEpochMs?.let(::writeLong)
        writeInt(history.maxRecords)
    }

    private fun DataInputStream.readPolicy(): CallLogLocalPolicy {
        val hasHistoryStart = readCanonicalBoolean()
        val historyStart = if (hasHistoryStart) readLong() else null
        val maxRecords = readInt()
        val mask = readUnsignedByte()
        val knownMask = (1 shl CallDirection.entries.size) - 1
        require(mask and knownMask == mask && mask != 0) { "invalid call direction mask" }
        val directions = CallDirection.entries.filter { direction -> mask and (1 shl direction.ordinal) != 0 }.toSet()
        val counterpartyAccess = CallCounterpartyAccess.entries.getOrNull(readUnsignedByte())
            ?: throw IllegalArgumentException("invalid call counterparty access")
        val syncInterval = CallLogSyncInterval.entries.getOrNull(readUnsignedByte())
            ?: throw IllegalArgumentException("invalid call sync interval")
        return CallLogLocalPolicy(
            historyPolicy = CallHistoryPolicy(historyStart, maxRecords),
            directions = directions,
            counterpartyAccess = counterpartyAccess,
            syncInterval = syncInterval,
            onDemandEnabled = readCanonicalBoolean(),
            autoSendEnabled = readCanonicalBoolean(),
            agentMayRequest = readCanonicalBoolean(),
            policyRevision = readULong(),
        )
    }

    private fun DataOutputStream.writeULong(value: ULong) = writeLong(value.toLong())

    private fun DataInputStream.readULong(): ULong = readLong().toULong()

    private fun DataInputStream.readCanonicalBoolean(): Boolean = when (readUnsignedByte()) {
        0 -> false
        1 -> true
        else -> throw IllegalArgumentException("non-canonical call-log settings boolean")
    }

    private const val DISABLED_TAG = 0
    private const val ENABLED_TAG = 1
    private const val REVOKING_TAG = 2
}
