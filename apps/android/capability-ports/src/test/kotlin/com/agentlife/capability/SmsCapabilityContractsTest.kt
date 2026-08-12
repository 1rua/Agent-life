package com.agentlife.capability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SmsCapabilityContractsTest {
    @Test
    fun sms_history_policy_rejects_negative_start_zero_limit_and_over_limit() {
        assertThrows(IllegalArgumentException::class.java) { SmsHistoryPolicy(fromEpochMs = -1, maxRecords = 1) }
        assertThrows(IllegalArgumentException::class.java) { SmsHistoryPolicy(fromEpochMs = null, maxRecords = 0) }
        assertThrows(IllegalArgumentException::class.java) { SmsHistoryPolicy(fromEpochMs = null, maxRecords = 10_001) }
    }

    @Test
    fun sms_interval_exposes_only_manual_15_30_and_60_minutes() {
        assertEquals(null, SmsSyncInterval.MANUAL.periodMs)
        assertEquals(15 * 60 * 1000L, SmsSyncInterval.MINUTES_15.periodMs)
        assertEquals(30 * 60 * 1000L, SmsSyncInterval.MINUTES_30.periodMs)
        assertEquals(60 * 60 * 1000L, SmsSyncInterval.MINUTES_60.periodMs)
    }

    @Test
    fun sms_metadata_rejects_negative_message_or_observed_time() {
        assertThrows(IllegalArgumentException::class.java) {
            SmsMetadata("record", senderAddress = null, threadId = null, messageAtEpochMs = -1, observedAtEpochMs = 0, read = false, subscriptionId = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SmsMetadata("record", senderAddress = null, threadId = null, messageAtEpochMs = 0, observedAtEpochMs = -1, read = false, subscriptionId = null)
        }
    }
}
