package com.agentlife.calls

import com.agentlife.capability.CallLogSyncInterval
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
        assertNull(failure.cause)
    }

    @Test
    fun `platform scheduling exception is replaced without leaking detail or cause`() {
        val platformFailure = SecurityException("provider package and platform detail")
        val jobs = RecordingAndroidCallLogJobScheduler(scheduleFailure = platformFailure)

        val failure = assertThrows(CallLogJobSchedulingException::class.java) {
            AndroidCallLogSyncScheduler(jobs).schedule(CallLogSyncInterval.MINUTES_15)
        }

        assertEquals("CALL_LOG_PERIODIC_JOB_SCHEDULING_FAILED", failure.message)
        assertNull(failure.cause)
    }

    @Test
    fun `scheduling cancellation and fatal error propagate unchanged`() {
        val cancellation = CancellationException("cancel schedule")
        val cancelled = assertThrows(CancellationException::class.java) {
            AndroidCallLogSyncScheduler(
                RecordingAndroidCallLogJobScheduler(scheduleFailure = cancellation),
            ).schedule(CallLogSyncInterval.MINUTES_15)
        }
        assertSame(cancellation, cancelled)

        val fatal = AssertionError("fatal schedule")
        val thrownFatal = assertThrows(AssertionError::class.java) {
            AndroidCallLogSyncScheduler(
                RecordingAndroidCallLogJobScheduler(scheduleFailure = fatal),
            ).schedule(CallLogSyncInterval.MINUTES_15)
        }
        assertSame(fatal, thrownFatal)
    }

    @Test
    fun `manual cancellation exception is sanitized while cancellation and fatal error propagate`() {
        val platformFailure = IllegalStateException("scheduler implementation detail")
        val sanitized = assertThrows(CallLogJobSchedulingException::class.java) {
            AndroidCallLogSyncScheduler(
                RecordingAndroidCallLogJobScheduler(cancelFailure = platformFailure),
            ).schedule(CallLogSyncInterval.MANUAL)
        }
        assertEquals("CALL_LOG_PERIODIC_JOB_SCHEDULING_FAILED", sanitized.message)
        assertNull(sanitized.cause)

        val cancellation = CancellationException("cancel cancellation")
        val cancelled = assertThrows(CancellationException::class.java) {
            AndroidCallLogSyncScheduler(
                RecordingAndroidCallLogJobScheduler(cancelFailure = cancellation),
            ).schedule(CallLogSyncInterval.MANUAL)
        }
        assertSame(cancellation, cancelled)

        val fatal = AssertionError("fatal cancellation")
        val thrownFatal = assertThrows(AssertionError::class.java) {
            AndroidCallLogSyncScheduler(
                RecordingAndroidCallLogJobScheduler(cancelFailure = fatal),
            ).schedule(CallLogSyncInterval.MANUAL)
        }
        assertSame(fatal, thrownFatal)
    }

    private class RecordingAndroidCallLogJobScheduler(
        private val scheduleSucceeds: Boolean = true,
        private val scheduleFailure: Throwable? = null,
        private val cancelFailure: Throwable? = null,
    ) : AndroidCallLogJobScheduler {
        val periodicJobs = mutableListOf<ScheduledPeriodicCallLogJob>()
        val cancelledJobIds = mutableListOf<Int>()

        override fun schedulePeriodic(job: ScheduledPeriodicCallLogJob): Boolean {
            scheduleFailure?.let { throw it }
            periodicJobs += job
            return scheduleSucceeds
        }

        override fun cancel(jobId: Int) {
            cancelFailure?.let { throw it }
            cancelledJobIds += jobId
        }
    }
}
