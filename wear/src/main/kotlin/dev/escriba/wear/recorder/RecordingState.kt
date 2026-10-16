package dev.escriba.wear.recorder

import android.content.ComponentName
import android.content.Context
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import dev.escriba.wear.complication.RecordingComplicationService
import dev.escriba.wear.tile.EscribaTileService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Estado de grabación compartido por el servicio, la UI, el Tile y la complicación. */
object RecordingState {

    enum class Phase { IDLE, CAPTURING, PAUSED }

    /** Datos en vivo de la sesión, para la pantalla del reloj. */
    data class Live(
        val sessionId: String? = null,
        val origin: String? = null,
        val mode: RecordingMode? = null,
        val startedAtMs: Long = 0,
        val startedElapsedMs: Long = 0,
        val pausedUntil: String? = null,
        val fragments: Int = 0,
        val speechPct: Int = 0,
        val levelDb: Int = 0,
        val floorDb: Int = 0,
        val inSpeech: Boolean = false,
        val batteryAtStart: Float? = null,
        val profileName: String? = null,
        val profileColor: String? = null,
    )

    private val _phase = MutableStateFlow(Phase.IDLE)
    val phase: StateFlow<Phase> = _phase

    private val _live = MutableStateFlow(Live())
    val live: StateFlow<Live> = _live

    val isRecording: Boolean get() = _phase.value != Phase.IDLE

    internal fun setPhase(context: Context, phase: Phase) {
        if (_phase.value == phase) return
        _phase.value = phase
        refreshSurfaces(context)
    }

    internal fun update(transform: (Live) -> Live) {
        _live.value = transform(_live.value)
    }

    internal fun reset() {
        _live.value = Live()
    }

    /** Pide al sistema que vuelva a pintar el Tile y la complicación. */
    fun refreshSurfaces(context: Context) {
        val app = context.applicationContext
        runCatching { TileService.getUpdater(app).requestUpdate(EscribaTileService::class.java) }
        runCatching {
            ComplicationDataSourceUpdateRequester
                .create(app, ComponentName(app, RecordingComplicationService::class.java))
                .requestUpdateAll()
        }
    }
}
