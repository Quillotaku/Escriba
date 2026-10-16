package dev.escriba.mobile.transcription

import dev.escriba.datalayer.AppLog
import dev.escriba.mobile.data.EscribaDb
import dev.escriba.nativeml.WhisperEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Fase 6a: mide cuánto tarda Whisper en este móvil con una sesión real. */
class Benchmark(private val scope: CoroutineScope, private val db: EscribaDb, private val transcriber: Transcriber) {

    sealed interface State {
        data object Idle : State
        data class Running(val message: String) : State
        data class Done(val result: Transcriber.Result, val timings: String) : State
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
}
