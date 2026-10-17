package dev.escriba.wear.transfer

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dev.escriba.datalayer.AppLog
import dev.escriba.wear.EscribaWearApp
import java.util.concurrent.TimeUnit

/**
 * Runs the [Uploader] in the background. WorkManager survives the system killing the app: if
 * something was left to send, it's retried on the next cycle (every 15 minutes).
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as EscribaWearApp
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        val outcome = runCatching { app.uploader.run(manual) }
            .onFailure { AppLog.e("upload", "The upload failed", it) }
            .getOrNull()
        if (outcome != null && outcome != Uploader.Outcome.NOTHING_TO_DO && outcome != Uploader.Outcome.WAITING_FOR_BATCH) {
            AppLog.i("upload", "Upload cycle: ${outcome.name.lowercase()}")
        }
        return Result.success()
    }

    companion object {
        private const val KEY_MANUAL = "manual"
        private const val PERIODIC = "upload-periodic"
        private const val NOW = "upload-now"

        /** Periodic check: picks up whatever was left unsent (no phone nearby, app killed…). */
        fun ensurePeriodic(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<UploadWorker>(15, TimeUnit.MINUTES).build(),
            )
        }

        /** Immediate attempt: after closing a fragment or a session, or when the user asks. */
        fun kick(context: Context, manual: Boolean = false) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                NOW,
                if (manual) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<UploadWorker>().setInputData(workDataOf(KEY_MANUAL to manual)).build(),
            )
        }
    }
}
