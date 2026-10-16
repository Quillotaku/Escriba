package dev.escriba.wear.recorder

import android.content.ComponentName
import android.content.Context
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import dev.escriba.wear.complication.RecordingComplicationService
import dev.escriba.wear.tile.SpikeTileService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Estado de grabación compartido por el servicio, la UI, el Tile y la complicación. */
object RecordingState {

    data class Active(val origin: String, val startedAtMs: Long, val startedElapsedMs: Long)

    private val _active = MutableStateFlow<Active?>(null)
    val active: StateFlow<Active?> = _active

    val isRecording: Boolean get() = _active.value != null

    internal fun set(context: Context, value: Active?) {
        _active.value = value
        refreshSurfaces(context)
    }

    /** Pide al sistema que vuelva a pintar el Tile y la complicación. */
    fun refreshSurfaces(context: Context) {
        val app = context.applicationContext
        runCatching { TileService.getUpdater(app).requestUpdate(SpikeTileService::class.java) }
        runCatching {
            ComplicationDataSourceUpdateRequester
                .create(app, ComponentName(app, RecordingComplicationService::class.java))
                .requestUpdateAll()
        }
    }
}
