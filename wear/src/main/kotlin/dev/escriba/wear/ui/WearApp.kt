package dev.escriba.wear.ui

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
import androidx.compose.ui.platform.LocalScrollCaptureInProgress
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
import dev.escriba.datalayer.PairingState
import dev.escriba.shared.audio.VadSensitivity
import dev.escriba.shared.recording.DailyWindow
import dev.escriba.wear.EscribaWearApp
import dev.escriba.wear.recorder.MicSource
import dev.escriba.wear.recorder.RecorderService
import dev.escriba.wear.recorder.RecorderSettings
import dev.escriba.wear.recorder.RecordingMode
import dev.escriba.wear.recorder.RecordingState
import dev.escriba.wear.transfer.UploadWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Pantallas del reloj. La principal graba; lo demás está debajo, girando la corona. */
private enum class Screen { HOME, PROFILES, PHONE, SETTINGS, DIAGNOSTICS }

/** Lo que solo puede hacer la actividad. */
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

// --- Piezas comunes ----------------------------------------------------------

/** Lista del reloj con su scaffold: cada elemento se transforma al llegar al borde de la pantalla. */
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

/** Punto de grabación que late (fijo si el sistema pide menos movimiento o en modo ambiente). */
@Composable
private fun RecDot(animate: Boolean) {
    val alpha = if (animate) {
        val t = rememberInfiniteTransition(label = "rec")
        t.animateFloat(1f, 0.4f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "rec").value
    } else 1f
    Dot(WatchColors.minium, Modifier.alpha(alpha))
}

private val HM = DateTimeFormatter.ofPattern("HH:mm")

private fun elapsed(ms: Long, seconds: Boolean): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    return if (seconds) "%d:%02d:%02d".format(h, m, s % 60) else "%d h %02d".format(h, m)
}

// --- Inicio ------------------------------------------------------------------

@Composable
private fun HomeScreen(app: EscribaWearApp, actions: WatchActions, open: (Screen) -> Unit) {
    val phase by RecordingState.phase.collectAsStateWithLifecycle()
    val live by RecordingState.live.collectAsStateWithLifecycle()
    val selected by app.profiles.selected.collectAsStateWithLifecycle()
    val peer by app.link.keys.peer.collectAsStateWithLifecycle()
    val ambientManager = LocalAmbientModeManager.current
    val ambient = ambientManager?.currentAmbientMode is AmbientMode.Ambient

    // Pendientes y batería: leer el disco en el hilo principal hacía ir a tirones la interfaz.
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
    val profileName = (if (phase == RecordingState.Phase.IDLE) selected?.name else live.profileName ?: selected?.name) ?: "Escriba"
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
                            Text(if (live.inSpeech) "Oyendo voz" else "Escuchando", style = MaterialTheme.typography.bodySmall, color = WatchColors.inkMuted)
                        }
                    }
                    paused -> Text(
                        "En pausa" + (live.pausedUntil?.let { " · vuelve a las $it" } ?: ""),
                        style = MaterialTheme.typography.bodySmall, color = WatchColors.inkMuted, textAlign = TextAlign.Center,
                    )
                }
                // En modo ambiente no hay botón: solo lo esencial, en gris.
                if (!ambient) RecordButton(phase, actions.toggleRecording)
                if (!recording && !ambient) {
                    Text(if (paused) "Parar" else "Grabar", style = MaterialTheme.typography.labelMedium, color = WatchColors.inkMuted)
                }
                if (!ambient) {
                    Text(
                        listOfNotNull(
                            if (peer == null) "Sin emparejar" else if (pending > 0) "$pending por enviar" else "Todo enviado",
                            battery?.let { "${it.toInt()} %" },
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
                    secondaryLabel = { Text(selected?.name ?: "Sin perfiles", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                ) { Text("Perfil") }
            }
            action(spec, "Móvil", if (peer == null) "Sin emparejar" else "Emparejado", icon = WatchIcons.Phone) { open(Screen.PHONE) }
            action(spec, "Ajustes", icon = WatchIcons.Tune) { open(Screen.SETTINGS) }
            action(spec, "Diagnóstico", icon = WatchIcons.Lines) { open(Screen.DIAGNOSTICS) }
        }
    }
}

/**
 * Tiempo grabado. Es el único elemento que cambia cada segundo, así que lleva su propio tic y
 * no obliga a recomponer el resto de la pantalla. En ambiente, un tic por minuto y sin segundos.
 */
@Composable
private fun ElapsedClock(startedAtMs: Long, ambient: Boolean) {
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
        elapsed(now - startedAtMs, seconds = !ambient),
        style = TextStyle(fontFamily = WatchMono, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 34.sp),
        color = if (ambient) WatchColors.inkMuted else WatchColors.ink,
    )
}

/** Botón redondo de 72 dp: contorno minio para grabar, relleno minio mientras graba. */
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
            contentDescription = if (recording || paused) "Parar" else "Grabar",
            modifier = Modifier.size(32.dp),
        )
    }
}

// --- Perfiles ----------------------------------------------------------------

/**
 * Lista de perfiles. [pick] recibe el id elegido, si es el actual y si no se está grabando: desde
 * la app solo se selecciona; desde el Tile («Más…») se empieza a grabar con él.
 */
@Composable
fun ProfilesScreen(app: EscribaWearApp, pick: (id: String, current: Boolean, idle: Boolean) -> Unit) {
    val list by app.profiles.list.collectAsStateWithLifecycle()
    val selected by app.profiles.selected.collectAsStateWithLifecycle()
    val phase by RecordingState.phase.collectAsStateWithLifecycle()
    val idle = phase == RecordingState.Phase.IDLE
    val all = list?.profiles.orEmpty()
    WatchList { spec ->
        header(spec, if (idle) "Perfil" else "Cambiar a")
        if (all.isEmpty()) note(spec, "Sin perfiles: empareja el móvil y elige la bóveda allí.")
        items(all, key = { it.id }) { p ->
            val current = p.id == selected?.id
            Button(
                onClick = { pick(p.id, current, idle) },
                modifier = Modifier.fillMaxWidth().transformedHeight(this, spec).minimumVerticalContentPadding(ButtonDefaults.minimumVerticalListContentPadding),
                transformation = SurfaceTransformation(spec),
                colors = if (current) ButtonDefaults.filledTonalButtonColors(containerColor = WatchColors.lapisSoft) else ButtonDefaults.filledTonalButtonColors(),
                icon = { Dot(WatchColors.profile(p.color), Modifier.size(12.dp)) },
                secondaryLabel = { Text(if (p.mode == "continuo") "Continuo" else "Bajo demanda") },
            ) { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

// --- Móvil -------------------------------------------------------------------

@Composable
private fun PhoneScreen(app: EscribaWearApp, actions: WatchActions) {
    val peer by app.link.keys.peer.collectAsStateWithLifecycle()
    val pairing by app.pairing.state.collectAsStateWithLifecycle()
    val upload by app.uploader.stats.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    fun at(ms: Long) = if (ms == 0L) "—" else Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(HM)

    WatchList { spec ->
        header(spec, "Móvil")
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
                note(spec, when {
                    state.localConfirmed && !state.peerConfirmed -> "Confirma en el móvil"
                    state.peerConfirmed && !state.localConfirmed -> "El móvil ya confirmó"
                    else -> "¿Es el mismo código que en el móvil?"
                })
                if (!state.localConfirmed) action(spec, "Coincide", primary = true) { scope.launch { app.pairing.confirm() } }
                action(spec, "Cancelar") { app.pairing.cancel() }
            }
            is PairingState.Waiting -> {
                note(spec, state.message)
                action(spec, "Cancelar") { app.pairing.cancel() }
            }
            else -> {
                (state as? PairingState.Failed)?.reason?.let { note(spec, it) }
                note(spec, peer?.let { "Emparejado con ${it.name}" } ?: "Sin emparejar: el reloj no envía nada.")
                if (peer != null) {
                    note(
                        spec,
                        if (upload.lastSuccessMs == 0L) "Nada enviado desde que se abrió Escriba"
                        else "Enviados ${upload.sentFragments} (${upload.sentBytes / 1024} KB) · el último a las ${at(upload.lastSuccessMs)}",
                    )
                    action(spec, "Enviar ahora", primary = true, onClick = actions.sendNow)
                }
                action(spec, if (peer == null) "Emparejar" else "Volver a emparejar", primary = peer == null) {
                    scope.launch {
                        runCatching { app.pairing.startAsWatch() }.onFailure { AppLog.e("enlace", "No se pudo iniciar el emparejamiento", it) }
                    }
                }
                action(spec, "Sin optimización de batería", "Para que el sistema no corte los envíos", onClick = actions.requestBatteryExemption)
            }
        }
    }
}

// --- Ajustes -----------------------------------------------------------------

@Composable
private fun SettingsScreen(app: EscribaWearApp, settings: RecorderSettings) {
    val phase by RecordingState.phase.collectAsStateWithLifecycle()
    val list by app.profiles.list.collectAsStateWithLifecycle()
    val idle = phase == RecordingState.Phase.IDLE
    var source by remember { mutableStateOf(settings.source) }
    var mode by remember { mutableStateOf(settings.mode) }
    var vad by remember { mutableStateOf(settings.sensitivity) }

    WatchList { spec ->
        header(spec, "Ajustes")
        action(spec, "Micrófono", source.label, enabled = idle) {
            source = MicSource.entries[(source.ordinal + 1) % MicSource.entries.size]
            settings.source = source
        }
        if (list == null) {
            // Sin perfiles del móvil, el modo y la sensibilidad se eligen aquí.
            action(spec, "Modo", mode.label, enabled = idle) {
                mode = RecordingMode.entries[(mode.ordinal + 1) % RecordingMode.entries.size]
                settings.mode = mode
            }
            action(spec, "Sensibilidad", vad.name.lowercase().replaceFirstChar { it.uppercase() }, enabled = idle) {
                vad = VadSensitivity.entries[(vad.ordinal + 1) % VadSensitivity.entries.size]
                settings.sensitivity = vad
            }
        }
        val override = settings.windowOverride
        note(spec, "Horario continuo: ${override ?: app.profiles.scheduleOr(settings.window)}" + if (override != null) " (prueba)" else "")
        if (!idle) note(spec, "Para cambiar los ajustes, para antes la grabación.")
    }
}

// --- Diagnóstico -------------------------------------------------------------

@Composable
private fun DiagnosticsScreen(app: EscribaWearApp, settings: RecorderSettings) {
    val scope = rememberCoroutineScope()
    val phase by RecordingState.phase.collectAsStateWithLifecycle()
    val live by RecordingState.live.collectAsStateWithLifecycle()
    val log by AppLog.events.entries.collectAsStateWithLifecycle()
    val idle = phase == RecordingState.Phase.IDLE

    WatchList { spec ->
        header(spec, "Diagnóstico")
        if (phase == RecordingState.Phase.CAPTURING) {
            note(spec, "Voz ${live.speechPct} % · ${live.fragments} fragmentos\nNivel ${live.levelDb} dB · suelo ${live.floorDb} dB")
        }
        action(spec, "Probar conexión", "Ping al móvil") {
            scope.launch {
                runCatching { app.link.pingNearby() }
                    .onSuccess { if (it == 0) AppLog.i("ui", "No hay ningún móvil cercano con Escriba") }
                    .onFailure { AppLog.e("ui", "Fallo al enviar el ping", it) }
            }
        }
        action(spec, "Pedir perfiles al móvil") { scope.launch { app.profiles.requestFromPhone() } }
        action(spec, "Horario de prueba", "Empieza en 1 min y dura 2", enabled = idle) {
            val now = LocalTime.now().withSecond(0).withNano(0)
            settings.windowOverride = DailyWindow(now.plusMinutes(1), now.plusMinutes(3))
            AppLog.i("ui", "Horario de prueba: ${settings.windowOverride}")
        }
        if (settings.windowOverride != null) {
            action(spec, "Horario normal", enabled = idle) {
                settings.windowOverride = null
                AppLog.i("ui", "Horario de prueba desactivado")
            }
        }
        action(spec, "Borrar pendientes", "Audio aún no enviado", enabled = idle) {
            app.store.deleteAll()
            AppLog.i("ui", "Fragmentos pendientes borrados")
        }
        header(spec, "Registro")
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
