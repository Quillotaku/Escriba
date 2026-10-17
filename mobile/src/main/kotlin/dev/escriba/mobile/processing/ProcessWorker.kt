package dev.escriba.mobile.processing

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.escriba.datalayer.AppLog
import dev.escriba.mobile.EscribaApp
import dev.escriba.mobile.R
import java.util.concurrent.TimeUnit

/**
 * Processes a session in the background (transcription + notes).
 *
 * Transcribing a long session takes many minutes, more than the 10 WorkManager grants a normal
 * job: it runs as a `mediaProcessing` foreground service, with a notification saying what it's
 * doing.
 */
class ProcessWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as EscribaApp
        val sessionId = inputData.getString(KEY_SESSION) ?: return Result.failure()
        val profile = inputData.getString(KEY_PROFILE)

        promote(sessionId, getString(R.string.step_preparing))
        return runCatching {
            app.processor.process(sessionId, profile) { step ->
                when (step) {
                    is SessionProcessor.Step.Transcribing -> promote(sessionId, getString(R.string.step_transcribing, step.speechSeconds / 60, step.speechSeconds % 60))
                    is SessionProcessor.Step.Diarizing -> promote(sessionId, getString(R.string.step_diarizing, step.speechSeconds / 60))
                    is SessionProcessor.Step.Summarizing -> promote(sessionId, getString(R.string.step_summarizing, step.model))
                    SessionProcessor.Step.Writing -> promote(sessionId, getString(R.string.step_writing))
                }
            }
        }.also { ProcessingStatus.clear(sessionId) }.fold(
            onSuccess = { Result.success() },
            onFailure = {
                AppLog.e(TAG, "Couldn't process session ${sessionId.take(8)}", it)
                if (runAttemptCount < 5) Result.retry() else Result.failure()
            },
        )
    }

    private fun getString(id: Int, vararg args: Any): String = applicationContext.getString(id, *args)

    private suspend fun promote(sessionId: String, text: String) {
        ProcessingStatus.set(sessionId, text)
        runCatching { setForeground(foregroundInfo(text)) }
            // It may fail if Android doesn't allow starting a service from the background (Escriba
            // must be exempt from battery optimization). The work goes on, but with the 10 min limit.
            .onFailure { AppLog.e(TAG, "Couldn't move to the foreground", it) }
    }

    private fun foregroundInfo(text: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.channel_processing), NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
    }

    companion object {
        private const val TAG = "processing"
        private const val KEY_SESSION = "session"
        private const val KEY_PROFILE = "profile"
        private const val CHANNEL = "processing"
        private const val NOTIFICATION_ID = 10

        /** Sessions with more speech than this wait until the phone is charging. */
        const val CHARGING_THRESHOLD_MS = 10 * 60_000L

        /**
         * [speechMs] decides whether it waits for the charger. [now] forces immediate processing (app button).
         */
        fun enqueue(context: Context, sessionId: String, profileOverride: String? = null, speechMs: Long = 0, now: Boolean = false) {
            val waitForCharger = !now && speechMs > CHARGING_THRESHOLD_MS
            val request = OneTimeWorkRequestBuilder<ProcessWorker>()
                .setInputData(workDataOf(KEY_SESSION to sessionId, KEY_PROFILE to profileOverride))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiresCharging(waitForCharger).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("process-$sessionId", ExistingWorkPolicy.REPLACE, request)
            if (waitForCharger) AppLog.i(TAG, "Long session ${sessionId.take(8)} (${speechMs / 60_000} min of speech): it'll be processed while charging")
        }
    }
}
