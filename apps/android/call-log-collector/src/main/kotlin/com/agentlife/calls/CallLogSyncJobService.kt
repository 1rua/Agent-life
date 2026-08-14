package com.agentlife.calls

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Intent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** One invocation whose stop transition atomically suppresses late completion. */
internal class CallLogJobExecution(
    private val finish: (retryPending: Boolean) -> Unit,
) {
    private val lock = Any()
    private var active = true

    fun stop() = synchronized(lock) {
        active = false
    }

    suspend fun run(work: suspend () -> Boolean) {
        try {
            completeIfActive(work())
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            completeIfActive(retryPending = true)
        }
    }

    private fun completeIfActive(retryPending: Boolean) = synchronized(lock) {
        if (!active) return@synchronized
        active = false
        finish(retryPending)
    }
}

/** Maps only recoverable stop-retry lookup failures to the deny-first result. */
internal fun retryCallLogJobAfterStop(
    runtimeFactory: () -> CallLogRuntime,
): Boolean = try {
    runtimeFactory().retryAfterStop()
} catch (failure: CancellationException) {
    throw failure
} catch (_: Exception) {
    false
}

/** Runs one freshly authorized call-log batch for the periodic local job. */
class CallLogSyncJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var runningJob: Job? = null
    private var runningExecution: CallLogJobExecution? = null

    override fun onStartJob(params: JobParameters): Boolean {
        runningExecution?.stop()
        runningJob?.cancel()
        val execution = CallLogJobExecution { retryPending -> jobFinished(params, retryPending) }
        runningExecution = execution
        runningJob = scope.launch {
            execution.run {
                CallLogRuntimeFactoryRegistry.create(applicationContext)
                    .runScheduled()
                    .retryPending
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        runningExecution?.stop()
        runningExecution = null
        runningJob?.cancel()
        runningJob = null
        return retryCallLogJobAfterStop {
            CallLogRuntimeFactoryRegistry.create(applicationContext)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        runningExecution?.stop()
        runningExecution = null
        scope.cancel()
        super.onDestroy()
    }
}
