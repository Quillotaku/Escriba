package dev.escriba.mobile.processing

import dev.escriba.datalayer.AppLog
import dev.escriba.mobile.data.DiarizationEntity
import dev.escriba.mobile.data.EscribaDb
import dev.escriba.mobile.data.InsightsEntity
import dev.escriba.mobile.data.TranscriptEntity
import dev.escriba.mobile.llm.KnownEntities
import dev.escriba.mobile.llm.Summarizer
import dev.escriba.mobile.transcription.ParakeetAsr
import dev.escriba.mobile.transcription.SpeechAudio
import dev.escriba.mobile.transcription.Transcriber
import dev.escriba.mobile.vault.NoteWriter
import dev.escriba.mobile.vault.ProfileRepository
import dev.escriba.mobile.vault.VaultAccess
import dev.escriba.mobile.voices.Diarizer
import dev.escriba.mobile.voices.VoiceStore
import dev.escriba.shared.EscribaJson
import dev.escriba.shared.Segment
import dev.escriba.shared.crypto.Crypto
import dev.escriba.shared.llm.Insights
import dev.escriba.shared.transcription.TranscriptLine
import dev.escriba.shared.vault.Conversations
import dev.escriba.shared.vault.FragmentInfo
import dev.escriba.shared.vault.GlobalConfig
import dev.escriba.shared.vault.Profile
import dev.escriba.shared.voices.SpeakerTurn
import dev.escriba.shared.voices.VoiceMath
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
    private val diarizer: Diarizer,
    private val voices: VoiceStore,
    private val notes: NoteWriter,
) {
    sealed interface Step {
        data class Transcribing(val model: String, val speechSeconds: Long) : Step
        data class Diarizing(val speechSeconds: Long) : Step
        data class Summarizing(val model: String, val conversations: Int) : Step
        data object Writing : Step
    }

    suspend fun process(sessionId: String, profileOverride: String? = null, onStep: suspend (Step) -> Unit = {}): NoteWriter.Outcome {
        val dao = db.sessions()
        val session = dao.session(sessionId) ?: error("La sesión no existe")
        val set = profiles.reload() ?: error("No se pudo leer la configuración de la bóveda")
        val profile = notes.resolveProfile(set, session.profileId, profileOverride)
        val problems = ArrayList<String>()

        val raw = runCatching { ensureTranscript(sessionId, profile, onStep) }
            .onFailure {
                AppLog.e(TAG, "Sesión ${sessionId.take(8)} sin transcribir", it)
                problems += "sin transcripción (${it.message})"
            }
            .getOrNull()

        // Quién habla: diarización + voces registradas. Si falla, la nota sale sin hablantes.
        var speakers = emptyList<String>()
        val lines = if (raw != null && profile.diarization) {
            runCatching {
                val labeled = labelSpeakers(sessionId, raw, onStep)
                speakers = labeled.second
                labeled.first
            }.onFailure {
                AppLog.e(TAG, "Sesión ${sessionId.take(8)} sin diarizar", it)
                problems += "sin hablantes (${it.message})"
            }.getOrDefault(raw)
        } else raw

        val insights = if (lines != null && profile.prompt.isNotBlank()) {
            // Si cambian los nombres de los hablantes, el resumen se rehace.
            val key = if (speakers.isEmpty() && lines.none { it.speaker != null }) profile.hash
            else profile.hash + "-" + Crypto.hex(Crypto.sha256(lines.mapNotNull { it.speaker }.distinct().sorted().joinToString("|").encodeToByteArray())).take(8)
            runCatching { ensureInsights(sessionId, profile, key, set.config, lines, onStep) }
                .onFailure {
                    AppLog.e(TAG, "Sesión ${sessionId.take(8)} sin resumir", it)
                    problems += "sin resumen (${it.message})"
                }
                .getOrNull()
        } else null

        onStep(Step.Writing)
        val outcome = notes.process(sessionId, profileOverride, lines, insights, speakers)
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

    /** Hablantes de una sesión ya diarizada, para la pantalla «Voces». */
    data class SessionSpeaker(val cluster: Int, val label: String, val speechMs: Long, val samples: List<String>, val hasEmbedding: Boolean)

    suspend fun speakers(sessionId: String): List<SessionSpeaker> {
        val dao = db.sessions()
        val d = dao.diarization(sessionId) ?: return emptyList()
        val clean = cleaned(d)
        val turns = clean.turns
        val embeddings = clean.embeddings
        val speech = clean.speechMs
        val labels = VoiceMath.identify(embeddings, voices.all())
        val lines = dao.transcript(sessionId)?.let { EscribaJson.decodeFromString<List<TranscriptLine>>(it.linesJson) }.orEmpty()
        val labeled = VoiceMath.assign(lines, turns) { "#$it" }
        return speech.keys.sorted().map { c ->
            SessionSpeaker(
                cluster = c,
                label = labels[c] ?: "Hablante ${c + 1}",
                speechMs = speech[c] ?: 0,
                samples = labeled.filter { it.speaker == "#$c" }.sortedByDescending { it.text.length }.take(3).map { it.text },
                hasEmbedding = c in embeddings,
            )
        }
    }

    /** Registra la voz del hablante [cluster] de la sesión con el nombre [name]. */
    suspend fun enrollSpeaker(sessionId: String, cluster: Int, name: String) {
        val d = db.sessions().diarization(sessionId) ?: error("La sesión no está diarizada")
        val embedding = decodeEmbeddings(d)[cluster] ?: error("Ese hablante tiene muy poca voz para registrarlo")
        voices.enroll(name.trim(), embedding)
    }

    private fun decodeEmbeddings(d: DiarizationEntity): Map<Int, FloatArray> = cleaned(d).embeddings

    /** Diarización guardada, con los hablantes partidos ya unidos. */
    private fun cleaned(d: DiarizationEntity) = VoiceMath.merge(
        EscribaJson.decodeFromString<List<SpeakerTurn>>(d.turnsJson),
        EscribaJson.decodeFromString<Map<Int, String>>(d.embeddingsJson).mapValues { VoiceStore.decode(it.value) },
        EscribaJson.decodeFromString<Map<Int, Long>>(d.speechMsJson),
    )

    /** Descarta los hablantes detectados y los vuelve a separar. */
    suspend fun redoDiarization(sessionId: String): DiarizationEntity {
        db.sessions().deleteDiarization(sessionId)
        return ensureDiarization(sessionId)
    }

    /** Separa los hablantes de una sesión (o reutiliza lo ya calculado). */
    suspend fun ensureDiarization(sessionId: String, onStep: suspend (Step) -> Unit = {}): DiarizationEntity {
        val dao = db.sessions()
        return dao.diarization(sessionId) ?: run {
            val fragments = dao.fragments(sessionId)
            check(fragments.all { File(it.path).exists() }) { "el audio ya no está en el móvil (retención)" }
            onStep(Step.Diarizing(fragments.sumOf { it.speechMs } / 1000))
            val r = diarizer.diarize(SpeechAudio.decode(fragments))
            DiarizationEntity(
                sessionId = sessionId,
                turnsJson = EscribaJson.encodeToString(r.turns),
                embeddingsJson = EscribaJson.encodeToString(r.embeddings.mapValues { VoiceStore.encode(it.value) }),
                speechMsJson = EscribaJson.encodeToString(r.speechMs),
                createdAtMs = System.currentTimeMillis(),
            ).also { dao.upsertDiarization(it) }
        }
    }

    /**
     * Frases de la sesión con su hablante, si está diarizada, y los nombres de las voces
     * registradas. Es lo que muestra el detalle de la sesión; no calcula nada nuevo.
     */
    suspend fun labeledLines(sessionId: String): Pair<List<TranscriptLine>, Set<String>> {
        val dao = db.sessions()
        val lines = dao.transcript(sessionId)?.let { EscribaJson.decodeFromString<List<TranscriptLine>>(it.linesJson) } ?: return emptyList<TranscriptLine>() to emptySet()
        val known = voices.names().toSet()
        val d = dao.diarization(sessionId) ?: return lines to known
        return label(d, lines, voices.all()).first to known
    }

    /** Frases con su hablante y la lista de hablantes reconocidos por su voz. */
    private suspend fun labelSpeakers(
        sessionId: String,
        lines: List<TranscriptLine>,
        onStep: suspend (Step) -> Unit,
    ): Pair<List<TranscriptLine>, List<String>> {
        val d = ensureDiarization(sessionId, onStep)
        return label(d, lines, voices.all())
    }

    private fun label(d: DiarizationEntity, lines: List<TranscriptLine>, enrolled: List<dev.escriba.shared.voices.EnrolledVoice>): Pair<List<TranscriptLine>, List<String>> {
        val clean = cleaned(d)
        val turns = clean.turns
        val speech = clean.speechMs
        // Con un solo hablante no hace falta etiquetar nada… salvo que sea una voz conocida.
        val labels = VoiceMath.identify(clean.embeddings, enrolled).toMutableMap()
        speech.keys.forEach { c -> labels.getOrPut(c) { "Hablante ${labels.size + 1}" } }
        val known = enrolled.map { it.name }.toSet()
        if (speech.size <= 1 && labels.values.none { it in known }) return lines to emptyList()
        val labeled = VoiceMath.assign(lines, turns) { labels[it] ?: "Hablante ${it + 1}" }
        return labeled to labels.values.filter { it in known }.distinct()
    }

    private suspend fun ensureTranscript(
        sessionId: String,
        profile: Profile,
        onStep: suspend (Step) -> Unit,
    ): List<TranscriptLine> {
        val motor = if (profile.engine == "parakeet") Transcriber.Motor.PARAKEET else Transcriber.Motor.WHISPER
        val model = if (motor == Transcriber.Motor.PARAKEET) ParakeetAsr.MODEL_NAME else profile.whisper
        val language = profile.language
        val vocabulary = profile.vocabulary
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
        val result = transcriber.open(model, motor = motor).use { it.transcribe(fragments, language, vocabulary) }
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
        key: String,
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
            val reusable = s != null && s.profileHash == key && s.model.contains(profile.llm, ignoreCase = true)
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
                dao.upsertInsights(InsightsEntity(sessionId, c.index, session.modelName, key, EscribaJson.encodeToString(r.insights), System.currentTimeMillis()))
                result[c.index] = r.insights
            }
        }
        return result
    }

    private companion object {
        const val TAG = "procesado"
    }
}
