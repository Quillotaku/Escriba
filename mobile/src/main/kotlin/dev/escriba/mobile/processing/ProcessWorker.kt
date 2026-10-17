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
 * Procesa una sesión en segundo plano (transcripción + notas).
 *
 * La transcripción de una sesión larga dura muchos minutos, más que los 10 que WorkManager
 * concede a un trabajo normal: se ejecuta como servicio en primer plano de tipo
 * `mediaProcessing`, con una notificación que dice qué está haciendo.
 */
class ProcessWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as EscribaApp
        val sessionId = inputData.getString(KEY_SESSION) ?: return Result.failure()
        val profile = inputData.getString(KEY_PROFILE)

        promote("Preparando la sesión…")
        return runCatching {
            app.processor.process(sessionId, profile) { step ->
                when (step) {
                    is SessionProcessor.Step.Transcribing -> promote("Transcribiendo ${step.speechSeconds / 60} min ${step.speechSeconds % 60} s de voz…")
                    is SessionProcessor.Step.Summarizing -> promote("Resumiendo con ${step.model}…")
                    SessionProcessor.Step.Writing -> promote("Escribiendo la nota…")
                }
            }
        }.fold(
            onSuccess = { Result.success() },
            onFailure = {
                AppLog.e("procesado", "No se pudo procesar la sesión ${sessionId.take(8)}", it)
                if (runAttemptCount < 5) Result.retry() else Result.failure()
            },
        )
    }

    private suspend fun promote(text: String) {
        runCatching { setForeground(foregroundInfo(text)) }
            // Puede fallar si Android no deja arrancar un servicio desde segundo plano (Escriba debe
            // estar fuera de la optimización de batería). El trabajo sigue, pero con el límite de 10 min.
            .onFailure { AppLog.e("procesado", "No se pudo pasar a primer plano", it) }
    }

    private fun foregroundInfo(text: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Procesado de grabaciones", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("Escriba")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
    }

    companion object {
        private const val KEY_SESSION = "sesion"
        private const val KEY_PROFILE = "perfil"
        private const val CHANNEL = "procesado"
        private const val NOTIFICATION_ID = 10

        /** Las sesiones con más voz que esto esperan a que el móvil esté cargando. */
        const val CHARGING_THRESHOLD_MS = 10 * 60_000L

        /**
         * [speechMs] decide si espera a la carga. [now] fuerza el procesado inmediato (botón de la app).
         */
        fun enqueue(context: Context, sessionId: String, profileOverride: String? = null, speechMs: Long = 0, now: Boolean = false) {
            val waitForCharger = !now && speechMs > CHARGING_THRESHOLD_MS
            val request = OneTimeWorkRequestBuilder<ProcessWorker>()
                .setInputData(workDataOf(KEY_SESSION to sessionId, KEY_PROFILE to profileOverride))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiresCharging(waitForCharger).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("procesar-$sessionId", ExistingWorkPolicy.REPLACE, request)
            if (waitForCharger) AppLog.i("procesado", "Sesión ${sessionId.take(8)} larga (${speechMs / 60_000} min de voz): se procesará cargando")
        }
    }
}
