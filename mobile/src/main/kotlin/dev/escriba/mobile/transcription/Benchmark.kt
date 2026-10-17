package dev.escriba.mobile.transcription

import android.content.Context
import dev.escriba.datalayer.AppLog
import dev.escriba.llm.LlamaEngine
import dev.escriba.mobile.data.EscribaDb
import dev.escriba.mobile.R
import dev.escriba.mobile.llm.Summarizer
import dev.escriba.mobile.processing.SessionProcessor
import dev.escriba.mobile.vault.ProfileRepository
import dev.escriba.nativeml.WhisperEngine
import dev.escriba.shared.EscribaJson
import dev.escriba.shared.transcription.TranscriptLine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.ZoneId

/** Benchmarks with real sessions: speech recognition and the LLM. */
class Benchmark(
    private val context: Context,
    private val scope: CoroutineScope,
    private val db: EscribaDb,
    private val transcriber: Transcriber,
    private val summarizer: Summarizer,
    private val profiles: ProfileRepository,
    private val processor: SessionProcessor,
) {

    sealed interface State {
        data object Idle : State
        data class Running(val message: String) : State
        data class Done(val result: Transcriber.Result, val timings: String) : State
        data class LlmDone(val result: Summarizer.Result) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    fun run(sessionId: String, modelName: String, threads: Int, flashAttn: Boolean, fast: Boolean) {
        if (_state.value is State.Running) return
        scope.launch {
            _state.value = State.Running(context.getString(R.string.bench_loading, modelName))
            runCatching {
                val fragments = db.sessions().fragments(sessionId)
                check(fragments.isNotEmpty()) { "The session has no fragments" }
                // Parakeet shows up in the list with its model name.
                val engine = if (modelName == ParakeetAsr.MODEL_NAME) Transcriber.Engine.PARAKEET else Transcriber.Engine.WHISPER
                transcriber.open(modelName, flashAttn, engine).use { session ->
                    _state.value = State.Running(context.getString(R.string.bench_transcribing, fragments.size, threads))
                    session.transcribe(fragments, language = "auto", vocabulary = emptyList(), threads = threads, fast = fast)
                }
            }.onSuccess {
                // The breakdown whisper.cpp prints (the last lines with "time =").
                val timings = if (it.stats.model == ParakeetAsr.MODEL_NAME) "" else WhisperEngine.nativeLog().lines().filter { "time =" in it }.takeLast(8).joinToString("\n") { it.trim() }
                AppLog.i("transcription", "Breakdown: " + timings.replace("\n", " | "))
                _state.value = State.Done(it, timings)
            }.onFailure {
                AppLog.e("transcription", "The benchmark failed", it)
                _state.value = State.Failed((it.message ?: it.javaClass.simpleName) + "\n" + WhisperEngine.nativeLog().takeLast(800))
            }
        }
    }

    /** Summarizes a session's stored transcript with the profile it was processed with. */
    fun runLlm(sessionId: String, modelName: String) {
        if (_state.value is State.Running) return
        scope.launch {
            _state.value = State.Running(context.getString(R.string.bench_loading, modelName))
            runCatching {
                val dao = db.sessions()
                val transcript = dao.transcript(sessionId) ?: error("That session isn't transcribed yet")
                val lines = EscribaJson.decodeFromString<List<TranscriptLine>>(transcript.linesJson)
                val set = profiles.reload() ?: error("Couldn't read the vault")
                val session = dao.session(sessionId) ?: error("The session doesn't exist")
                val profile = set.profiles[session.processedProfileId ?: session.profileId] ?: set.defaultProfile ?: error("No profiles")
                val zone = runCatching { ZoneId.of(set.config.timeZone) }.getOrDefault(ZoneId.systemDefault())
                summarizer.open(modelName).use { s ->
                    _state.value = State.Running(context.getString(R.string.bench_summarizing, lines.size, modelName, profile.name))
                    s.summarize(lines, profile, processor.knownEntities(set.config), zone)
                }
            }.onSuccess {
                _state.value = State.LlmDone(it)
            }.onFailure {
                AppLog.e("summary", "The LLM benchmark failed", it)
                _state.value = State.Failed((it.message ?: it.javaClass.simpleName) + "\n" + LlamaEngine.nativeLog().takeLast(800))
            }
        }
    }
}
