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
 * Audio retention, once a day:
 *  - on the phone, the audio of sessions already written to the vault is deleted after N days
 *    (`phone_audio_retention`);
 *  - in the vault, each note's `.ogg` is deleted after its profile's N days (`audio_retention`),
 *    and the note is updated so no broken link is left.
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
            AppLog.i(TAG, "Audio of session ${session.id.take(8)} deleted from the phone ($phoneDays days)")
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
                            .replace("![[$audio]]", "_Audio deleted on $today ($days-day retention)._")
                            .replace("\"[[$audio]]\"", "\"deleted $today\"")
                        if (updated != text) app.vault.writeText(note.path, updated)
                    }
                    dao.upsertNote(note.copy(audioDeletedAtMs = now))
                    AppLog.i(TAG, "Audio deleted from the vault: $audio ($days days)")
                }.onFailure { AppLog.e(TAG, "Couldn't delete $audio", it) }
            }
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "retention"
        private const val DAY_MS = 86_400_000L

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "retention",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<RetentionWorker>(1, TimeUnit.DAYS).build(),
            )
        }

        fun runNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("retention-now", ExistingWorkPolicy.REPLACE, OneTimeWorkRequestBuilder<RetentionWorker>().build())
        }
    }
}
