package dev.escriba.mobile.transcription

import dev.escriba.datalayer.AppLog
import dev.escriba.llm.LlamaEngine
import dev.escriba.mobile.data.EscribaDb
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

/** Pruebas de rendimiento con sesiones reales: Whisper (fase 6a) y el LLM (fase 7). */
class Benchmark(
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
            _state.value = State.Running("Cargando $modelName…")
            runCatching {
                val fragments = db.sessions().fragments(sessionId)
                check(fragments.isNotEmpty()) { "La sesión no tiene fragmentos" }
                transcriber.open(modelName, flashAttn).use { session ->
                    _state.value = State.Running("Transcribiendo ${fragments.size} fragmentos con $threads hilos…")
                    session.transcribe(fragments, language = "es", vocabulary = emptyList(), threads = threads, fast = fast)
                }
            }.onSuccess {
                // El desglose que imprime whisper.cpp (las últimas líneas con «time =»).
                val timings = WhisperEngine.nativeLog().lines().filter { "time =" in it }.takeLast(8).joinToString("\n") { it.trim() }
                AppLog.i("transcripcion", "Desglose: " + timings.replace("\n", " | "))
                _state.value = State.Done(it, timings)
            }.onFailure {
                AppLog.e("transcripcion", "Falló la prueba", it)
                _state.value = State.Failed((it.message ?: it.javaClass.simpleName) + "\n" + WhisperEngine.nativeLog().takeLast(800))
            }
        }
    }

    /** Resume la transcripción ya guardada de una sesión con el perfil con que se procesó. */
    fun runLlm(sessionId: String, modelName: String) {
        if (_state.value is State.Running) return
        scope.launch {
            _state.value = State.Running("Cargando $modelName…")
            runCatching {
                val dao = db.sessions()
                val transcript = dao.transcript(sessionId) ?: error("Esa sesión aún no está transcrita")
                val lines = EscribaJson.decodeFromString<List<TranscriptLine>>(transcript.linesJson)
                val set = profiles.reload() ?: error("No se pudo leer la bóveda")
                val session = dao.session(sessionId) ?: error("La sesión no existe")
                val profile = set.profiles[session.processedProfileId ?: session.profileId] ?: set.defaultProfile ?: error("Sin perfiles")
                val zone = runCatching { ZoneId.of(set.config.timeZone) }.getOrDefault(ZoneId.systemDefault())
                summarizer.open(modelName).use { s ->
                    _state.value = State.Running("Resumiendo ${lines.size} frases con $modelName (perfil ${profile.name})…")
                    s.summarize(lines, profile, processor.knownEntities(set.config), zone)
                }
            }.onSuccess {
                _state.value = State.LlmDone(it)
            }.onFailure {
                AppLog.e("resumen", "Falló la prueba del LLM", it)
                _state.value = State.Failed((it.message ?: it.javaClass.simpleName) + "\n" + LlamaEngine.nativeLog().takeLast(800))
            }
        }
    }
}
