package dev.escriba.wear.tile

import android.content.Intent
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
import dev.escriba.shared.vault.WatchProfile
import dev.escriba.wear.EscribaWearApp
import dev.escriba.wear.ProfilePickerActivity
import dev.escriba.wear.recorder.RecorderService
import dev.escriba.wear.recorder.RecordingState

/**
 * Tile de Escriba: los perfiles favoritos como botones.
 *
 *  - Parado: tocar un perfil empieza a grabar con él.
 *  - Grabando o en pausa: tocar otro perfil cierra la sesión y abre otra con ese perfil;
 *    tocar el activo, o «Parar», detiene la grabación.
 *  - «Más…» abre la lista con todos los perfiles.
 *
 * En la fase 1 se comprobó que HyperOS deja arrancar el servicio de micrófono directamente
 * desde el Tile, sin pasar por una actividad.
 */
class EscribaTileService : TileService() {

    private val profiles get() = (application as EscribaWearApp).profiles

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        handleClick(requestParams.currentState.lastClickableId)

        val phase = RecordingState.phase.value
        val live = RecordingState.live.value
        val active = profiles.selected.value
        val slots = profiles.forTile(SLOTS)

        val title = when (phase) {
            RecordingState.Phase.CAPTURING -> label("● ${live.profileName ?: "Grabando"}", 15f, RED)
            RecordingState.Phase.PAUSED -> label("En pausa · ${live.pausedUntil ?: ""}", 12f, AMBER)
            RecordingState.Phase.IDLE -> label("Escriba", 15f, WHITE)
        }

        val column = LayoutElementBuilders.Column.Builder()
            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
            .addContent(title)
            .addContent(spacer(6f))

        if (slots.isEmpty()) {
            column.addContent(label("Sin perfiles: elige la bóveda en el móvil", 11f, GREY))
        } else {
            slots.chunked(2).forEach { pair ->
                val row = LayoutElementBuilders.Row.Builder()
                pair.forEachIndexed { i, p ->
                    if (i > 0) row.addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(6f)).build())
                    val isActive = phase != RecordingState.Phase.IDLE && p.id == active?.id
                    row.addContent(profileChip(p, isActive))
                }
                column.addContent(row.build()).addContent(spacer(6f))
            }
        }

        val bottom = LayoutElementBuilders.Row.Builder()
        if (phase != RecordingState.Phase.IDLE) {
            bottom.addContent(chip("Parar", ID_STOP, ActionBuilders.LoadAction.Builder().build(), RED, width = 70f))
            bottom.addContent(LayoutElementBuilders.Spacer.Builder().setWidth(dp(6f)).build())
        }
        bottom.addContent(chip("Más…", ID_MORE, openPicker(), GREY_DARK, width = 70f))
        column.addContent(bottom.build())

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(column.build()))
            .build()
        return immediate(tile)
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        immediate(ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build())

    private fun handleClick(id: String) {
        if (id.isEmpty() || id == ID_MORE) return
        val phase = RecordingState.phase.value
        val intent: Intent = when {
            id == ID_STOP -> RecorderService.intent(this, RecorderService.ACTION_STOP, ORIGIN)
            id.startsWith(PREFIX_PROFILE) -> {
                val profileId = id.removePrefix(PREFIX_PROFILE)
                if (phase != RecordingState.Phase.IDLE && profileId == profiles.selected.value?.id) {
                    RecorderService.intent(this, RecorderService.ACTION_STOP, ORIGIN)
                } else {
                    RecorderService.switchIntent(this, profileId, ORIGIN)
                }
            }
            else -> return
        }
        runCatching { RecorderService.send(this, intent) }
            .onFailure { AppLog.e(TAG, "El Tile no pudo hablar con el servicio de grabación", it) }
    }

    private fun profileChip(p: WatchProfile, active: Boolean): LayoutElementBuilders.LayoutElement =
        chip(
            text = (if (active) "■ " else "") + p.name.take(9),
            id = PREFIX_PROFILE + p.id,
            action = ActionBuilders.LoadAction.Builder().build(),
            color = parseColor(p.color),
            width = 80f,
            outlined = active,
        )

    private fun openPicker(): ActionBuilders.Action =
        ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(packageName)
                    .setClassName(ProfilePickerActivity::class.java.name)
                    .build(),
            )
            .build()

    private fun label(text: String, size: Float, color: Int) =
        LayoutElementBuilders.Text.Builder()
            .setText(text)
            .setMaxLines(1)
            .setFontStyle(LayoutElementBuilders.FontStyle.Builder().setSize(sp(size)).setColor(argb(color)).build())
            .build()

    private fun spacer(height: Float) = LayoutElementBuilders.Spacer.Builder().setHeight(dp(height)).build()

    private fun chip(text: String, id: String, action: ActionBuilders.Action, color: Int, width: Float, outlined: Boolean = false) =
        LayoutElementBuilders.Box.Builder()
            .setWidth(dp(width))
            .setHeight(dp(40f))
            .setModifiers(
                ModifiersBuilders.Modifiers.Builder()
                    .setClickable(ModifiersBuilders.Clickable.Builder().setId(id).setOnClick(action).build())
                    .setBackground(
                        ModifiersBuilders.Background.Builder()
                            .setColor(argb(color))
                            .setCorner(ModifiersBuilders.Corner.Builder().setRadius(dp(20f)).build())
                            .build(),
                    )
                    .apply {
                        if (outlined) setBorder(ModifiersBuilders.Border.Builder().setWidth(dp(2f)).setColor(argb(WHITE)).build())
                    }
                    .build(),
            )
            .addContent(label(text, 12f, WHITE))
            .build()

    private fun <T : Any> immediate(value: T): ListenableFuture<T> =
        CallbackToFutureAdapter.getFuture { completer ->
            completer.set(value)
            "escriba-tile"
        }

    private fun parseColor(hex: String): Int = runCatching { android.graphics.Color.parseColor(hex) }.getOrDefault(GREY_DARK)

    private companion object {
        const val TAG = "tile"
        const val ORIGIN = "tile"
        const val SLOTS = 4
        const val ID_STOP = "parar"
        const val ID_MORE = "mas"
        const val PREFIX_PROFILE = "perfil:"
        const val RESOURCES_VERSION = "2"
        const val WHITE = 0xFFFFFFFF.toInt()
        const val GREY = 0xFFB0BEC5.toInt()
        const val GREY_DARK = 0xFF37474F.toInt()
        const val RED = 0xFFE53935.toInt()
        const val AMBER = 0xFFFFB300.toInt()
    }
}
