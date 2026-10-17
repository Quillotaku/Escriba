package dev.escriba.mobile.processing

import dev.escriba.datalayer.AppLog
import dev.escriba.mobile.data.EscribaDb
import dev.escriba.mobile.data.InsightsEntity
import dev.escriba.mobile.data.TranscriptEntity
import dev.escriba.mobile.llm.KnownEntities
import dev.escriba.mobile.llm.Summarizer
import dev.escriba.mobile.transcription.Transcriber
import dev.escriba.mobile.vault.NoteWriter
import dev.escriba.mobile.vault.ProfileRepository
import dev.escriba.mobile.vault.VaultAccess
import dev.escriba.shared.EscribaJson
import dev.escriba.shared.Segment
import dev.escriba.shared.llm.Insights
import dev.escriba.shared.transcription.TranscriptLine
import dev.escriba.shared.vault.Conversations
import dev.escriba.shared.vault.FragmentInfo
import dev.escriba.shared.vault.GlobalConfig
import dev.escriba.shared.vault.Profile
import java.io.File
import java.time.ZoneId

/**
 * Procesa una sesión de principio a fin: transcribe, resume cada conversación con el LLM y
 * escribe sus notas. Cada etapa guarda su resultado, así que si el proceso se corta no se repite
 * lo ya hecho: una transcripción de 20 minutos no se pierde por un fallo en el resumen.
 *
 * Si una etapa falla, la nota se escribe igual con lo que haya (sin resumen, o sin transcripción)
 * y el motivo queda en la sesión para la app.
 */
class SessionProcessor(
    private val db: EscribaDb,
    private val vault: VaultAccess,
    private val profiles: ProfileRepository,
    private val transcriber: Transcriber,
    private val summarizer: Summarizer,
    private val notes: NoteWriter,
) {
    sealed interface Step {
        data class Transcribing(val model: String, val speechSeconds: Long) : Step
        data class Summarizing(val model: String, val conversations: Int) : Step
        data object Writing : Step
    }

    suspend fun process(sessionId: String, profileOverride: String? = null, onStep: suspend (Step) -> Unit = {}): NoteWriter.Outcome {
        val dao = db.sessions()
        val session = dao.session(sessionId) ?: error("La sesión no existe")
        val set = profiles.reload() ?: error("No se pudo leer la configuración de la bóveda")
        val profile = notes.resolveProfile(set, session.profileId, profileOverride)
        val problems = ArrayList<String>()

        val lines = runCatching { ensureTranscript(sessionId, profile.whisper, profile.language, profile.vocabulary, onStep) }
            .onFailure {
                AppLog.e(TAG, "Sesión ${sessionId.take(8)} sin transcribir", it)
                problems += "sin transcripción (${it.message})"
            }
            .getOrNull()

        val insights = if (lines != null && profile.prompt.isNotBlank()) {
            runCatching { ensureInsights(sessionId, profile, set.config, lines, onStep) }
                .onFailure {
                    AppLog.e(TAG, "Sesión ${sessionId.take(8)} sin resumir", it)
                    problems += "sin resumen (${it.message})"
                }
                .getOrNull()
        } else null

        onStep(Step.Writing)
        val outcome = notes.process(sessionId, profileOverride, lines, insights)
        if (problems.isNotEmpty()) {
            dao.session(sessionId)?.let { dao.upsertSession(it.copy(processError = "Nota escrita " + problems.joinToString(" y "))) }
        }
        return outcome
    }

    /** Nombres de las notas que ya existen en las carpetas de personas, proyectos y clientes. */
    fun knownEntities(config: GlobalConfig): KnownEntities {
        fun names(folder: String) = runCatching {
            vault.list(folder).filter { !it.isDirectory && it.name.endsWith(".md") }.map { it.name.removeSuffix(".md") }
        }.getOrDefault(emptyList())
        return KnownEntities(names(config.peopleFolder), names(config.projectsFolder), names(config.clientsFolder))
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
        check(fragments.isNotEmpty()) { "la sesión no tiene fragmentos" }
        check(fragments.all { File(it.path).exists() }) { "el audio ya no está en el móvil (retención)" }

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

    /** Resultado del LLM por conversación (índice → insights). Reutiliza lo ya calculado con el mismo modelo y perfil. */
    private suspend fun ensureInsights(
        sessionId: String,
        profile: Profile,
        config: GlobalConfig,
        lines: List<TranscriptLine>,
        onStep: suspend (Step) -> Unit,
    ): Map<Int, Insights> {
        val dao = db.sessions()
        val conversations = Conversations.split(
            dao.fragments(sessionId).map { f -> FragmentInfo(f.seq, f.startMs, f.endMs, EscribaJson.decodeFromString<List<Segment>>(f.segmentsJson)) },
            profile.splitAfterSilenceMin,
        )
        val stored = dao.insights(sessionId).associateBy { it.conversation }
        val result = HashMap<Int, Insights>()
        val pending = conversations.filter { c ->
            val s = stored[c.index]
            val reusable = s != null && s.profileHash == profile.hash && s.model.contains(profile.llm, ignoreCase = true)
            if (reusable) result[c.index] = Insights.parse(s.json)
            !reusable
        }
        if (pending.isEmpty()) return result

        onStep(Step.Summarizing(profile.llm, pending.size))
        val zone = runCatching { ZoneId.of(config.timeZone) }.getOrDefault(ZoneId.systemDefault())
        val known = knownEntities(config)
        summarizer.open(profile.llm).use { session ->
            for (c in pending) {
                val own = lines.filter { it.wallStartMs >= c.startMs - 1_000 && it.wallStartMs <= c.endMs + 1_000 }
                if (own.isEmpty()) continue
                val r = session.summarize(own, profile, known, zone)
                dao.upsertInsights(InsightsEntity(sessionId, c.index, session.modelName, profile.hash, EscribaJson.encodeToString(r.insights), System.currentTimeMillis()))
                result[c.index] = r.insights
            }
        }
        return result
    }

    private companion object {
        const val TAG = "procesado"
    }
}
