package com.agentlife.sms

import com.agentlife.capability.SmsSyncInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsSyncSchedulerTest {
    @Test
    fun `manual interval cancels the fixed scheduled job`() {
        val jobs = RecordingAndroidSmsJobScheduler()

        AndroidSmsSyncScheduler(jobs).schedule(SmsSyncInterval.MANUAL)

        assertEquals(listOf(AndroidSmsSyncScheduler.JOB_ID), jobs.cancelledJobIds)
        assertTrue(jobs.periodicJobs.isEmpty())
    }

    @Test
    fun `periodic intervals schedule jobs at the exact allowed periods`() {
        val jobs = RecordingAndroidSmsJobScheduler()
        val scheduler = AndroidSmsSyncScheduler(jobs)

        scheduler.schedule(SmsSyncInterval.MINUTES_15)
        scheduler.schedule(SmsSyncInterval.MINUTES_30)
        scheduler.schedule(SmsSyncInterval.MINUTES_60)

        assertEquals(
            listOf(
                ScheduledPeriodicSmsJob(AndroidSmsSyncScheduler.JOB_ID, 15 * 60 * 1000L, persisted = false),
                ScheduledPeriodicSmsJob(AndroidSmsSyncScheduler.JOB_ID, 30 * 60 * 1000L, persisted = false),
                ScheduledPeriodicSmsJob(AndroidSmsSyncScheduler.JOB_ID, 60 * 60 * 1000L, persisted = false),
            ),
            jobs.periodicJobs,
        )
    }

    @Test
    fun `periodic schedule failure is surfaced to the local settings caller`() {
        val jobs = RecordingAndroidSmsJobScheduler(scheduleSucceeds = false)

        assertThrows(SmsJobSchedulingException::class.java) {
            AndroidSmsSyncScheduler(jobs).schedule(SmsSyncInterval.MINUTES_15)
        }
    }

    private class RecordingAndroidSmsJobScheduler(
        private val scheduleSucceeds: Boolean = true,
    ) : AndroidSmsJobScheduler {
        val periodicJobs = mutableListOf<ScheduledPeriodicSmsJob>()
        val cancelledJobIds = mutableListOf<Int>()

        override fun schedulePersistedPeriodic(jobId: Int, periodMs: Long): Boolean {
            periodicJobs += ScheduledPeriodicSmsJob(jobId, periodMs, persisted = false)
            return scheduleSucceeds
        }

        override fun cancel(jobId: Int) {
            cancelledJobIds += jobId
        }
    }
}