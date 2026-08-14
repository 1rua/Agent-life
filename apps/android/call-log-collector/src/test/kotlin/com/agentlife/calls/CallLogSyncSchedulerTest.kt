package com.agentlife.calls

import com.agentlife.capability.CallLogSyncInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CallLogSyncSchedulerTest {
    @Test
    fun `manual interval cancels the fixed call log job`() {
        val jobs = RecordingAndroidCallLogJobScheduler()

        AndroidCallLogSyncScheduler(jobs).schedule(CallLogSyncInterval.MANUAL)

        assertEquals(listOf(AndroidCallLogSyncScheduler.JOB_ID), jobs.cancelledJobIds)
        assertTrue(jobs.periodicJobs.isEmpty())
    }

    @Test
    fun `registered intervals schedule only non persisted jobs at exact periods`() {
        val jobs = RecordingAndroidCallLogJobScheduler()
        val scheduler = AndroidCallLogSyncScheduler(jobs)

        scheduler.schedule(CallLogSyncInterval.MINUTES_15)
        scheduler.schedule(CallLogSyncInterval.MINUTES_30)
        scheduler.schedule(CallLogSyncInterval.MINUTES_60)

        assertEquals(
            listOf(
                ScheduledPeriodicCallLogJob(AndroidCallLogSyncScheduler.JOB_ID, 900_000L, persisted = false),
                ScheduledPeriodicCallLogJob(AndroidCallLogSyncScheduler.JOB_ID, 1_800_000L, persisted = false),
                ScheduledPeriodicCallLogJob(AndroidCallLogSyncScheduler.JOB_ID, 3_600_000L, persisted = false),
            ),
            jobs.periodicJobs,
        )
    }

    @Test
    fun `platform scheduling failure surfaces a fixed call log exception`() {
        val jobs = RecordingAndroidCallLogJobScheduler(scheduleSucceeds = false)

        val failure = assertThrows(CallLogJobSchedulingException::class.java) {
            AndroidCallLogSyncScheduler(jobs).schedule(CallLogSyncInterval.MINUTES_15)
        }

        assertEquals("CALL_LOG_PERIODIC_JOB_SCHEDULING_FAILED", failure.message)
    }

    private class RecordingAndroidCallLogJobScheduler(
        private val scheduleSucceeds: Boolean = true,
    ) : AndroidCallLogJobScheduler {
        val periodicJobs = mutableListOf<ScheduledPeriodicCallLogJob>()
        val cancelledJobIds = mutableListOf<Int>()

        override fun schedulePeriodic(job: ScheduledPeriodicCallLogJob): Boolean {
            periodicJobs += job
            return scheduleSucceeds
        }

        override fun cancel(jobId: Int) {
            cancelledJobIds += jobId
        }
    }
}
