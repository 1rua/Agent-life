package com.agentlife.calls

import com.agentlife.encrypted.store.AesGcmEncryptedBlobStore
import com.agentlife.encrypted.store.AesGcmKeyProvider
import com.agentlife.encrypted.store.EncryptedBlobCorrupted
import com.agentlife.encrypted.store.EncryptedOutboxPersistence
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

data class CallLogSyncState(
    val sourceEpoch: ULong,
    val cursor: CallLogCursor?,
    val policyRevision: ULong,
) {
    init {
        require(sourceEpoch > 0u) { "call log source epoch must be positive" }
    }

    override fun toString(): String =
        "CallLogSyncState(cursorPresent=${cursor != null},policyRevision=$policyRevision)"
}

interface CallLogSyncStateStore {
    fun snapshot(): CallLogSyncState?
    fun initialize(state: CallLogSyncState)
    fun advance(expectedSourceEpoch: ULong, cursor: CallLogCursor, policyRevision: ULong): Boolean
    fun resetWithRotatedKey(state: CallLogSyncState)
}

class CallLogSyncStateCorrupted : IllegalStateException("CALL_LOG_SYNC_STATE_CORRUPTED")
class CallLogSourceEpochExhausted : IllegalStateException("CALL_LOG_SOURCE_EPOCH_EXHAUSTED")

fun nextCallLogSourceEpoch(current: ULong): ULong {
    if (current == ULong.MAX_VALUE) throw CallLogSourceEpochExhausted()
    return current + 1u
}

/**
 * Stateful cursor persistence is deliberately lazy: construction cannot expose
 * stale ciphertext.  Every update authenticates, writes first, then publishes
 * its in-memory value.
 */
class EncryptedCallLogSyncStateStore(
    private val persistence: EncryptedOutboxPersistence,
    private val keyProvider: AesGcmKeyProvider,
) : CallLogSyncStateStore {
    private val lock = Any()
    private var loaded = false
    private var current: CallLogSyncState? = null

    override fun snapshot(): CallLogSyncState? = synchronized(lock) {
        loadIfNeeded()
        current
    }

    override fun initialize(state: CallLogSyncState) = synchronized(lock) {
        loadIfNeeded()
        check(current == null) { "call log sync state is already initialized" }
        persist(state)
        current = state
    }

    override fun advance(
        expectedSourceEpoch: ULong,
        cursor: CallLogCursor,
        policyRevision: ULong,
    ): Boolean = synchronized(lock) {
        loadIfNeeded()
        val prior = current ?: return@synchronized false
        require(expectedSourceEpoch == prior.sourceEpoch) { "call log source epoch changed" }
        require(policyRevision == prior.policyRevision) { "call log policy revision changed" }
        if (prior.cursor != null && cursor <= prior.cursor) return@synchronized false
        val next = prior.copy(cursor = cursor)
        persist(next)
        current = next
        true
    }

    override fun resetWithRotatedKey(state: CallLogSyncState) = synchronized(lock) {
        // Do not call loadIfNeeded: revocation must work even if the old key or
        // ciphertext has already been destroyed or is corrupt.
        keyProvider.delete()
        persistence.clear()
        val blob = AesGcmEncryptedBlobStore(persistence, keyProvider.getOrCreate())
        blob.writePlaintext(encode(state))
        current = state
        loaded = true
    }

    private fun loadIfNeeded() {
        if (loaded) return
        val existing = persistence.read()
        if (existing == null) {
            current = null
            loaded = true
            return
        }
        val oneReadPersistence = object : EncryptedOutboxPersistence {
            private var supplied = existing.copyOf()
            override fun read(): ByteArray? = supplied.copyOf().also { supplied.fill(0) }
            override fun write(ciphertext: ByteArray) = persistence.write(ciphertext)
            override fun clear() = persistence.clear()
        }
        val decoded = try {
            AesGcmEncryptedBlobStore(oneReadPersistence, keyProvider.getOrCreate())
                .readPlaintext()
                ?.let(::decode)
                ?: throw CallLogSyncStateCorrupted()
        } catch (_: EncryptedBlobCorrupted) {
            throw CallLogSyncStateCorrupted()
        } catch (_: CallLogSyncStateCorrupted) {
            throw CallLogSyncStateCorrupted()
        } catch (_: Exception) {
            throw CallLogSyncStateCorrupted()
        }
        current = decoded
        loaded = true
    }

    private fun persist(state: CallLogSyncState) {
        AesGcmEncryptedBlobStore(persistence, keyProvider.getOrCreate()).writePlaintext(encode(state))
    }

    private fun encode(state: CallLogSyncState): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeUTF(MAGIC)
            output.writeLong(state.sourceEpoch.toLong())
            output.writeBoolean(state.cursor != null)
            state.cursor?.let {
                output.writeLong(it.startedAtEpochMs)
                output.writeLong(it.providerId)
            }
            output.writeLong(state.policyRevision.toLong())
        }
        bytes.toByteArray()
    }

    private fun decode(value: ByteArray): CallLogSyncState = try {
        DataInputStream(ByteArrayInputStream(value)).use { input ->
            check(input.readUTF() == MAGIC)
            val epoch = input.readLong().toULong()
            val cursorPresent = input.readUnsignedByte().also { check(it == 0 || it == 1) } == 1
            val cursor = if (cursorPresent) CallLogCursor(input.readLong(), input.readLong()) else null
            val policyRevision = input.readLong().toULong()
            check(input.available() == 0)
            CallLogSyncState(epoch, cursor, policyRevision)
        }
    } catch (_: Exception) {
        throw CallLogSyncStateCorrupted()
    }

    private companion object {
        const val MAGIC = "AGENT_LIFE_CALL_SYNC_STATE_V1"
    }
}
