package com.agentlife.calls

import android.annotation.SuppressLint
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import com.agentlife.capability.AuthorizedAutoSendSubscription
import com.agentlife.capability.CallLogSyncInterval
import com.agentlife.capability.CapabilityAvailability
import com.agentlife.capability.LocalCallLogAutoSendAuthorizer
import com.agentlife.capability.LocalCallLogAutoSendRequest
import com.agentlife.capability.LocalCallLogAutoSendState
import kotlinx.coroutines.CancellationException

/** Platform-neutral schedule control owned only by local settings composition. */
interface CallLogJobScheduler {
    fun schedule(interval: CallLogSyncInterval)
    fun cancel()
}

/** Small Android boundary that keeps JobInfo construction host-testable. */
internal interface AndroidCallLogJobScheduler {
    fun schedulePeriodic(job: ScheduledPeriodicCallLogJob): Boolean
    fun cancel(jobId: Int)
}

internal data class ScheduledPeriodicCallLogJob(
    val jobId: Int,
    val periodMs: Long,
    val persisted: Boolean,
)

class CallLogJobSchedulingException :
    IllegalStateException("CALL_LOG_PERIODIC_JOB_SCHEDULING_FAILED")

/** Creates only the fixed, non-persisted low-frequency call-log job. */
class AndroidCallLogSyncScheduler private constructor(
    private val jobs: AndroidCallLogJobScheduler,
) : CallLogJobScheduler {
    constructor(context: Context) : this(AndroidCallLogJobSchedulerAdapter(context.applicationContext))

    internal constructor(
        jobs: AndroidCallLogJobScheduler,
        @Suppress("UNUSED_PARAMETER") testOnly: Unit = Unit,
    ) : this(jobs)

    override fun schedule(interval: CallLogSyncInterval) {
        val periodMs = interval.periodMs
        if (periodMs == null) {
            cancel()
            return
        }
        val scheduled = try {
            jobs.schedulePeriodic(
                ScheduledPeriodicCallLogJob(JOB_ID, periodMs, persisted = false),
            )
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            throw CallLogJobSchedulingException()
        }
        if (!scheduled) throw CallLogJobSchedulingException()
    }

    override fun cancel() {
        try {
            jobs.cancel(JOB_ID)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            throw CallLogJobSchedulingException()
        }
    }

    companion object {
        const val JOB_ID = 0x43414C
    }
}

private class AndroidCallLogJobSchedulerAdapter(context: Context) : AndroidCallLogJobScheduler {
    private val scheduler = checkNotNull(context.getSystemService(JobScheduler::class.java)) {
        "JobScheduler is unavailable"
    }
    private val service = ComponentName(context, CallLogSyncJobService::class.java)

    @SuppressLint("MissingPermission")
    override fun schedulePeriodic(job: ScheduledPeriodicCallLogJob): Boolean {
        check(!job.persisted) { "call-log jobs must not persist across reboot" }
        return scheduler.schedule(
            JobInfo.Builder(job.jobId, service)
                .setPeriodic(job.periodMs)
                .setPersisted(false)
                .build(),
        ) == JobScheduler.RESULT_SUCCESS
    }

    override fun cancel(jobId: Int) {
        scheduler.cancel(jobId)
    }
}

fun interface CallLogAutoSyncRunner {
    suspend fun runOnce(subscription: AuthorizedAutoSendSubscription): CallLogSyncRunResult
}

data class CallLogScheduledRunResult(
    val ran: Boolean,
    val retryPending: Boolean,
)

/** Deny-first runtime that revalidates every local input for every invocation. */
class CallLogRuntime(
    private val settings: PersistentCallLogSettingsAuthority,
    private val availability: CallLogAvailabilitySource,
    private val state: CallLogSyncStateStore,
    private val runner: CallLogAutoSyncRunner?,
    private val localAuthorizer: LocalCallLogAutoSendAuthorizer =
        LocalCallLogAutoSendAuthorizer(),
) {
    suspend fun runScheduled(): CallLogScheduledRunResult {
        val subscription = currentSubscription()
            ?: return CallLogScheduledRunResult(ran = false, retryPending = false)
        val currentRunner = runner
            ?: return CallLogScheduledRunResult(ran = false, retryPending = true)
        return try {
            val result = currentRunner.runOnce(subscription)
            CallLogScheduledRunResult(
                ran = true,
                retryPending = result.retained > 0 || result.failure != null,
            )
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            CallLogScheduledRunResult(ran = true, retryPending = true)
        }
    }

    /** Recomputes stop retry from current authority; no prior subscription is reused. */
    fun retryAfterStop(): Boolean {
        val subscription = currentSubscription()
        return runner != null && subscription != null
    }

    private fun currentSubscription(): AuthorizedAutoSendSubscription? {
        var settingsFailed = false
        val snapshot = try {
            settings.snapshot()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            settingsFailed = true
            null
        }
        var availabilityFailed = false
        val currentAvailability = try {
            availability.current()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            availabilityFailed = true
            null
        }
        var stateFailed = false
        val currentState = try {
            state.snapshot()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            stateFailed = true
            null
        }
        if (settingsFailed || availabilityFailed || stateFailed) return null

        val currentSettings = snapshot ?: return null
        if (currentSettings.corrupted || currentSettings.epochExhausted) return null
        val policy = (currentSettings.phase as? CallLogSettingsPhase.Enabled)?.policy ?: return null
        if (
            policy.syncInterval == CallLogSyncInterval.MANUAL ||
            !policy.autoSendEnabled ||
            currentAvailability != CapabilityAvailability.READY ||
            currentState == null ||
            currentState.policyRevision != policy.policyRevision
        ) {
            return null
        }

        val confirmed = try {
            settings.snapshot()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            return null
        }
        if (confirmed != currentSettings) return null
        val grant = try {
            settings.capabilityGrant()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            return null
        }
        return localAuthorizer.authorize(
            request = LocalCallLogAutoSendRequest(
                policyRevision = policy.policyRevision,
                authorizationRevision = currentSettings.authorizationRevision,
            ),
            localState = LocalCallLogAutoSendState(
                grant = grant,
                authorizationRevision = confirmed.authorizationRevision,
            ),
            availability = currentAvailability,
        )
    }

    companion object {
        fun denyFirst(): CallLogRuntime = CallLogRuntime(
            settings = PersistentCallLogSettingsAuthority(InMemoryCallLogSettingsPersistence()),
            availability = CallLogAvailabilitySource { CapabilityAvailability.DISABLED },
            state = DenyFirstCallLogSyncStateStore,
            runner = null,
        )
    }
}

private object DenyFirstCallLogSyncStateStore : CallLogSyncStateStore {
    override fun snapshot(): CallLogSyncState? = null
    override fun initialize(state: CallLogSyncState) = Unit
    override fun advance(expectedSourceEpoch: ULong, cursor: CallLogCursor, policyRevision: ULong): Boolean = false
    override fun resetWithRotatedKey(state: CallLogSyncState) = Unit
}

fun interface CallLogRuntimeFactory {
    fun create(context: Context): CallLogRuntime
}

/** An uninstalled or reset service cannot reach the call-log provider. */
object CallLogRuntimeFactoryRegistry {
    private val denyFirstFactory = CallLogRuntimeFactory { CallLogRuntime.denyFirst() }

    @Volatile
    private var factory: CallLogRuntimeFactory = denyFirstFactory

    fun install(value: CallLogRuntimeFactory) {
        factory = value
    }

    fun create(context: Context): CallLogRuntime = factory.create(context.applicationContext)

    fun reset() {
        factory = denyFirstFactory
    }
}
