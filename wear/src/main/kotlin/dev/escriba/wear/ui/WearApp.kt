package dev.escriba.wear.ui

import android.content.res.Resources
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalScrollCaptureInProgress
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import androidx.wear.compose.foundation.AmbientMode
import androidx.wear.compose.foundation.AmbientTickEffect
import androidx.wear.compose.foundation.LocalAmbientModeManager
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListHeaderDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.material3.touchTargetAwareSize
import androidx.wear.compose.navigation3.rememberSwipeDismissableSceneStrategy
import dev.escriba.datalayer.AppLog
import dev.escriba.datalayer.PairingFailure
import dev.escriba.datalayer.PairingState
import dev.escriba.datalayer.PairingWait
import dev.escriba.shared.audio.VadSensitivity
import dev.escriba.shared.recording.DailyWindow
import dev.escriba.wear.EscribaWearApp
import dev.escriba.wear.R
import dev.escriba.wear.recorder.MicSource
import dev.escriba.wear.recorder.RecorderSettings
import dev.escriba.wear.recorder.RecordingMode
import dev.escriba.wear.recorder.RecordingState
import dev.escriba.wear.recorder.label
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Watch screens. The main one records; the rest are below it, turning the crown. */
private enum class Screen { HOME, PROFILES, PHONE, SETTINGS, DIAGNOSTICS }

/** What only the activity can do. */
class WatchActions(
    val toggleRecording: () -> Unit,
    val switchProfile: (String) -> Unit,
    val sendNow: () -> Unit,
    val requestBatteryExemption: () -> Unit,
    val batteryNow: () -> Float?,
)

@Composable
fun WearApp(app: EscribaWearApp, settings: RecorderSettings, actions: WatchActions) {
    val backStack = remember { mutableStateListOf(Screen.HOME) }
    AppScaffold {
        NavDisplay(
            backStack = backStack,
            onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
            sceneStrategies = listOf(rememberSwipeDismissableSceneStrategy()),
            entryProvider = entryProvider {
                entry<Screen> { screen ->
                    when (screen) {
                        Screen.HOME -> HomeScreen(app, actions) { backStack.add(it) }
                        Screen.PROFILES -> ProfilesScreen(app) { profile, current, idle ->
                            if (!current) {
                                if (idle) app.profiles.select(profile) else actions.switchProfile(profile)
                            }
                            backStack.removeAt(backStack.lastIndex)
                        }
                        Screen.PHONE -> PhoneScreen(app, actions)
                        Screen.SETTINGS -> SettingsScreen(app, settings)
                        Screen.DIAGNOSTICS -> DiagnosticsScreen(app, settings)
                    }
                }
            },
        )
    }
}

// --- Shared pieces -----------------------------------------------------------

/** Watch list with its scaffold: each item transforms as it reaches the edge of the screen. */
@Composable
fun WatchList(content: TransformingLazyColumnScope.(TransformationSpec) -> Unit) {
    val state = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()
    ScreenScaffold(
        scrollState = state,
        scrollIndicator = { if (!LocalScrollCaptureInProgress.current) ScrollIndicator(state) },
    ) { padding ->
        TransformingLazyColumn(state = state, contentPadding = padding) { content(spec) }
    }
}

private fun TransformingLazyColumnScope.header(spec: TransformationSpec, text: String) = item {
    ListHeader(
        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec).minimumVerticalContentPadding(ListHeaderDefaults.minimumTopListContentPadding),
        transformation = SurfaceTransformation(spec),
    ) { Text(text) }
}

private fun TransformingLazyColumnScope.note(spec: TransformationSpec, text: String) = item {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = WatchColors.inkMuted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

private fun TransformingLazyColumnScope.action(
    spec: TransformationSpec,
    label: String,
    secondary: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    primary: Boolean = false,
    onClick: () -> Unit,
) = item {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec).minimumVerticalContentPadding(ButtonDefaults.minimumVerticalListContentPadding),
        transformation = SurfaceTransformation(spec),
        colors = if (primary) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
        icon = icon?.let { { Icon(it, null) } },
        secondaryLabel = secondary?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
    ) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

@Composable
private fun Dot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).clip(CircleShape).background(color))
}

/** Pulsing recording dot (steady if the system asks for less motion or in ambient mode). */
@Composable
private fun RecDot(animate: Boolean) {
    val alpha = if (animate) {
        val t = rememberInfiniteTransition(label = "rec")
        t.animateFloat(1f, 0.4f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "rec").value
    } else 1f
    Dot(WatchColors.minium, Modifier.alpha(alpha))
}

private val HM = DateTimeFormatter.ofPattern("HH:mm")

private fun elapsed(res: Resources, ms: Long, seconds: Boolean): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    return if (seconds) "%d:%02d:%02d".format(h, m, s % 60) else res.getString(R.string.elapsed_hours, h.toInt(), m.toInt())
}

// --- Home --------------------------------------------------------------------

@Composable
private fun HomeScreen(app: EscribaWearApp, actions: WatchActions, open: (Screen) -> Unit) {
    val res = LocalResources.current
    val phase by RecordingState.phase.collectAsStateWithLifecycle()
    val live by RecordingState.live.collectAsStateWithLifecycle()
    val selected by app.profiles.selected.collectAsStateWithLifecycle()
    val peer by app.link.keys.peer.collectAsStateWithLifecycle()
    val lost by app.pairing.peerLost.collectAsStateWithLifecycle()
    val ambientManager = LocalAmbientModeManager.current
    val ambient = ambientManager?.currentAmbientMode is AmbientMode.Ambient

    // Pending fragments and battery: reading the disk on the main thread made the UI stutter.
    var pending by remember { mutableIntStateOf(0) }
    var battery by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) {
                val fragments = app.store.stats().fragments
                val level = actions.batteryNow()
                pending = fragments
                battery = level
            }
            delay(10_000)
        }
    }

    val recording = phase == RecordingState.Phase.CAPTURING
    val paused = phase == RecordingState.Phase.PAUSED
    val profileName = (if (phase == RecordingState.Phase.IDLE) selected?.name else live.profileName ?: selected?.name) ?: res.getString(R.string.app_name)
    val profileColor = WatchColors.profile(if (phase == RecordingState.Phase.IDLE) selected?.color else live.profileColor ?: selected?.color)

    WatchList { spec ->
        item {
            Column(
                Modifier.fillMaxWidth().transformedHeight(this, spec).padding(top = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Dot(profileColor)
                    Text(profileName, style = MaterialTheme.typography.titleMedium, color = WatchColors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                when {
                    recording -> {
                        ElapsedClock(live.startedAtMs, ambient)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            RecDot(animate = !ambient)
                            Text(
                                stringResource(if (live.inSpeech) R.string.hearing_voice else R.string.listening),
                                style = MaterialTheme.typography.bodySmall, color = WatchColors.inkMuted,
                            )
                        }
                    }
                    paused -> Text(
                        live.pausedUntil?.let { stringResource(R.string.paused_until, it) } ?: stringResource(R.string.paused),
                        style = MaterialTheme.typography.bodySmall, color = WatchColors.inkMuted, textAlign = TextAlign.Center,
                    )
                }
                // No button in ambient mode: only the essentials, in grey.
                if (!ambient) RecordButton(phase, actions.toggleRecording)
                if (!recording && !ambient) {
                    Text(stringResource(if (paused) R.string.stop else R.string.record), style = MaterialTheme.typography.labelMedium, color = WatchColors.inkMuted)
                }
                if (!ambient) {
                    Text(
                        listOfNotNull(
                            when {
                                peer == null -> stringResource(R.string.not_paired)
                                lost -> stringResource(R.string.pair_again_short)
                                pending > 0 -> pluralStringResource(R.plurals.pending_to_send, pending, pending)
                                else -> stringResource(R.string.all_sent)
                            },
                            battery?.let { stringResource(R.string.battery_pct, it.toInt()) },
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = WatchColors.inkMuted,
                    )
                }
            }
        }
        if (!ambient) {
            item {
                Button(
                    onClick = { open(Screen.PROFILES) },
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec).minimumVerticalContentPadding(ButtonDefaults.minimumVerticalListContentPadding),
                    transformation = SurfaceTransformation(spec),
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    icon = { Dot(profileColor, Modifier.size(12.dp)) },
                    secondaryLabel = { Text(selected?.name ?: stringResource(R.string.no_profiles), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                ) { Text(stringResource(R.string.profile)) }
            }
            val phoneState = res.getString(
                when {
                    peer == null -> R.string.not_paired
                    lost -> R.string.pair_again_short
                    else -> R.string.paired
                },
            )
            action(spec, res.getString(R.string.phone), phoneState, icon = WatchIcons.Phone) { open(Screen.PHONE) }
            action(spec, res.getString(R.string.settings), icon = WatchIcons.Tune) { open(Screen.SETTINGS) }
            action(spec, res.getString(R.string.diagnostics), icon = WatchIcons.Lines) { open(Screen.DIAGNOSTICS) }
        }
    }
}

/**
 * Recorded time. It's the only element that changes every second, so it has its own tick and
 * doesn't force the rest of the screen to recompose. In ambient mode, one tick a minute and no
 * seconds.
 */
@Composable
private fun ElapsedClock(startedAtMs: Long, ambient: Boolean) {
    val res = LocalResources.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LocalAmbientModeManager.current?.AmbientTickEffect { now = System.currentTimeMillis() }
    if (!ambient) {
        LaunchedEffect(startedAtMs) {
            while (true) {
                now = System.currentTimeMillis()
                delay(1_000 - now % 1_000)
            }
        }
    }
    Text(
        elapsed(res, now - startedAtMs, seconds = !ambient),
        style = TextStyle(fontFamily = WatchMono, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 34.sp),
        color = if (ambient) WatchColors.inkMuted else WatchColors.ink,
    )
}

/** Round 72 dp button: minium outline to record, filled with minium while recording. */
@Composable
private fun RecordButton(phase: RecordingState.Phase, onClick: () -> Unit) {
    val recording = phase == RecordingState.Phase.CAPTURING
    val paused = phase == RecordingState.Phase.PAUSED
    val ring = when {
        recording -> null
        paused -> WatchColors.ruleStrong
        else -> WatchColors.minium
    }
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.touchTargetAwareSize(72.dp).let { m -> ring?.let { m.border(2.dp, it, CircleShape) } ?: m },
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (recording) WatchColors.minium else WatchColors.paperRaised,
            contentColor = when {
                recording -> WatchColors.onMinium
                paused -> WatchColors.ink
                else -> WatchColors.minium
            },
        ),
    ) {
        Icon(
            if (recording || paused) WatchIcons.Stop else WatchIcons.Mic,
            contentDescription = stringResource(if (recording || paused) R.string.stop else R.string.record),
            modifier = Modifier.size(32.dp),
        )
    }
}

// --- Profiles ----------------------------------------------------------------

/**
 * Profile list. [pick] gets the chosen id, whether it's the current one and whether nothing is
 * being recorded: from the app it only selects; from the Tile ("More") it starts recording with it.
 */
@Composable
fun ProfilesScreen(app: EscribaWearApp, pick: (id: String, current: Boolean, idle: Boolean) -> Unit) {
    val res = LocalResources.current
    val list by app.profiles.list.collectAsStateWithLifecycle()
    val selected by app.profiles.selected.collectAsStateWithLifecycle()
    val phase by RecordingState.phase.collectAsStateWithLifecycle()
    val idle = phase == RecordingState.Phase.IDLE
    val all = list?.profiles.orEmpty()
    WatchList { spec ->
        header(spec, res.getString(if (idle) R.string.profile else R.string.switch_to))
        if (all.isEmpty()) note(spec, res.getString(R.string.no_profiles_hint))
        items(all, key = { it.id }) { p ->
            val current = p.id == selected?.id
            Button(
                onClick = { pick(p.id, current, idle) },
                modifier = Modifier.fillMaxWidth().transformedHeight(this, spec).minimumVerticalContentPadding(ButtonDefaults.minimumVerticalListContentPadding),
                transformation = SurfaceTransformation(spec),
                colors = if (current) ButtonDefaults.filledTonalButtonColors(containerColor = WatchColors.lapisSoft) else ButtonDefaults.filledTonalButtonColors(),
                icon = { Dot(WatchColors.profile(p.color), Modifier.size(12.dp)) },
                secondaryLabel = { Text(stringResource(if (p.continuous) R.string.mode_continuous else R.string.mode_on_demand)) },
            ) { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

// --- Phone -------------------------------------------------------------------

private fun Resources.waiting(wait: PairingWait): String = getString(
    when (wait) {
        PairingWait.PHONE -> R.string.pairing_wait_phone
        PairingWait.WATCH -> R.string.pairing_wait_watch
        PairingWait.CODE -> R.string.pairing_wait_code
    },
)

private fun Resources.failure(reason: PairingFailure): String = getString(
    when (reason) {
        PairingFailure.NO_NEARBY_PHONE -> R.string.pairing_failed_no_phone
        PairingFailure.NOT_DIRECTLY_CONNECTED -> R.string.pairing_failed_not_direct
        PairingFailure.COMMITMENT_MISMATCH -> R.string.pairing_failed_commitment
        PairingFailure.BAD_CONFIRMATION -> R.string.pairing_failed_confirmation
    },
)

@Composable
private fun PhoneScreen(app: EscribaWearApp, actions: WatchActions) {
    val res = LocalResources.current
    val peer by app.link.keys.peer.collectAsStateWithLifecycle()
    val pairing by app.pairing.state.collectAsStateWithLifecycle()
    val upload by app.uploader.stats.collectAsStateWithLifecycle()
    val lost by app.pairing.peerLost.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    fun at(ms: Long) = if (ms == 0L) "—" else Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(HM)

    WatchList { spec ->
        header(spec, res.getString(R.string.phone))
        when (val state = pairing) {
            is PairingState.Code -> {
                item {
                    Text(
                        state.code,
                        style = TextStyle(fontFamily = WatchMono, fontWeight = FontWeight.SemiBold, fontSize = 30.sp),
                        color = WatchColors.ink, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    )
                }
                note(spec, res.getString(when {
                    state.localConfirmed && !state.peerConfirmed -> R.string.pairing_confirm_on_phone
                    state.peerConfirmed && !state.localConfirmed -> R.string.pairing_phone_confirmed
                    else -> R.string.pairing_same_code
                }))
                if (!state.localConfirmed) action(spec, res.getString(R.string.pairing_matches), primary = true) { scope.launch { app.pairing.confirm() } }
                action(spec, res.getString(R.string.cancel)) { app.pairing.cancel() }
            }
            is PairingState.Waiting -> {
                note(spec, res.waiting(state.wait))
                action(spec, res.getString(R.string.cancel)) { app.pairing.cancel() }
            }
            else -> {
                (state as? PairingState.Failed)?.reason?.let { note(spec, res.failure(it)) }
                note(spec, peer?.let { res.getString(R.string.paired_with, it.name) } ?: res.getString(R.string.not_paired_hint))
                if (lost) note(spec, res.getString(R.string.peer_lost_hint))
                if (peer != null) {
                    note(
                        spec,
                        if (upload.lastSuccessMs == 0L) res.getString(R.string.nothing_sent)
                        else res.getString(R.string.sent_summary, upload.sentFragments.toInt(), (upload.sentBytes / 1024).toInt(), at(upload.lastSuccessMs)),
                    )
                    action(spec, res.getString(R.string.send_now), primary = true, onClick = actions.sendNow)
                }
                action(spec, res.getString(if (peer == null) R.string.pair else R.string.pair_again), primary = peer == null || lost) {
                    scope.launch {
                        runCatching { app.pairing.startAsWatch() }.onFailure { AppLog.e("link", "Couldn't start pairing", it) }
                    }
                }
                action(spec, res.getString(R.string.battery_exemption), res.getString(R.string.battery_exemption_hint), onClick = actions.requestBatteryExemption)
            }
        }
    }
}

// --- Settings ----------------------------------------------------------------

@Composable
private fun SettingsScreen(app: EscribaWearApp, settings: RecorderSettings) {
    val res = LocalResources.current
    val phase by RecordingState.phase.collectAsStateWithLifecycle()
    val list by app.profiles.list.collectAsStateWithLifecycle()
    val idle = phase == RecordingState.Phase.IDLE
    var source by remember { mutableStateOf(settings.source) }
    var mode by remember { mutableStateOf(settings.mode) }
    var vad by remember { mutableStateOf(settings.sensitivity) }

    WatchList { spec ->
        header(spec, res.getString(R.string.settings))
        action(spec, res.getString(R.string.microphone), res.getString(source.label), enabled = idle) {
            source = MicSource.entries[(source.ordinal + 1) % MicSource.entries.size]
            settings.source = source
        }
        if (list == null) {
            // Without profiles from the phone, mode and sensitivity are chosen here.
            action(spec, res.getString(R.string.mode), res.getString(mode.label), enabled = idle) {
                mode = RecordingMode.entries[(mode.ordinal + 1) % RecordingMode.entries.size]
                settings.mode = mode
            }
            action(spec, res.getString(R.string.sensitivity), res.getString(vad.label), enabled = idle) {
                vad = VadSensitivity.entries[(vad.ordinal + 1) % VadSensitivity.entries.size]
                settings.sensitivity = vad
            }
        }
        val override = settings.windowOverride
        note(
            spec,
            if (override != null) res.getString(R.string.continuous_schedule_test, override.toString())
            else res.getString(R.string.continuous_schedule, app.profiles.scheduleOr(settings.window).toString()),
        )
        if (!idle) note(spec, res.getString(R.string.stop_to_change))
    }
}

// --- Diagnostics -------------------------------------------------------------

@Composable
private fun DiagnosticsScreen(app: EscribaWearApp, settings: RecorderSettings) {
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val phase by RecordingState.phase.collectAsStateWithLifecycle()
    val live by RecordingState.live.collectAsStateWithLifecycle()
    val log by AppLog.events.entries.collectAsStateWithLifecycle()
    val idle = phase == RecordingState.Phase.IDLE

    WatchList { spec ->
        header(spec, res.getString(R.string.diagnostics))
        if (phase == RecordingState.Phase.CAPTURING) {
            note(spec, res.getString(R.string.live_stats, live.speechPct, live.fragments, live.levelDb, live.floorDb))
        }
        action(spec, res.getString(R.string.test_connection), res.getString(R.string.test_connection_hint)) {
            scope.launch {
                runCatching { app.link.pingNearby() }
                    .onSuccess { if (it == 0) AppLog.i("ui", "No nearby phone with Escriba") }
                    .onFailure { AppLog.e("ui", "Failed to send the ping", it) }
            }
        }
        action(spec, res.getString(R.string.request_profiles)) { scope.launch { app.profiles.requestFromPhone() } }
        action(spec, res.getString(R.string.test_schedule), res.getString(R.string.test_schedule_hint), enabled = idle) {
            val now = LocalTime.now().withSecond(0).withNano(0)
            settings.windowOverride = DailyWindow(now.plusMinutes(1), now.plusMinutes(3))
            AppLog.i("ui", "Test schedule: ${settings.windowOverride}")
        }
        if (settings.windowOverride != null) {
            action(spec, res.getString(R.string.normal_schedule), enabled = idle) {
                settings.windowOverride = null
                AppLog.i("ui", "Test schedule turned off")
            }
        }
        action(spec, res.getString(R.string.delete_pending), res.getString(R.string.delete_pending_hint), enabled = idle) {
            app.store.deleteAll()
            AppLog.i("ui", "Pending fragments deleted")
        }
        header(spec, res.getString(R.string.log))
        items(log.takeLast(30).reversed()) { entry ->
            Text(
                entry.toString(),
                style = TextStyle(fontFamily = WatchMono, fontSize = 10.sp, lineHeight = 13.sp),
                color = WatchColors.inkMuted,
                modifier = Modifier.fillMaxWidth().transformedHeight(this, spec).padding(horizontal = 6.dp),
            )
        }
    }
}
