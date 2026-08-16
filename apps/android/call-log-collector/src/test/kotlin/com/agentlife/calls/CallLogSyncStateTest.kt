package com.agentlife.calls

import com.agentlife.encrypted.store.AesGcmKeyProvider
import com.agentlife.encrypted.store.AesGcmEncryptedBlobStore
import com.agentlife.encrypted.store.EncryptedOutboxPersistence
import com.agentlife.encrypted.store.InMemoryOutboxPersistence
import java.security.SecureRandom
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CallLogSyncStateTest {
    @Test
    fun cursor_advances_only_in_expected_epoch_and_strictly_forward() {
        val store = encryptedStateStore()
        store.initialize(CallLogSyncState(1u, null, 7u))
        assertTrue(store.advance(1u, CallLogCursor(100, 1), 7u))
        assertFalse(store.advance(1u, CallLogCursor(100, 1), 7u))
        assertFalse(store.advance(1u, CallLogCursor(99, 2), 7u))
        assertThrows(IllegalArgumentException::class.java) {
            store.advance(2u, CallLogCursor(101, 2), 7u)
        }
    }

    @Test
    fun reset_rotates_key_resets_cursor_and_never_wraps_epoch() {
        val keys = RecordingKeyProvider()
        val store = encryptedStateStore(keys)
        store.initialize(CallLogSyncState(1u, CallLogCursor(100, 1), 7u))
        store.resetWithRotatedKey(CallLogSyncState(2u, null, 8u))
        assertEquals(1, keys.deleteCalls)
        assertEquals(CallLogSyncState(2u, null, 8u), store.snapshot())
        assertThrows(CallLogSourceEpochExhausted::class.java) {
            nextCallLogSourceEpoch(ULong.MAX_VALUE)
        }
    }

    @Test
    fun invalid_states_and_policy_mismatch_are_rejected() {
        assertThrows(IllegalArgumentException::class.java) { CallLogSyncState(0u, null, 1u) }
        val store = encryptedStateStore()
        store.initialize(CallLogSyncState(1u, null, 7u))
        assertThrows(IllegalArgumentException::class.java) { store.advance(1u, CallLogCursor(1, 1), 8u) }
    }

    @Test
    fun state_restarts_and_corrupt_formats_fail_closed_without_diagnostics_leakage() {
        val persistence = InMemoryOutboxPersistence()
        val keys = RecordingKeyProvider()
        val first = EncryptedCallLogSyncStateStore(persistence, keys)
        first.initialize(CallLogSyncState(1u, CallLogCursor(2, 3), 7u))
        assertEquals(CallLogSyncState(1u, CallLogCursor(2, 3), 7u), EncryptedCallLogSyncStateStore(persistence, keys).snapshot())

        persistence.write(checkNotNull(persistence.bytes) + byteArrayOf(1))
        val failure = assertThrows(CallLogSyncStateCorrupted::class.java) {
            EncryptedCallLogSyncStateStore(persistence, keys).snapshot()
        }
        assertEquals("CALL_LOG_SYNC_STATE_CORRUPTED", failure.message)
        assertNull(failure.cause)
    }

    @Test
    fun encrypted_state_loading_preserves_key_provider_cancellation() {
        val persistence = InMemoryOutboxPersistence().also { it.write(byteArrayOf(1)) }
        val keys = object : AesGcmKeyProvider {
            override fun getOrCreate(): SecretKey = throw CancellationException("key private")
            override fun delete() = Unit
        }

        val failure = assertThrows(CancellationException::class.java) {
            EncryptedCallLogSyncStateStore(persistence, keys).snapshot()
        }

        assertEquals("key private", failure.message)
    }

    @Test
    fun independent_outer_and_inner_corruption_paths_are_fail_closed_and_redacted() {
        val key = SecretKeySpec(ByteArray(32) { 9 }, "AES")
        val validPlain = encodedState(CallLogSyncState(1u, CallLogCursor(2, 3), 7u))
        val goodPersistence = InMemoryOutboxPersistence().also {
            AesGcmEncryptedBlobStore(it, key).writePlaintext(validPlain)
        }
        val good = checkNotNull(goodPersistence.bytes)
        val cases = listOf(
            good.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() },
            good.copyOf(3),
            good.copyOf().also { it[4] = 'X'.code.toByte() },
            good + byteArrayOf(1),
            envelopeWithIv(key, ByteArray(11) { 4 }, validPlain),
            InMemoryOutboxPersistence().also { AesGcmEncryptedBlobStore(it, key).writePlaintext("unknown-state".encodeToByteArray()) }.bytes!!,
        )

        cases.forEach { bytes ->
            val failure = assertThrows(CallLogSyncStateCorrupted::class.java) {
                EncryptedCallLogSyncStateStore(InMemoryOutboxPersistence().also { it.write(bytes) }, FixedKeyProvider(key)).snapshot()
            }
            assertEquals("CALL_LOG_SYNC_STATE_CORRUPTED", failure.message)
            assertNull(failure.cause)
        }
    }

    @Test
    fun missing_state_is_uninitialized_and_reset_does_not_decode_old_ciphertext() {
        val persistence = CountingPersistence()
        val keys = RecordingKeyProvider()
        val store = EncryptedCallLogSyncStateStore(persistence, keys)
        assertNull(store.snapshot())
        val readsBeforeReset = persistence.readCalls
        persistence.bytes = byteArrayOf(1, 2, 3)
        val target = CallLogSyncState(2u, null, 8u)
        store.resetWithRotatedKey(target)
        assertEquals(readsBeforeReset, persistence.readCalls)
        assertEquals(target, store.snapshot())
    }

    @Test
    fun reset_is_idempotent_after_clear_or_write_failure_and_persists_exact_target() {
        val keys = RecordingKeyProvider()
        val persistence = FlakyPersistence()
        val store = EncryptedCallLogSyncStateStore(persistence, keys)
        val target = CallLogSyncState(3u, null, 11u)

        persistence.failNextClear = true
        assertThrows(IllegalStateException::class.java) { store.resetWithRotatedKey(target) }
        store.resetWithRotatedKey(target)
        assertEquals(target, store.snapshot())

        persistence.failNextWrite = true
        assertThrows(IllegalStateException::class.java) { store.resetWithRotatedKey(target) }
        store.resetWithRotatedKey(target)
        assertEquals(target, store.snapshot())
        assertEquals(4, keys.deleteCalls)
    }

    private fun encryptedStateStore(keys: RecordingKeyProvider = RecordingKeyProvider()) =
        EncryptedCallLogSyncStateStore(InMemoryOutboxPersistence(), keys)

    private class RecordingKeyProvider : AesGcmKeyProvider {
        private var key: SecretKey? = null
        var deleteCalls = 0
        override fun getOrCreate(): SecretKey = key ?: SecretKeySpec(ByteArray(32).also(SecureRandom()::nextBytes), "AES").also { key = it }
        override fun delete() { deleteCalls += 1; key = null }
    }

    private class FixedKeyProvider(private val key: SecretKey) : AesGcmKeyProvider {
        override fun getOrCreate(): SecretKey = key
        override fun delete() = Unit
    }

    private class CountingPersistence : EncryptedOutboxPersistence {
        var bytes: ByteArray? = null
        var readCalls = 0
        override fun read(): ByteArray? { readCalls += 1; return bytes?.copyOf() }
        override fun write(ciphertext: ByteArray) { bytes = ciphertext.copyOf() }
        override fun clear() { bytes = null }
    }

    private class FlakyPersistence : EncryptedOutboxPersistence {
        private var bytes: ByteArray? = null
        var failNextClear = false
        var failNextWrite = false
        override fun read(): ByteArray? = bytes?.copyOf()
        override fun write(ciphertext: ByteArray) {
            if (failNextWrite) {
                failNextWrite = false
                throw IllegalStateException("write failed")
            }
            bytes = ciphertext.copyOf()
        }
        override fun clear() {
            if (failNextClear) {
                failNextClear = false
                throw IllegalStateException("clear failed")
            }
            bytes = null
        }
    }

    private fun encodedState(state: CallLogSyncState): ByteArray = ByteArrayOutputStream().use { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeUTF("AGENT_LIFE_CALL_SYNC_STATE_V1")
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

    private fun envelopeWithIv(key: SecretKey, iv: ByteArray, plain: ByteArray): ByteArray {
        val ciphertext = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            doFinal(plain)
        }
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                val magic = "AGENT_LIFE_AES_GCM_BLOB_V1".encodeToByteArray()
                output.writeInt(magic.size)
                output.write(magic)
                output.writeInt(iv.size)
                output.write(iv)
                output.writeInt(ciphertext.size)
                output.write(ciphertext)
            }
            bytes.toByteArray()
        }
    }
}
