package com.agentlife.calls

import com.agentlife.capability.CallCounterpartyAccess
import com.agentlife.capability.CallDirection
import com.agentlife.capability.CallHistoryPolicy
import com.agentlife.capability.CallLogSyncInterval
import com.agentlife.capability.MobileDataCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CallLogSettingsAuthorityTest {
    @Test
    fun `fresh authority is disabled and exposes no grant`() {
        val authority = PersistentCallLogSettingsAuthority(InMemoryCallLogSettingsPersistence())

        val snapshot = authority.snapshot()
        assertEquals(CallLogSettingsPhase.Disabled, snapshot.phase)
        assertEquals(0uL, snapshot.authorizationRevision)
        assertFalse(snapshot.corrupted)
        assertFalse(snapshot.epochExhausted)
        assertNull(authority.capabilityGrant())
    }

    @Test
    fun `disabled authority reaches enabled only through revoking bootstrap`() {
        val authority = PersistentCallLogSettingsAuthority(InMemoryCallLogSettingsPersistence())
        val enabledPolicy = policy(1u, CallCounterpartyAccess.NUMBER)

        authority.beginRevocation(
            targetEpoch = 1u,
            targetPolicyRevision = 1u,
            targetPolicy = enabledPolicy,
            authorizationRevision = 1u,
        )
        assertTrue(authority.snapshot().phase is CallLogSettingsPhase.Revoking)
        assertNull(authority.capabilityGrant())

        authority.commitRevocationTarget()

        assertEquals(CallLogSettingsPhase.Enabled(enabledPolicy), authority.snapshot().phase)
        assertEquals(MobileDataCapability.CALLS, authority.capabilityGrant()!!.capability)
    }

    @Test
    fun `enabled state round trips through persistence`() {
        val persistence = InMemoryCallLogSettingsPersistence()
        val original = PersistentCallLogSettingsAuthority(persistence)
        val enabledPolicy = policy(4u, CallCounterpartyAccess.NUMBER)
        original.beginRevocation(3u, 4u, enabledPolicy, authorizationRevision = 8u)
        original.commitRevocationTarget()

        val restored = PersistentCallLogSettingsAuthority(persistence)

        assertEquals(CallLogSettingsPhase.Enabled(enabledPolicy), restored.snapshot().phase)
        assertEquals(8uL, restored.snapshot().authorizationRevision)
        assertEquals(4uL, restored.capabilityGrant()!!.policyRevision)
    }

    @Test
    fun `policy directions are canonical and snapshots are copy safe`() {
        val persistence = InMemoryCallLogSettingsPersistence()
        val originalDirections = linkedSetOf(CallDirection.REJECTED, CallDirection.INCOMING)
        val configured = CallLogLocalPolicy(
            historyPolicy = CallHistoryPolicy(fromEpochMs = 7L, maxRecords = 9),
            directions = originalDirections,
            counterpartyAccess = CallCounterpartyAccess.WITHHELD,
            syncInterval = CallLogSyncInterval.MINUTES_15,
            onDemandEnabled = true,
            autoSendEnabled = false,
            agentMayRequest = true,
            policyRevision = 1u,
        )
        originalDirections.clear()
        val authority = PersistentCallLogSettingsAuthority(persistence)
        authority.beginRevocation(1u, 1u, configured, authorizationRevision = 1u)
        authority.commitRevocationTarget()

        val first = authority.snapshot()
        val firstPolicy = (first.phase as CallLogSettingsPhase.Enabled).policy
        assertEquals(listOf(CallDirection.INCOMING, CallDirection.REJECTED), firstPolicy.filter().canonicalDirections())
        assertNotSame(first, authority.snapshot())
        assertNotSame(firstPolicy, (authority.snapshot().phase as CallLogSettingsPhase.Enabled).policy)
    }

    @Test
    fun revoking_target_survives_restart_and_never_exposes_a_grant() {
        val persistence = InMemoryCallLogSettingsPersistence()
        val authority = enabledAuthority(persistence)
        authority.beginRevocation(
            targetEpoch = 2u,
            targetPolicyRevision = 8u,
            targetPolicy = policy(8u, CallCounterpartyAccess.NUMBER),
            authorizationRevision = 12u,
        )

        val restored = PersistentCallLogSettingsAuthority(persistence)

        assertTrue(restored.snapshot().phase is CallLogSettingsPhase.Revoking)
        assertNull(restored.capabilityGrant())
    }

    @Test
    fun `revisions must be strictly increasing`() {
        val authority = enabledAuthority(InMemoryCallLogSettingsPersistence())

        assertThrows(IllegalArgumentException::class.java) {
            authority.beginRevocation(
                targetEpoch = 3u,
                targetPolicyRevision = 2u,
                targetPolicy = policy(2u, CallCounterpartyAccess.WITHHELD),
                authorizationRevision = 2u,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            authority.beginRevocation(
                targetEpoch = 3u,
                targetPolicyRevision = 3u,
                targetPolicy = policy(3u, CallCounterpartyAccess.WITHHELD),
                authorizationRevision = 1u,
            )
        }
    }

    @Test
    fun `corrupt truncated unknown and trailing persisted states fail closed`() {
        val source = InMemoryCallLogSettingsPersistence()
        val valid = enabledAuthority(source)
        valid.beginRevocation(2u, 3u, policy(3u, CallCounterpartyAccess.WITHHELD), 3u)
        val encoded = source.read()!!
        val unknownPhase = encoded.copyOf().also { it[CallLogSettingsCodec.phaseOffset] = 99.toByte() }

        listOf(
            byteArrayOf(1, 2, 3),
            encoded.copyOf(encoded.size - 1),
            unknownPhase,
            encoded + byteArrayOf(0),
        ).forEach { malformed ->
            val authority = PersistentCallLogSettingsAuthority(InMemoryCallLogSettingsPersistence(malformed))
            assertTrue(authority.snapshot().corrupted)
            assertEquals(CallLogSettingsPhase.Disabled, authority.snapshot().phase)
            assertNull(authority.capabilityGrant())
        }
    }

    @Test
    fun `epoch exhausted state persists and never exposes a grant`() {
        val persistence = InMemoryCallLogSettingsPersistence()
        val authority = enabledAuthority(persistence)
        authority.beginRevocation(
            targetEpoch = ULong.MAX_VALUE,
            targetPolicyRevision = 3u,
            targetPolicy = policy(3u, CallCounterpartyAccess.WITHHELD),
            authorizationRevision = 2u,
            epochExhausted = true,
        )
        authority.commitRevocationTarget()

        val restored = PersistentCallLogSettingsAuthority(persistence)

        assertTrue(restored.snapshot().epochExhausted)
        assertNull(restored.capabilityGrant())
    }

    @Test
    fun `diagnostics are redacted`() {
        val policy = policy(7u, CallCounterpartyAccess.NUMBER)
        val authority = PersistentCallLogSettingsAuthority(InMemoryCallLogSettingsPersistence())
        authority.beginRevocation(55u, 7u, policy, authorizationRevision = 8u)

        val policyText = policy.toString()
        val snapshotText = authority.snapshot().toString()
        val phaseText = authority.snapshot().phase.toString()

        assertFalse(policyText.contains("fromEpochMs"))
        assertFalse(snapshotText.contains("55"))
        assertFalse(snapshotText.contains("historyPolicy"))
        assertFalse(phaseText.contains("55"))
    }

    @Test
    fun `file persistence uses the no backup settings filename and replaces atomically`() {
        val noBackupFilesDir = Files.createTempDirectory("call-log-settings-test").toFile()
        try {
            val persistence = FileCallLogSettingsPersistence.fromNoBackupFilesDir(noBackupFilesDir)

            persistence.write(byteArrayOf(1, 2, 3))
            persistence.write(byteArrayOf(4, 5, 6))

            assertEquals(
                listOf(4.toByte(), 5.toByte(), 6.toByte()),
                persistence.read()!!.toList(),
            )
            assertTrue(File(noBackupFilesDir, FileCallLogSettingsPersistence.FILE_NAME).isFile)
        } finally {
            noBackupFilesDir.deleteRecursively()
        }
    }

    private fun enabledAuthority(persistence: InMemoryCallLogSettingsPersistence): PersistentCallLogSettingsAuthority {
        return PersistentCallLogSettingsAuthority(persistence).also { authority ->
            authority.beginRevocation(1u, 2u, policy(2u, CallCounterpartyAccess.WITHHELD), 1u)
            authority.commitRevocationTarget()
        }
    }

    private fun policy(
        revision: ULong,
        counterpartyAccess: CallCounterpartyAccess,
    ) = CallLogLocalPolicy(
        historyPolicy = CallHistoryPolicy(fromEpochMs = 100L, maxRecords = 10),
        directions = setOf(CallDirection.OUTGOING, CallDirection.INCOMING),
        counterpartyAccess = counterpartyAccess,
        syncInterval = CallLogSyncInterval.MINUTES_30,
        onDemandEnabled = true,
        autoSendEnabled = false,
        agentMayRequest = true,
        policyRevision = revision,
    )
}
