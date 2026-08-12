package com.agentlife.sms

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import com.agentlife.capability.AgentDataRequest
import com.agentlife.capability.AgentRequestAuthorization
import com.agentlife.capability.AgentRequestAuthorizer
import com.agentlife.capability.AuthorizedAutoSendSubscription
import com.agentlife.capability.CapabilityAvailability
import com.agentlife.capability.CapabilityFilter
import com.agentlife.capability.DataSyncMode
import com.agentlife.capability.DefaultAgentRequestAuthorizer
import com.agentlife.capability.MobileDataCapability
import com.agentlife.capability.SmsSyncInterval

/** Platform-neutral schedule control owned by the local settings composition. */
interface SmsJobScheduler {
    fun schedule(interval: SmsSyncInterval)
    fun cancel()
}

/** Android framework boundary kept small enough for host-side scheduler tests. */
internal interface AndroidSmsJobScheduler {
    fun schedulePersistedPeriodic(jobId: Int, periodMs: Long)
    fun cancel(jobId: Int)
}

internal data class ScheduledPeriodicSmsJob(
    val jobId: Int,
    val periodMs: Long,
    val persisted: Boolean,
)

/** Android [JobScheduler] adapter; it creates only the SMS periodic job. */
class AndroidSmsSyncScheduler private constructor(
    private val jobs: AndroidSmsJobScheduler,
) : SmsJobScheduler {
    constructor(context: Context) : this(AndroidSmsJobSchedulerAdapter(context.applicationContext))

    internal constructor(jobs: AndroidSmsJobScheduler, @Suppress("UNUSED_PARAMETER") testOnly: Unit = Unit) : this(jobs)

    override fun schedule(interval: SmsSyncInterval) {
        val periodMs = interval.periodMs
        if (periodMs == null) {
            cancel()
        } else {
            jobs.schedulePersistedPeriodic(JOB_ID, periodMs)
        }
    }

    override fun cancel() {
        jobs.cancel(JOB_ID)
    }

    companion object {
        const val JOB_ID = 0x534D53
    }
}

private class AndroidSmsJobSchedulerAdapter(context: Context) : AndroidSmsJobScheduler {
    private val scheduler = checkNotNull(context.getSystemService(JobScheduler::class.java)) {
        "JobScheduler is unavailable"
    }
    private val service = ComponentName(context, SmsSyncJobService::class.java)

    override fun schedulePersistedPeriodic(jobId: Int, periodMs: Long) {
        scheduler.schedule(
            JobInfo.Builder(jobId, service)
                .setPeriodic(periodMs)
                .setPersisted(true)
                .build(),
        )
    }

    override fun cancel(jobId: Int) {
        scheduler.cancel(jobId)
    }
}

fun interface SmsAutoSyncRunner {
    suspend fun runOnce(subscription: AuthorizedAutoSendSubscription): SmsSyncRunResult
}

data class SmsScheduledRunResult(
    val ran: Boolean,
    val retryPending: Boolean,
)

/**
 * Service-facing runtime. Every check happens before a coordinator batch can
 * reach the SMS provider, and the only subscription is minted from local state.
 */
class SmsRuntime(
    private val settingsAuthority: PersistentSmsSettingsAuthority,
    private val autoSyncRunner: SmsAutoSyncRunner?,
    private val authorizer: AgentRequestAuthorizer = DefaultAgentRequestAuthorizer(),
) {
    suspend fun runScheduled(readSmsPermissionGranted: Boolean): SmsScheduledRunResult {
        val snapshot = settingsAuthority.snapshot()
        if (!readSmsPermissionGranted || snapshot.corrupted || !snapshot.granted ||
            snapshot.syncInterval == SmsSyncInterval.MANUAL
        ) {
            return SmsScheduledRunResult(ran = false, retryPending = false)
        }

        val subscription = authorizedAutoSendSubscription(snapshot.policyRevision) ?: return SmsScheduledRunResult(
            ran = false,
            retryPending = false,
        )
        val runner = autoSyncRunner ?: return SmsScheduledRunResult(ran = false, retryPending = true)

        return try {
            val result = runner.runOnce(subscription)
            SmsScheduledRunResult(ran = true, retryPending = result.failure != null || result.retained > 0)
        } catch (_: Throwable) {
            SmsScheduledRunResult(ran = true, retryPending = true)
        }
    }

    private fun authorizedAutoSendSubscription(policyRevision: ULong): AuthorizedAutoSendSubscription? {
        val authorization = authorizer.authorize(
            request = AgentDataRequest(
                requestId = "local-sms-auto-sync",
                capability = MobileDataCapability.SMS,
                mode = DataSyncMode.AUTO_SEND,
                filter = CapabilityFilter.Sms,
                policyRevision = policyRevision,
            ),
            grant = settingsAuthority.capabilityGrant(),
            availability = CapabilityAvailability.READY,
        )
        return (authorization as? AgentRequestAuthorization.Allowed)
            ?.access as? AuthorizedAutoSendSubscription
    }

    companion object {
        fun denyFirst(): SmsRuntime = SmsRuntime(
            settingsAuthority = PersistentSmsSettingsAuthority(InMemorySmsSettingsPersistence()),
            autoSyncRunner = null,
        )
    }
}

fun interface SmsRuntimeFactory {
    fun create(context: Context): SmsRuntime
}

/** JobService composition registry. An unconfigured or failed install cannot query SMS. */
object SmsRuntimeFactoryRegistry {
    private val denyFirstFactory = SmsRuntimeFactory { SmsRuntime.denyFirst() }

    @Volatile
    private var factory: SmsRuntimeFactory = denyFirstFactory

    fun install(value: SmsRuntimeFactory) {
        factory = value
    }

    fun create(context: Context): SmsRuntime = factory.create(context.applicationContext)

    fun reset() {
        factory = denyFirstFactory
    }
}
