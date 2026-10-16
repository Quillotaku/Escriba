package dev.escriba.mobile.vault

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.escriba.datalayer.AppLog
import dev.escriba.mobile.EscribaApp
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Retención del audio, una vez al día:
 *  - en el móvil, el audio de las sesiones ya escritas en la bóveda se borra a los N días
 *    (`retencion_audio_movil`);
 *  - en la bóveda, el `.ogg` de cada nota se borra a los N días de su perfil (`retencion_audio`),
 *    y la nota se actualiza para que no quede un enlace roto.
 */
class RetentionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as EscribaApp
        val set = app.profiles.reload()
        val dao = app.db.sessions()
        val now = System.currentTimeMillis()

        val phoneDays = set?.config?.phoneAudioRetentionDays ?: 7
        for (session in dao.processedWithAudio()) {
            val processed = session.processedAtMs ?: continue
            if (now - processed < phoneDays * DAY_MS) continue
            File(app.filesDir, "inbox/${session.id}").deleteRecursively()
            dao.upsertSession(session.copy(audioDeletedAtMs = now))
            AppLog.i("retencion", "Audio de la sesión ${session.id.take(8)} borrado del móvil (${phoneDays} días)")
        }

        if (set != null && app.vault.isConfigured) {
            val today = LocalDate.now(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            for (note in dao.notesWithAudio()) {
                val days = set.profiles[note.profileId]?.audioRetentionDays ?: continue
                if (now - note.writtenAtMs < days * DAY_MS) continue
                val audio = note.audioPath ?: continue
                runCatching {
                    app.vault.delete(audio)
                    app.vault.readText(note.path)?.let { text ->
                        val updated = text
                            .replace("![[$audio]]", "_Audio borrado el $today (retención de $days días)._")
                            .replace("\"[[$audio]]\"", "\"borrado $today\"")
                        if (updated != text) app.vault.writeText(note.path, updated)
                    }
                    dao.upsertNote(note.copy(audioDeletedAtMs = now))
                    AppLog.i("retencion", "Audio borrado de la bóveda: $audio ($days días)")
                }.onFailure { AppLog.e("retencion", "No se pudo borrar $audio", it) }
            }
        }
        return Result.success()
    }

    companion object {
        private const val DAY_MS = 86_400_000L

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "retencion",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<RetentionWorker>(1, TimeUnit.DAYS).build(),
            )
        }

        fun runNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("retencion-ahora", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<RetentionWorker>().build())
        }
    }
}
