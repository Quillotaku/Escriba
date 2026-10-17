package dev.escriba.mobile.processing

import dev.escriba.datalayer.AppLog
import dev.escriba.mobile.data.DiarizationEntity
import dev.escriba.mobile.data.EscribaDb
import dev.escriba.mobile.data.InsightsEntity
import dev.escriba.mobile.data.TranscriptEntity
import dev.escriba.mobile.data.UsageEntity
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
 * Processes a session end to end: transcribes, summarizes each conversation with the LLM and
 * writes its notes. Each stage stores its result, so if the process is cut short what's done isn't
 * repeated: a 20-minute transcription isn't lost because the summary failed.
 *
 * If a stage fails, the note is written anyway with whatever there is (no summary, or no
 * transcript) and the reason is kept in the session for the app.
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
        val session = dao.session(sessionId) ?: error("The session doesn't exist")
        val set = profiles.reload() ?: error("Couldn't read the vault settings")
        val profile = notes.resolveProfile(set, session.profileId, profileOverride)
        val problems = ArrayList<String>()

        val raw = runCatching { ensureTranscript(sessionId, profile, onStep) }
            .onFailure {
                AppLog.e(TAG, "Session ${sessionId.take(8)} not transcribed", it)
                problems += "without a transcript (${it.message})"
            }
            .getOrNull()

        // Who speaks: diarization + enrolled voices. If it fails, the note goes without speakers.
        var speakers = emptyList<String>()
        val lines = if (raw != null && profile.diarization) {
            runCatching {
                val labeled = labelSpeakers(sessionId, raw, onStep)
                speakers = labeled.second
                labeled.first
            }.onFailure {
                AppLog.e(TAG, "Session ${sessionId.take(8)} not diarized", it)
                problems += "without speakers (${it.message})"
            }.getOrDefault(raw)
        } else raw

        val insights = if (lines != null && profile.prompt.isNotBlank()) {
            // If the speaker names change, the summary is redone.
            val key = if (speakers.isEmpty() && lines.none { it.speaker != null }) profile.hash
            else profile.hash + "-" + Crypto.hex(Crypto.sha256(lines.mapNotNull { it.speaker }.distinct().sorted().joinToString("|").encodeToByteArray())).take(8)
            runCatching { ensureInsights(sessionId, profile, key, set.config, lines, onStep) }
                .onFailure {
                    AppLog.e(TAG, "Session ${sessionId.take(8)} not summarized", it)
                    problems += "without a summary (${it.message})"
                }
                .getOrNull()
        } else null

        onStep(Step.Writing)
        val outcome = notes.process(sessionId, profileOverride, lines, insights, speakers)
        if (problems.isNotEmpty()) {
            dao.session(sessionId)?.let { dao.upsertSession(it.copy(processError = "Note written " + problems.joinToString(" and "))) }
        }
        return outcome
    }

    /** Names of the notes that already exist in the people, projects and clients folders. */
    fun knownEntities(config: GlobalConfig): KnownEntities {
        fun names(folder: String) = runCatching {
            vault.list(folder).filter { !it.isDirectory && it.name.endsWith(".md") }.map { it.name.removeSuffix(".md") }
        }.getOrDefault(emptyList())
        return KnownEntities(names(config.peopleFolder), names(config.projectsFolder), names(config.clientsFolder))
    }

    /** Speakers of an already diarized session, for the Voices screen. */
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
                label = labels[c] ?: "${VoiceMath.UNKNOWN_PREFIX} ${c + 1}",
                speechMs = speech[c] ?: 0,
                samples = labeled.filter { it.speaker == "#$c" }.sortedByDescending { it.text.length }.take(3).map { it.text },
                hasEmbedding = c in embeddings,
            )
        }
    }

    /** Enrolls the voice of the session's speaker [cluster] under the name [name]. */
    suspend fun enrollSpeaker(sessionId: String, cluster: Int, name: String) {
        val d = db.sessions().diarization(sessionId) ?: error("The session isn't diarized")
        val embedding = decodeEmbeddings(d)[cluster] ?: error("That speaker has too little speech to enroll")
        voices.enroll(name.trim(), embedding)
    }

    private fun decodeEmbeddings(d: DiarizationEntity): Map<Int, FloatArray> = cleaned(d).embeddings

    /** Stored diarization, with split speakers already joined. */
    private fun cleaned(d: DiarizationEntity) = VoiceMath.merge(
        EscribaJson.decodeFromString<List<SpeakerTurn>>(d.turnsJson),
        EscribaJson.decodeFromString<Map<Int, String>>(d.embeddingsJson).mapValues { VoiceStore.decode(it.value) },
        EscribaJson.decodeFromString<Map<Int, Long>>(d.speechMsJson),
    )

    /** Drops the detected speakers and separates them again. */
    suspend fun redoDiarization(sessionId: String): DiarizationEntity {
        db.sessions().deleteDiarization(sessionId)
        return ensureDiarization(sessionId)
    }

    /** Separates a session's speakers (or reuses what was already computed). */
    suspend fun ensureDiarization(sessionId: String, onStep: suspend (Step) -> Unit = {}): DiarizationEntity {
        val dao = db.sessions()
        return dao.diarization(sessionId) ?: run {
            val fragments = dao.fragments(sessionId)
            check(fragments.all { File(it.path).exists() }) { "the audio is no longer on the phone (retention)" }
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
     * The session's lines with their speaker, if it's diarized, and the names of the enrolled
     * voices. It's what the session detail shows; it doesn't compute anything new.
     */
    suspend fun labeledLines(sessionId: String): Pair<List<TranscriptLine>, Set<String>> {
        val dao = db.sessions()
        val lines = dao.transcript(sessionId)?.let { EscribaJson.decodeFromString<List<TranscriptLine>>(it.linesJson) } ?: return emptyList<TranscriptLine>() to emptySet()
        val known = voices.names().toSet()
        val d = dao.diarization(sessionId) ?: return lines to known
        return label(d, lines, voices.all()).first to known
    }

    /** Lines with their speaker and the list of speakers recognized by their voice. */
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
        // With a single speaker there's nothing to label… unless it's a known voice.
        val labels = VoiceMath.identify(clean.embeddings, enrolled).toMutableMap()
        speech.keys.forEach { c -> labels.getOrPut(c) { "${VoiceMath.UNKNOWN_PREFIX} ${labels.size + 1}" } }
        val known = enrolled.map { it.name }.toSet()
        if (speech.size <= 1 && labels.values.none { it in known }) return lines to emptyList()
        val labeled = VoiceMath.assign(lines, turns) { labels[it] ?: "${VoiceMath.UNKNOWN_PREFIX} ${it + 1}" }
        return labeled to labels.values.filter { it in known }.distinct()
    }

    private suspend fun ensureTranscript(
        sessionId: String,
        profile: Profile,
        onStep: suspend (Step) -> Unit,
    ): List<TranscriptLine> {
        val engine = if (profile.engine == "parakeet") Transcriber.Engine.PARAKEET else Transcriber.Engine.WHISPER
        val model = if (engine == Transcriber.Engine.PARAKEET) ParakeetAsr.MODEL_NAME else profile.whisper
        val language = profile.language
        val vocabulary = profile.vocabulary
        val dao = db.sessions()
        dao.transcript(sessionId)?.let { existing ->
            if (existing.model.contains(model.removePrefix("ggml-").removeSuffix(".bin"))) {
                return EscribaJson.decodeFromString(existing.linesJson)
            }
            AppLog.i(TAG, "The session was transcribed with ${existing.model}; the profile asks for $model: transcribing again")
        }
        val fragments = dao.fragments(sessionId)
        check(fragments.isNotEmpty()) { "the session has no fragments" }
        check(fragments.all { File(it.path).exists() }) { "the audio is no longer on the phone (retention)" }

        onStep(Step.Transcribing(model, fragments.sumOf { it.speechMs } / 1000))
        val result = transcriber.open(model, engine = engine).use { it.transcribe(fragments, language, vocabulary) }
        dao.insertUsage(
            UsageEntity(
                kind = UsageEntity.ASR, model = result.stats.model, sessionId = sessionId, audioMs = result.stats.audioMs,
                computeMs = result.stats.transcribeMs, promptTokens = 0, generatedTokens = 0, atMs = System.currentTimeMillis(),
            ),
        )
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

    /** LLM result per conversation (index → insights). Reuses what was computed with the same model and profile. */
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
                dao.insertUsage(
                    UsageEntity(
                        kind = UsageEntity.LLM, model = r.stats.model, sessionId = sessionId, audioMs = 0,
                        computeMs = r.stats.totalMs, promptTokens = r.stats.promptTokens, generatedTokens = r.stats.generatedTokens,
                        atMs = System.currentTimeMillis(),
                    ),
                )
                dao.upsertInsights(InsightsEntity(sessionId, c.index, session.modelName, key, EscribaJson.encodeToString(r.insights), System.currentTimeMillis()))
                result[c.index] = r.insights
            }
        }
        return result
    }

    private companion object {
        const val TAG = "processing"
    }
}
