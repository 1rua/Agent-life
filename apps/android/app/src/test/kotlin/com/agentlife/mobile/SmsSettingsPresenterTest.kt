package com.agentlife.mobile

import com.agentlife.capability.CapabilityAvailability
import com.agentlife.capability.SmsHistoryPolicy
import com.agentlife.capability.SmsSyncInterval
import com.agentlife.sms.InMemorySmsSettingsPersistence
import com.agentlife.sms.PersistentSmsSettingsAuthority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsSettingsPresenterTest {
    @Test
    fun `view state exposes every local SMS consent control without message content`() {
        val authority = PersistentSmsSettingsAuthority(InMemorySmsSettingsPersistence()).also { settings ->
            settings.localController().update(
                historyPolicy = SmsHistoryPolicy(fromEpochMs = 123L, maxRecords = 42),
                syncInterval = SmsSyncInterval.MINUTES_60,
                granted = true,
                onDemandEnabled = true,
                autoSendEnabled = true,
                agentMayRequest = false,
            )
        }

        val state = SmsSettingsPresenter(
            snapshotSource = authority::snapshot,
            permissionAvailability = { CapabilityAvailability.PERMISSION_REQUIRED },
        ).state()

        assertTrue(state.granted)
        assertEquals(CapabilityAvailability.PERMISSION_REQUIRED, state.permissionStatus)
        assertEquals(SmsHistoryStartMode.FROM_EPOCH, state.historyStartMode)
        assertEquals(42, state.maxRecords)
        assertEquals(SmsSyncInterval.MINUTES_60, state.syncInterval)
        assertTrue(state.onDemandEnabled)
        assertTrue(state.autoSendEnabled)
        assertFalse(state.agentMayRequest)
    }

    @Test
    fun `presenting SMS settings cannot mutate the local authority for a remote caller`() {
        val authority = PersistentSmsSettingsAuthority(InMemorySmsSettingsPersistence())
        val before = authority.snapshot()
        val presenter = SmsSettingsPresenter(authority::snapshot) { CapabilityAvailability.DISABLED }

        presenter.state()

        assertEquals(before, authority.snapshot())
    }
}
