package dev.escriba.wear.tile

import android.content.Intent
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.DimensionBuilders.weight
import androidx.wear.protolayout.DimensionBuilders.wrap
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.protolayout.material3.ButtonColors
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.Typography
import androidx.wear.protolayout.material3.buttonGroup
import androidx.wear.protolayout.material3.materialScope
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.material3.textButton
import androidx.wear.protolayout.material3.textEdgeButton
import androidx.wear.protolayout.modifiers.clickable
import androidx.wear.protolayout.types.LayoutColor
import androidx.wear.protolayout.types.LayoutString
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import dev.escriba.datalayer.AppLog
import dev.escriba.shared.vault.ProfilePalette
import dev.escriba.shared.vault.WatchProfile
import dev.escriba.wear.EscribaWearApp
import dev.escriba.wear.ProfilePickerActivity
import dev.escriba.wear.R
import dev.escriba.wear.recorder.RecorderService
import dev.escriba.wear.recorder.RecordingState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Escriba's Tile, built with the Tiles Material 3 components (like the system ones).
 *
 *  - Stopped: the favourite profiles as buttons in their colour; tapping one starts recording
 *    with it. The edge button, "More", opens the list with all of them.
 *  - Recording or paused: the active profile and since when it's recording, buttons to switch to
 *    another profile (closes the session and opens another) and "Stop" on the edge, in minium.
 *
 * HyperOS lets the Tile start the microphone service directly, without going through an activity.
 */
class EscribaTileService : TileService() {

    private val profiles get() = (application as EscribaWearApp).profiles

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> {
        handleClick(requestParams.currentState.lastClickableId)

        val phase = RecordingState.phase.value
        val live = RecordingState.live.value
        val active = profiles.selected.value
        val favourites = profiles.forTile(SLOTS)

        val layout = materialScope(this, requestParams.deviceConfiguration, allowDynamicTheme = false) {
            when (phase) {
                RecordingState.Phase.IDLE -> idleLayout(favourites)
                else -> recordingLayout(
                    paused = phase == RecordingState.Phase.PAUSED,
                    name = live.profileName ?: active?.name ?: getString(R.string.app_name),
                    color = live.profileColor ?: active?.color,
                    since = live.startedAtMs,
                    pausedUntil = live.pausedUntil,
                    others = favourites.filter { it.id != active?.id }.take(2),
                )
            }
        }

        val tile = TileBuilders.Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            // While recording, it refreshes every minute so "since…" and the pause stay current.
            .setFreshnessIntervalMillis(if (phase == RecordingState.Phase.IDLE) 0 else 60_000)
            .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout))
            .build()
        return immediate(tile)
    }

    override fun onTileResourcesRequest(requestParams: RequestBuilders.ResourcesRequest): ListenableFuture<ResourceBuilders.Resources> =
        immediate(ResourceBuilders.Resources.Builder().setVersion(RESOURCES_VERSION).build())

    // --- Layouts -------------------------------------------------------------

    private fun MaterialScope.idleLayout(favourites: List<WatchProfile>) = primaryLayout(
        titleSlot = { title(getString(R.string.app_name)) },
        mainSlot = {
            if (favourites.isEmpty()) {
                text(LayoutString(getString(R.string.tile_no_profiles)), typography = Typography.BODY_SMALL, color = MUTED, maxLines = 3)
            } else {
                profileButtons(favourites.take(3), fill = true)
            }
        },
        bottomSlot = { textEdgeButton(onClick = clickable(openPicker(), ID_MORE), colors = tonal()) { label(getString(R.string.tile_more)) } },
    )

    private fun MaterialScope.recordingLayout(
        paused: Boolean,
        name: String,
        color: String?,
        since: Long,
        pausedUntil: String?,
        others: List<WatchProfile>,
    ) = primaryLayout(
        titleSlot = { title(getString(if (paused) R.string.paused else R.string.tile_recording), if (paused) MUTED else MINIUM) },
        mainSlot = {
            val column = LayoutElementBuilders.Column.Builder()
                .setWidth(expand())
                .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                .addContent(text(LayoutString(name), typography = Typography.TITLE_LARGE, color = profileColor(color), maxLines = 1))
                .addContent(
                    text(
                        LayoutString(
                            if (paused) pausedUntil?.let { getString(R.string.tile_back, it) } ?: getString(R.string.tile_off_schedule)
                            else getString(R.string.tile_since, Instant.ofEpochMilli(since).atZone(ZoneId.systemDefault()).format(HM)),
                        ),
                        typography = Typography.BODY_SMALL,
                        color = MUTED,
                        maxLines = 1,
                    ),
                )
            if (others.isNotEmpty()) {
                column.addContent(LayoutElementBuilders.Spacer.Builder().setHeight(androidx.wear.protolayout.DimensionBuilders.dp(6f)).build())
                column.addContent(profileButtons(others, tonal = true))
            }
            column.build()
        },
        bottomSlot = {
            textEdgeButton(
                onClick = clickable(ActionBuilders.LoadAction.Builder().build(), ID_STOP),
                colors = ButtonColors(containerColor = MINIUM, labelColor = INK_ON_COLOR, iconColor = INK_ON_COLOR, secondaryLabelColor = INK_ON_COLOR),
            ) { label(getString(R.string.stop)) }
        },
    )

    /** A row of up to three profile buttons. Filled with their colour or, for switching, tonal. */
    private fun MaterialScope.profileButtons(list: List<WatchProfile>, tonal: Boolean = false, fill: Boolean = false) =
        buttonGroup(width = expand(), height = if (fill) expand() else wrap()) {
            list.forEach { p ->
                buttonGroupItem {
                    textButton(
                        onClick = clickable(ActionBuilders.LoadAction.Builder().build(), PREFIX_PROFILE + p.id),
                        width = weight(1f),
                        height = if (fill) expand() else androidx.wear.protolayout.DimensionBuilders.dp(48f),
                        colors = if (tonal) tonal() else ButtonColors(
                            containerColor = profileColor(p.color),
                            labelColor = INK_ON_COLOR,
                            iconColor = INK_ON_COLOR,
                            secondaryLabelColor = INK_ON_COLOR,
                        ),
                        labelContent = { label(p.name, if (tonal) INK else INK_ON_COLOR) },
                    )
                }
            }
        }

    private fun MaterialScope.title(text: String, color: LayoutColor = INK) =
        text(LayoutString(text), typography = Typography.TITLE_MEDIUM, color = color, maxLines = 1)

    private fun MaterialScope.label(text: String, color: LayoutColor = INK) =
        // Long names ("Quick Ideas") go down a size to fit without being cut.
        text(LayoutString(text), typography = if (text.length > 10) Typography.LABEL_SMALL else Typography.LABEL_MEDIUM, color = color, maxLines = 3)

    private fun tonal() = ButtonColors(containerColor = RAISED, labelColor = INK, iconColor = INK, secondaryLabelColor = MUTED)

    private fun profileColor(hex: String?): LayoutColor =
        ProfilePalette.nearest(hex)?.let { LayoutColor(PROFILE_WATCH[it]) } ?: MUTED

    // --- Actions -------------------------------------------------------------

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
            .onFailure { AppLog.e(TAG, "The Tile couldn't reach the recording service", it) }
    }

    private fun openPicker(): ActionBuilders.Action =
        ActionBuilders.LaunchAction.Builder()
            .setAndroidActivity(
                ActionBuilders.AndroidActivity.Builder()
                    .setPackageName(packageName)
                    .setClassName(ProfilePickerActivity::class.java.name)
                    .build(),
            )
            .build()

    private fun <T : Any> immediate(value: T): ListenableFuture<T> =
        CallbackToFutureAdapter.getFuture { completer ->
            completer.set(value)
            "escriba-tile"
        }

    private companion object {
        const val TAG = "tile"
        const val ORIGIN = "tile"
        const val SLOTS = 3
        const val ID_STOP = "stop"
        const val ID_MORE = "more"
        const val PREFIX_PROFILE = "profile:"
        const val RESOURCES_VERSION = "3"
        val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        // The design system's "watch" theme.
        val INK = LayoutColor(0xFFFFFFFF.toInt())
        val INK_ON_COLOR = LayoutColor(0xFF000000.toInt())
        val MUTED = LayoutColor(0xFFA9B6C1.toInt())
        val RAISED = LayoutColor(0xFF161C21.toInt())
        val MINIUM = LayoutColor(0xFFFF7D61.toInt())
        /** pine, lapis, ochre, plum, slate, terracotta (watch theme values). */
        val PROFILE_WATCH = intArrayOf(
            0xFF7FCF8E.toInt(), 0xFFA3B8F5.toInt(), 0xFFF0B65C.toInt(),
            0xFFE19AD8.toInt(), 0xFFA9BCCC.toInt(), 0xFFF2A07F.toInt(),
        )
    }
}
