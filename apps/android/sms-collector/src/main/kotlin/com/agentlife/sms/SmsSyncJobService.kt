package com.agentlife.sms

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

/** Runs one locally authorized SMS auto-send batch for the persisted periodic job. */
class SmsSyncJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var runningJob: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        runningJob?.cancel()
        runningJob = scope.launch {
            val retryPending = try {
                SmsRuntimeFactoryRegistry.create(applicationContext).runScheduled(
                    readSmsPermissionGranted = checkSelfPermission(Manifest.permission.READ_SMS) ==
                        PackageManager.PERMISSION_GRANTED,
                ).retryPending
            } catch (_: Throwable) {
                true
            }
            jobFinished(params, retryPending)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        runningJob?.cancel()
        runningJob = null
        return true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
