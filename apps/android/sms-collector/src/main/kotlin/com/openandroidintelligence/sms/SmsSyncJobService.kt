package com.openandroidintelligence.sms

import android.Manifest
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** One JobService invocation; stopping it atomically suppresses completion. */
internal class SmsJobExecution(
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
        } catch (failure: kotlinx.coroutines.CancellationException) {
            throw failure
        } catch (_: Throwable) {
            completeIfActive(retryPending = true)
        }
    }

    private fun completeIfActive(retryPending: Boolean) = synchronized(lock) {
        if (!active) return@synchronized
        active = false
        finish(retryPending)
    }
}

/** Runs one locally authorized SMS auto-send batch for the periodic job. */
class SmsSyncJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var runningJob: Job? = null
    private var runningExecution: SmsJobExecution? = null

    override fun onStartJob(params: JobParameters): Boolean {
        runningExecution?.stop()
        runningJob?.cancel()
        val execution = SmsJobExecution { retryPending -> jobFinished(params, retryPending) }
        runningExecution = execution
        runningJob = scope.launch {
            execution.run {
                SmsRuntimeFactoryRegistry.create(applicationContext).runScheduled(
                    readSmsPermissionGranted = checkSelfPermission(Manifest.permission.READ_SMS) ==
                        PackageManager.PERMISSION_GRANTED,
                ).retryPending
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        runningExecution?.stop()
        runningExecution = null
        runningJob?.cancel()
        runningJob = null
        return true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        runningExecution?.stop()
        runningExecution = null
        scope.cancel()
        super.onDestroy()
    }
}