package com.openandroidintelligence.sms

import com.openandroidintelligence.capability.SmsHistoryPolicy
import com.openandroidintelligence.capability.SmsSyncInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsSettingsAuthorityTest {
    @Test
    fun `fresh install denies all SMS access`() {
        val snapshot = PersistentSmsSettingsAuthority(InMemorySmsSettingsPersistence()).snapshot()

        assertFalse(snapshot.granted)
        assertFalse(snapshot.onDemandEnabled)
        assertFalse(snapshot.autoSendEnabled)
        assertFalse(snapshot.agentMayRequest)
        assertFalse(snapshot.corrupted)
    }

    @Test
    fun `local update restores from persisted bytes after restart`() {
        val persistence = InMemorySmsSettingsPersistence()
        PersistentSmsSettingsAuthority(persistence).localController().update(
            historyPolicy = SmsHistoryPolicy(fromEpochMs = 100L, maxRecords = 5),
            syncInterval = SmsSyncInterval.MINUTES_15,
            granted = true,
            onDemandEnabled = true,
            autoSendEnabled = true,
            agentMayRequest = true,
        )

        val restored = PersistentSmsSettingsAuthority(persistence).snapshot()

        assertEquals(SmsHistoryPolicy(fromEpochMs = 100L, maxRecords = 5), restored.historyPolicy)
        assertEquals(SmsSyncInterval.MINUTES_15, restored.syncInterval)
        assertTrue(restored.granted)
        assertTrue(restored.onDemandEnabled)
        assertTrue(restored.autoSendEnabled)
        assertTrue(restored.agentMayRequest)
    }

    @Test
    fun `updates advance policy and authorization revisions`() {
        val authority = PersistentSmsSettingsAuthority(InMemorySmsSettingsPersistence())
        val controller = authority.localController()
        val before = authority.snapshot()

        controller.update(
            historyPolicy = SmsHistoryPolicy(fromEpochMs = 100L, maxRecords = 5),
            syncInterval = SmsSyncInterval.MANUAL,
            granted = true,
            onDemandEnabled = true,
            autoSendEnabled = false,
            agentMayRequest = false,
        )

        val after = authority.snapshot()
        assertTrue(after.policyRevision > before.policyRevision)
        assertTrue(after.authorizationRevision > before.authorizationRevision)
    }

    @Test
    fun `same revision mutation fails`() {
        val authority = PersistentSmsSettingsAuthority(InMemorySmsSettingsPersistence())

        assertThrows(IllegalArgumentException::class.java) {
            authority.localController().update(
                historyPolicy = SmsHistoryPolicy(fromEpochMs = 1L, maxRecords = 5),
                syncInterval = SmsSyncInterval.MANUAL,
                granted = true,
                onDemandEnabled = true,
                autoSendEnabled = false,
                agentMayRequest = false,
                policyRevision = 0u,
                authorizationRevision = 0u,
            )
        }
    }

    @Test
    fun `malformed settings bytes fail closed without overwriting evidence`() {
        val persistence = InMemorySmsSettingsPersistence(byteArrayOf(1, 2, 3))
        val authority = PersistentSmsSettingsAuthority(persistence)

        assertTrue(authority.snapshot().corrupted)
        assertNull(authority.capabilityGrant())
        assertEquals(byteArrayOf(1, 2, 3).toList(), persistence.read()!!.toList())
        assertThrows(IllegalStateException::class.java) {
            authority.localController().update(
                historyPolicy = SmsHistoryPolicy(fromEpochMs = 1L, maxRecords = 5),
                syncInterval = SmsSyncInterval.MANUAL,
                granted = true,
                onDemandEnabled = true,
                autoSendEnabled = false,
                agentMayRequest = false,
            )
        }
        assertEquals(byteArrayOf(1, 2, 3).toList(), persistence.read()!!.toList())
    }

    @Test
    fun `first enable defaults use a ninety day local history window`() {
        val defaults = SmsSettingsDefaults.firstEnable(nowEpochMs = 10_000_000_000L)

        assertEquals(10_000_000_000L - 90L * 24 * 60 * 60 * 1000, defaults.historyPolicy.fromEpochMs)
        assertEquals(500, defaults.historyPolicy.maxRecords)
        assertEquals(SmsSyncInterval.MINUTES_30, defaults.syncInterval)
    }
}
