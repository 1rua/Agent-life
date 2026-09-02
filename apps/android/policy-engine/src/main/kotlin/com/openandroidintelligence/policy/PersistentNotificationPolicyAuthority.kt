package com.openandroidintelligence.policy

import com.openandroidintelligence.core.model.AuthorizationDecision
import com.openandroidintelligence.core.model.NotificationAuthorization
import com.openandroidintelligence.core.model.NotificationCollectionPolicyV1
import com.openandroidintelligence.core.model.NotificationDeliveryMode
import com.openandroidintelligence.core.model.NotificationFieldAccess
import com.openandroidintelligence.core.model.NotificationRecordV1
import com.openandroidintelligence.core.model.NotificationRuleMode
import com.openandroidintelligence.core.model.PolicyRevisionRace
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.charset.CodingErrorAction
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CopyOnWriteArrayList

/** Persistence supplied by app-private no-backup storage. */
interface NotificationPolicyPersistence {
    fun read(): ByteArray?
    fun write(value: ByteArray)
}

class InMemoryNotificationPolicyPersistence : NotificationPolicyPersistence {
    private var value: ByteArray? = null

    override fun read(): ByteArray? = value?.copyOf()

    override fun write(value: ByteArray) {
        this.value = value.copyOf()
    }
}

/** File adapter intended only for a child of Context.noBackupFilesDir. */
class FileNotificationPolicyPersistence(private val file: File) : NotificationPolicyPersistence {
    override fun read(): ByteArray? = if (file.isFile) file.readBytes() else null

    override fun write(value: ByteArray) {
        file.parentFile?.mkdirs()
        val parent = file.parentFile ?: file.absoluteFile.parentFile ?: error("policy file has no parent")
        val temporary = File(parent, "${file.name}.tmp")
        temporary.writeBytes(value)
        check(temporary.renameTo(file)) { "unable to atomically persist notification policy" }
    }
}

class PolicyStateCorrupted(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

class NotificationDeliveryAdmissionPermit internal constructor(
    private val semaphore: Semaphore,
) : AutoCloseable {
    private val released = AtomicBoolean(false)

    override fun close() {
        if (released.compareAndSet(false, true)) semaphore.release()
    }
}

data class NotificationAuthoritySnapshot(
    val policy: NotificationCollectionPolicyV1,
    val authorizationRevision: ULong,
    val granted: Boolean,
    val corrupted: Boolean = false,
    val deliveryMode: NotificationDeliveryMode = NotificationDeliveryMode.ON_DEMAND,
)

/**
 * Durable, device-local notification authority. Remote control/Agent message
 * contracts receive only this class's read-only NotificationAuthorization
 * view; only the separately wired local controller can change consent.
 */
class PersistentNotificationPolicyAuthority(
    private val persistence: NotificationPolicyPersistence,
) : NotificationAuthorization {
    private val lock = Any()
    private val deliveryAdmission = Semaphore(1, true)
    private val listeners = CopyOnWriteArrayList<(NotificationAuthoritySnapshot) -> Unit>()
    private var current: NotificationAuthoritySnapshot = restore()

    fun snapshot(): NotificationAuthoritySnapshot = synchronized(lock) {
        current.copy(policy = current.policy.copy(packageIds = current.policy.packageIds.toList()))
    }

    fun localController(): LocalNotificationPolicyController = LocalNotificationPolicyController(this)

    fun addListener(listener: (NotificationAuthoritySnapshot) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    /**
     * Serializes local policy commits with auto-send enqueue admission. The
     * permit may be held across a suspension and must be closed exactly once.
     */
    fun acquireDeliveryAdmissionPermit(): NotificationDeliveryAdmissionPermit {
        try {
            deliveryAdmission.acquire()
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("interrupted while acquiring notification delivery admission", interrupted)
        }
        return NotificationDeliveryAdmissionPermit(deliveryAdmission)
    }

    override fun decide(
        packageName: String,
        fieldAccess: NotificationFieldAccess,
        policyRevision: ULong,
    ): AuthorizationDecision {
        val state = snapshot()
        if (state.corrupted) return AuthorizationDecision.deny("LOCAL_POLICY_CORRUPTED")
        if (!state.granted) return AuthorizationDecision.deny("LOCAL_GRANT_REQUIRED")
        if (policyRevision != state.policy.policyRevision) {
            return AuthorizationDecision.deny("AUTHORIZATION_REVISION_STALE")
        }
        if (fieldAccess != state.policy.fieldAccess) {
            return AuthorizationDecision.deny("FIELD_ACCESS_NOT_GRANTED")
        }
        if (!matches(state.policy, packageName)) return AuthorizationDecision.deny("PACKAGE_NOT_ALLOWED")
        return AuthorizationDecision.allow()
    }

    /** Current-revision gate shared by enqueue and Bridge dispatch. */
    fun allows(record: NotificationRecordV1): Boolean {
        val state = snapshot()
        if (state.corrupted || !state.granted || record.captureRevision != state.policy.policyRevision) return false
        if (record is NotificationRecordV1.LossMarker) return true
        val metadata = record.metadata ?: return false
        if (!matches(state.policy, metadata.packageName)) return false
        return record.content == null || state.policy.fieldAccess == NotificationFieldAccess.CONTENT
    }

    internal fun applyLocal(
        policy: NotificationCollectionPolicyV1,
        authorizationRevision: ULong,
        granted: Boolean,
        deliveryMode: NotificationDeliveryMode?,
    ) {
        val permit = acquireDeliveryAdmissionPermit()
        val next = try {
            synchronized(lock) {
                val previous = current
                val nextDeliveryMode = deliveryMode ?: previous.deliveryMode
                if (previous.corrupted) throw PolicyStateCorrupted("notification policy evidence is corrupted")
                if (policy.policyRevision < previous.policy.policyRevision) {
                    throw PolicyRevisionRace("policy revision rollback")
                }
                if (authorizationRevision < previous.authorizationRevision) {
                    throw PolicyRevisionRace("authorization revision rollback")
                }
                if (policy.policyRevision == previous.policy.policyRevision && policy != previous.policy) {
                    throw PolicyRevisionRace("policy contents changed without a revision")
                }
                if (authorizationRevision == previous.authorizationRevision && granted != previous.granted) {
                    throw PolicyRevisionRace("authorization changed without a revision")
                }
                if (policy != previous.policy && authorizationRevision == previous.authorizationRevision) {
                    throw PolicyRevisionRace("policy changed without an authorization revision")
                }
                if (nextDeliveryMode != previous.deliveryMode && authorizationRevision == previous.authorizationRevision) {
                    throw PolicyRevisionRace("delivery mode changed without an authorization revision")
                }
                NotificationAuthoritySnapshot(
                    policy = policy.copy(packageIds = policy.packageIds.toList()),
                    authorizationRevision = authorizationRevision,
                    granted = granted,
                    deliveryMode = nextDeliveryMode,
                ).also { candidate ->
                    // Commit durable state before exposing a grant in memory.
                    persistence.write(encode(candidate))
                    current = candidate
                }
            }
        } finally {
            // Listener callbacks may synchronously call back into the authority.
            permit.close()
        }
        listeners.forEach { it(next) }
    }

    private fun restore(): NotificationAuthoritySnapshot {
        val bytes = persistence.read() ?: return DEFAULT
        return try {
            decode(bytes)
        } catch (_: Throwable) {
            // Preserve corrupt bytes for local diagnostics/recovery and deny.
            DEFAULT.copy(corrupted = true)
        }
    }

    private fun matches(policy: NotificationCollectionPolicyV1, packageName: String): Boolean {
        if (packageName.isBlank()) return false
        return when (policy.mode) {
            NotificationRuleMode.ALLOWLIST -> packageName in policy.packageIds
            NotificationRuleMode.DENYLIST -> packageName !in policy.packageIds
        }
    }

    private fun encode(value: NotificationAuthoritySnapshot): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            writeString(output, MAGIC_V2)
            output.writeLong(value.authorizationRevision.toLong())
            output.writeBoolean(value.granted)
            output.writeByte(value.deliveryMode.ordinal)
            output.writeByte(value.policy.mode.ordinal)
            output.writeByte(value.policy.fieldAccess.ordinal)
            output.writeLong(value.policy.policyRevision.toLong())
            output.writeInt(value.policy.packageIds.size)
            value.policy.packageIds.forEach { writeString(output, it) }
        }
        bytes.toByteArray()
    }

    private fun decode(bytes: ByteArray): NotificationAuthoritySnapshot = DataInputStream(
        ByteArrayInputStream(bytes),
    ).use { input ->
        val magic = readString(input)
        val authorizationRevision = input.readLong().toULong()
        val granted = readBoolean(input)
        val deliveryMode = when (magic) {
            MAGIC_V1 -> NotificationDeliveryMode.ON_DEMAND
            MAGIC_V2 -> NotificationDeliveryMode.entries.getOrNull(input.readUnsignedByte())
                ?: error("invalid delivery mode")
            else -> error("policy format mismatch")
        }
        val mode = NotificationRuleMode.entries.getOrNull(input.readUnsignedByte()) ?: error("invalid policy mode")
        val access = NotificationFieldAccess.entries.getOrNull(input.readUnsignedByte()) ?: error("invalid field access")
        val policyRevision = input.readLong().toULong()
        val count = input.readInt()
        require(count in 0..MAX_PACKAGES) { "invalid package count" }
        val packages = List(count) { readString(input) }
        check(input.available() == 0) { "policy trailing bytes" }
        NotificationAuthoritySnapshot(
            policy = NotificationCollectionPolicyV1(mode, packages, access, policyRevision),
            authorizationRevision = authorizationRevision,
            granted = granted,
            deliveryMode = deliveryMode,
        )
    }

    private fun writeString(output: DataOutputStream, value: String) {
        val bytes = value.toByteArray(UTF8)
        require(bytes.size <= MAX_STRING_BYTES) { "policy string is too large" }
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readString(input: DataInputStream): String {
        val size = input.readInt()
        require(size in 0..MAX_STRING_BYTES) { "invalid policy string size" }
        val bytes = ByteArray(size).also(input::readFully)
        return UTF8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }

    private fun readBoolean(input: DataInputStream): Boolean = when (input.readUnsignedByte()) {
        0 -> false
        1 -> true
        else -> error("invalid policy boolean")
    }

    companion object {
        private const val MAGIC_V1 = "OPEN_ANDROID_INTELLIGENCE_NOTIFICATION_AUTHORITY_V1"
        private const val MAGIC_V2 = "OPEN_ANDROID_INTELLIGENCE_NOTIFICATION_AUTHORITY_V2"
        private const val MAX_PACKAGES = 10_000
        private const val MAX_STRING_BYTES = 1024
        private val UTF8 = StandardCharsets.UTF_8
        private val DEFAULT = NotificationAuthoritySnapshot(
            policy = NotificationCollectionPolicyV1.default(),
            authorizationRevision = 0u,
            granted = false,
            deliveryMode = NotificationDeliveryMode.ON_DEMAND,
        )
    }
}

/** Mutation capability intentionally held only by local Android settings UI. */
class LocalNotificationPolicyController internal constructor(
    private val authority: PersistentNotificationPolicyAuthority,
) {
    fun apply(
        policy: NotificationCollectionPolicyV1,
        authorizationRevision: ULong,
        granted: Boolean,
    ) = authority.applyLocal(policy, authorizationRevision, granted, deliveryMode = null)

    fun apply(
        policy: NotificationCollectionPolicyV1,
        authorizationRevision: ULong,
        granted: Boolean,
        deliveryMode: NotificationDeliveryMode,
    ) = authority.applyLocal(policy, authorizationRevision, granted, deliveryMode)

    fun revoke(authorizationRevision: ULong) {
        val snapshot = authority.snapshot()
        authority.applyLocal(snapshot.policy, authorizationRevision, granted = false, deliveryMode = null)
    }
}
