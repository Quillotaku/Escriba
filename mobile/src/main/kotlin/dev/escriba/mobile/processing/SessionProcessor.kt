package dev.escriba.mobile.processing

import dev.escriba.datalayer.AppLog
import dev.escriba.mobile.data.EscribaDb
import dev.escriba.mobile.data.TranscriptEntity
import dev.escriba.mobile.transcription.Transcriber
import dev.escriba.mobile.vault.NoteWriter
import dev.escriba.mobile.vault.ProfileRepository
import dev.escriba.shared.EscribaJson
import dev.escriba.shared.transcription.TranscriptLine
import java.io.File

/**
 * Procesa una sesión de principio a fin: transcribe (si no lo está ya con el modelo del perfil)
 * y escribe sus notas. Cada etapa guarda su resultado, así que si el proceso se corta no se repite
 * lo ya hecho: una transcripción de 20 minutos no se pierde por un fallo al escribir la nota.
 */
class SessionProcessor(
    private val db: EscribaDb,
    private val profiles: ProfileRepository,
    private val transcriber: Transcriber,
    private val notes: NoteWriter,
) {
    sealed interface Step {
        data class Transcribing(val model: String, val speechSeconds: Long) : Step
        data object Writing : Step
    }

    suspend fun process(sessionId: String, profileOverride: String? = null, onStep: suspend (Step) -> Unit = {}): NoteWriter.Outcome {
        val dao = db.sessions()
        val session = dao.session(sessionId) ?: error("La sesión no existe")
        val set = profiles.reload() ?: error("No se pudo leer la configuración de la bóveda")
        val profile = notes.resolveProfile(set, session.profileId, profileOverride)

        val lines = runCatching { ensureTranscript(sessionId, profile.whisper, profile.language, profile.vocabulary, onStep) }
            .onFailure {
                // Sin transcripción la nota se escribe igual (con los tramos de voz), y se avisa.
                AppLog.e(TAG, "Sesión ${sessionId.take(8)} sin transcribir", it)
                dao.session(sessionId)?.let { s -> dao.upsertSession(s.copy(processError = "Sin transcribir: ${it.message}")) }
            }
            .getOrNull()

        onStep(Step.Writing)
        val outcome = notes.process(sessionId, profileOverride, lines)
        if (lines == null) dao.session(sessionId)?.let { s -> dao.upsertSession(s.copy(processError = "Nota escrita sin transcripción (revisa el modelo en «Modelos»)")) }
        return outcome
    }

    private suspend fun ensureTranscript(
        sessionId: String,
        model: String,
        language: String,
        vocabulary: List<String>,
        onStep: suspend (Step) -> Unit,
    ): List<TranscriptLine> {
        val dao = db.sessions()
        dao.transcript(sessionId)?.let { existing ->
            if (existing.model.contains(model.removePrefix("ggml-").removeSuffix(".bin"))) {
                return EscribaJson.decodeFromString(existing.linesJson)
            }
            AppLog.i(TAG, "La sesión se transcribió con ${existing.model}; el perfil pide $model: se transcribe de nuevo")
        }
        val fragments = dao.fragments(sessionId)
        check(fragments.isNotEmpty()) { "La sesión no tiene fragmentos" }
        check(fragments.all { File(it.path).exists() }) { "El audio ya no está en el móvil (retención): no se puede transcribir" }

        onStep(Step.Transcribing(model, fragments.sumOf { it.speechMs } / 1000))
        val result = transcriber.open(model).use { it.transcribe(fragments, language, vocabulary) }
        dao.upsertTranscript(
            TranscriptEntity(
                sessionId = sessionId,
                model = result.stats.model,
                language = language,
                linesJson = EscribaJson.encodeToString(result.lines),
                audioMs = result.stats.audioMs,
                transcribeMs = result.stats.transcribeMs,
                createdAtMs = System.currentTimeMillis(),
            ),
        )
        return result.lines
    }

    private companion object {
        const val TAG = "procesado"
    }
}
