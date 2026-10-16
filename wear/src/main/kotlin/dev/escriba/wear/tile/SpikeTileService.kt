package dev.escriba.wear.tile

import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.sp
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import dev.escriba.datalayer.AppLog
import dev.escriba.wear.QuickStartActivity
import dev.escriba.wear.recorder.RecorderService
import dev.escriba.wear.recorder.RecordingState

/**
 * Fase 1 (spike): Tile con dos formas de arrancar la grabación, para comprobar cuál
 * permite HyperOS:
 *  - "Directo": el Tile arranca el servicio desde segundo plano.
 *  - "Vía actividad": el Tile abre QuickStartActivity, que arranca el servicio.
 */
class SpikeTileService : TileService() {

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        val clicked = requestParams.currentState.lastClickableId
        AppLog.i(TAG, "Tile pedido (último clic='${clicked.ifEmpty { "ninguno" }}')")
        if (clicked == ID_DIRECT) startDirect()

        val recording = RecordingState.isRecording
        val layout = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(label(if (recording) "● Grabando" else "Escriba · spike", 16f, if (recording) RED else WHITE))
            .addContent(spacer())
            .addContent(chip("Directo", ID_DIRECT, ActionBuilders.LoadAction.Builder().build(), GREEN))
            .addContent(spacer())
            .addContent(chip("Vía actividad", ID_ACTIVITY, launchQuickStart(), BLUE))
            .build()

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
            .build()
        return immediate(tile)
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        immediate(ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build())

    private fun startDirect() {
        runCatching {
            startForegroundService(RecorderService.intent(this, RecorderService.ACTION_TOGGLE, "tile-directo"))
        }.onSuccess {
            AppLog.i(TAG, "Tile: startForegroundService aceptado")
        }.onFailure {
            AppLog.e(TAG, "Tile: startForegroundService RECHAZADO", it)
        }
    }

    private fun launchQuickStart(): ActionBuilders.Action =
        ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(packageName)
                    .setClassName(QuickStartActivity::class.java.name)
                    .addKeyToExtraMapping(
                        RecorderService.EXTRA_ORIGIN,
                        ActionBuilders.AndroidStringExtra.Builder().setValue("tile-actividad").build(),
                    )
                    .build(),
            )
            .build()

    private fun label(text: String, size: Float, color: Int) =
        LayoutElementBuilders.Text.Builder()
            .setText(text)
            .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(size)).setColor(argb(color)).build())
            .build()

    private fun spacer() = LayoutElementBuilders.Spacer.Builder().setHeight(dp(8f)).build()

    private fun chip(text: String, id: String, action: ActionBuilders.Action, color: Int) =
        LayoutElementBuilders.Box.Builder()
            .setWidth(dp(150f))
            .setHeight(dp(44f))
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(ModifiersBuilders.Clickable.Builder().setId(id).setOnClick(action).build())
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(color))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(22f)).build())
                            .build(),
                    )
                    .build(),
            )
            .addContent(label(text, 14f, WHITE))
            .build()

    private fun <T : Any> immediate(value: T): ListenableFuture<T> =
        CallbackToFutureAdapter.getFuture { completer ->
            completer.set(value)
            "escriba-tile"
        }

    private companion object {
        const val TAG = "tile"
        const val ID_DIRECT = "directo"
        const val ID_ACTIVITY = "actividad"
        const val RESOURCES_VERSION = "1"
        const val WHITE = 0xFFFFFFFF.toInt()
        const val RED = 0xFFFF5252.toInt()
        const val GREEN = 0xFF2E7D32.toInt()
        const val BLUE = 0xFF1565C0.toInt()
    }
}
