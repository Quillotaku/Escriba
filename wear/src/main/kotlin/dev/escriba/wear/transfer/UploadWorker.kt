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
 * Ejecuta el [Uploader] en segundo plano. WorkManager sobrevive a que el sistema mate la
 * app: si quedó algo por enviar, se reintenta en el siguiente ciclo (cada 15 minutos).
 */
class UploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as EscribaWearApp
        val manual = inputData.getBoolean(KEY_MANUAL, false)
        val outcome = runCatching { app.uploader.run(manual) }
            .onFailure { AppLog.e("envio", "El envío falló", it) }
            .getOrNull()
        if (outcome != null && outcome != Uploader.Outcome.NOTHING_TO_DO && outcome != Uploader.Outcome.WAITING_FOR_BATCH) {
            AppLog.i("envio", "Ciclo de envío: ${outcome.name.lowercase()}")
        }
        return Result.success()
    }

    companion object {
        private const val KEY_MANUAL = "manual"
        private const val PERIODIC = "envio-periodico"
        private const val NOW = "envio-ahora"

        /** Revisión periódica: recoge lo que se quedó sin enviar (sin móvil cerca, app muerta…). */
        fun ensurePeriodic(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<UploadWorker>(15, TimeUnit.MINUTES).build(),
            )
        }

        /** Intento inmediato: tras cerrar un fragmento o una sesión, o a petición del usuario. */
        fun kick(context: Context, manual: Boolean = false) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                NOW,
                if (manual) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<UploadWorker>().setInputData(workDataOf(KEY_MANUAL to manual)).build(),
            )
        }
    }
}
