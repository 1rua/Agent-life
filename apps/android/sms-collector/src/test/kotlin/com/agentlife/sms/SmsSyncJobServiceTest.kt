package com.agentlife.sms

import com.agentlife.capability.SmsHistoryPolicy
import com.agentlife.capability.SmsSyncInterval
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SmsSyncJobServiceTest {
    @Test
    fun `disabled settings do not start an SMS provider batch`() = runBlocking {
        val batches = RecordingBatchRunner()
        val runtime = SmsRuntime(PersistentSmsSettingsAuthority(InMemorySmsSettingsPersistence()), batches)

        val result = runtime.runScheduled(readSmsPermissionGranted = true)

        assertFalse(result.ran)
        assertFalse(result.retryPending)
        assertEquals(0, batches.calls)
    }

    @Test
    fun `corrupted settings do not start an SMS provider batch`() = runBlocking {
        val batches = RecordingBatchRunner()
        val runtime = SmsRuntime(
            PersistentSmsSettingsAuthority(InMemorySmsSettingsPersistence(byteArrayOf(1, 2, 3))),
            batches,
        )

        val result = runtime.runScheduled(readSmsPermissionGranted = true)

        assertFalse(result.ran)
        assertFalse(result.retryPending)
        assertEquals(0, batches.calls)
    }

    @Test
    fun `missing READ_SMS permission does not start an enabled SMS provider batch`() = runBlocking {
        val authority = PersistentSmsSettingsAuthority(InMemorySmsSettingsPersistence()).also { settings ->
            settings.localController().update(
                historyPolicy = SmsHistoryPolicy(fromEpochMs = 10L, maxRecords = 5),
                syncInterval = SmsSyncInterval.MINUTES_15,
                granted = true,
                onDemandEnabled = true,
                autoSendEnabled = true,
                agentMayRequest = true,
            )
        }
        val batches = RecordingBatchRunner()

        val result = SmsRuntime(authority, batches).runScheduled(readSmsPermissionGranted = false)

        assertFalse(result.ran)
        assertFalse(result.retryPending)
        assertEquals(0, batches.calls)
    }

    private class RecordingBatchRunner : SmsAutoSyncRunner {
        var calls = 0

        override suspend fun runOnce(subscription: com.agentlife.capability.AuthorizedAutoSendSubscription): SmsSyncRunResult {
            calls += 1
            return SmsSyncRunResult(captured = 0, enqueued = 0, acknowledged = 0, retained = 0, failure = null)
        }
    }
}
