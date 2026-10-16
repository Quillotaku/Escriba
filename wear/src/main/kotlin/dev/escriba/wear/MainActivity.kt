package dev.escriba.wear

import android.Manifest
import androidx.compose.ui.graphics.Color
import dev.escriba.wear.profiles.WatchProfiles
import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Alignment
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import dev.escriba.datalayer.PairingState
import dev.escriba.wear.transfer.UploadWorker
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import dev.escriba.datalayer.AppLog
import dev.escriba.shared.audio.VadSensitivity
import dev.escriba.shared.recording.DailyWindow
import dev.escriba.wear.recorder.BatteryLog
import dev.escriba.wear.recorder.MicSource
import dev.escriba.wear.recorder.RecorderService
import dev.escriba.wear.recorder.RecorderSettings
import dev.escriba.wear.recorder.RecordingMode
import dev.escriba.wear.recorder.RecordingState
import dev.escriba.wear.storage.FragmentStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime

/** Fase 2: control de la grabación, ajustes de prueba y diagnóstico. */
class MainActivity : ComponentActivity() {

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        AppLog.i("ui", "Permisos: " + result.entries.joinToString { "${it.key.substringAfterLast('.')}=${it.value}" })
    }

    override fun onStart() {
        super.onStart()
        (application as EscribaWearApp).link.startForegroundListening()
    }

    override fun onStop() {
        (application as EscribaWearApp).link.stopForegroundListening()
        super.onStop()
    }

    @SuppressLint("BatteryLife") // app personal instalada por ADB: necesita sobrevivir en segundo plano
    private fun requestBatteryExemption() {
        val power = getSystemService(PowerManager::class.java)
        if (power.isIgnoringBatteryOptimizations(packageName)) {
            AppLog.i("ui", "Escriba ya está fuera de la optimización de batería")
            return
        }
        runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }.onFailure { AppLog.e("ui", "El reloj no permite abrir ese ajuste", it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val missing = arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())

        val app = application as EscribaWearApp
        val settings = RecorderSettings(this)
        setContent {
            MaterialTheme {
                RecorderScreen(
                    settings = settings,
                    store = app.store,
                    toggleRecording = {
                        RecorderService.send(this, RecorderService.intent(this, RecorderService.ACTION_TOGGLE, "app"))
                    },
                    ping = {
                        runCatching { app.link.pingNearby() }
                            .onSuccess { if (it == 0) AppLog.i("ui", "No hay ningún móvil cercano con Escriba") }
                            .onFailure { AppLog.e("ui", "Fallo al enviar el ping", it) }
                    },
                    batteryNow = { BatteryLog.read(this)?.level },
                    profiles = app.profiles,
                    link = {
                        LinkSection(
                            app = app,
                            sendNow = { UploadWorker.kick(this@MainActivity, manual = true) },
                            exemptFromBatteryOptimization = ::requestBatteryExemption,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun RecorderScreen(
    settings: RecorderSettings,
    store: FragmentStore,
    toggleRecording: () -> Unit,
    ping: suspend () -> Unit,
    batteryNow: () -> Float?,
    profiles: WatchProfiles,
    link: ScalingLazyListScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val phase by RecordingState.phase.collectAsStateWithLifecycle()
    val live by RecordingState.live.collectAsStateWithLifecycle()
    val log by AppLog.events.entries.collectAsStateWithLifecycle()
    val listState = rememberScalingLazyListState()

    // Los ajustes solo se pueden cambiar con la grabación parada: el servicio los lee al empezar.
    val idle = phase == RecordingState.Phase.IDLE
    var mode by remember { mutableStateOf(settings.mode) }
    var source by remember { mutableStateOf(settings.source) }
    var vad by remember { mutableStateOf(settings.sensitivity) }

    var stats by remember { mutableStateOf(store.stats()) }
    var battery by remember { mutableStateOf(batteryNow()) }
    LaunchedEffect(Unit) {
        while (true) {
            stats = store.stats()
            battery = batteryNow()
            delay(5_000)
        }
    }

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 32.dp),
    ) {
        item {
            ListHeader {
                Text(
                    when (phase) {
                        RecordingState.Phase.IDLE -> "Escriba"
                        RecordingState.Phase.CAPTURING -> if (live.inSpeech) "● Voz" else "● Escuchando"
                        RecordingState.Phase.PAUSED -> "En pausa"
                    },
                )
            }
        }
        item {
            val lines = buildList {
                when (phase) {
                    RecordingState.Phase.CAPTURING -> {
                        add("Voz ${live.speechPct} % · ${live.fragments} fragmentos")
                        add("Nivel ${live.levelDb} dB · suelo ${live.floorDb} dB")
                        val start = live.batteryAtStart
                        val now = battery
                        val hours = (System.currentTimeMillis() - live.startedAtMs) / 3_600_000.0
                        if (start != null && now != null && hours > 0.05) {
                            add("Batería %.1f %%/h".format((start - now) / hours))
                        }
                    }
                    RecordingState.Phase.PAUSED -> add("Vuelve ${live.pausedUntil}")
                    RecordingState.Phase.IDLE -> Unit
                }
                add("Pendientes: ${stats.fragments} (${stats.bytes / 1024} KB)")
                battery?.let { add("Batería ${it.toInt()} %") }
            }
            Text(lines.joinToString("\n"), fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        item {
            val list by profiles.list.collectAsStateWithLifecycle()
            val selected by profiles.selected.collectAsStateWithLifecycle()
            val current = selected
            if (current == null) {
                Text(
                    if (list == null) "Sin perfiles: empareja el móvil y elige la bóveda allí." else "La bóveda no tiene perfiles válidos.",
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Button(
                    onClick = { profiles.selectNext() },
                    enabled = idle,
                    colors = ButtonDefaults.buttonColors(containerColor = parseColor(current.color)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("${current.name} · ${if (current.mode == "continuo") "continuo" else "bajo demanda"}", fontSize = 13.sp)
                }
            }
        }
        item {
            Button(onClick = toggleRecording, modifier = Modifier.fillMaxWidth()) {
                Text(if (idle) "Grabar" else "Parar")
            }
        }
        link()
        item { ListHeader { Text("Ajustes") } }
        if (profiles.list.value == null) {
            // Sin perfiles del móvil, el modo y el VAD se eligen aquí.
            item {
                SettingButton("Modo: ${mode.label}", enabled = idle) {
                    mode = RecordingMode.entries[(mode.ordinal + 1) % RecordingMode.entries.size]
                    settings.mode = mode
                }
            }
            item {
                SettingButton("VAD: ${vad.name.lowercase()}", enabled = idle) {
                    vad = VadSensitivity.entries[(vad.ordinal + 1) % VadSensitivity.entries.size]
                    settings.sensitivity = vad
                }
            }
        }
        item {
            SettingButton("Fuente: ${source.label}", enabled = idle) {
                source = MicSource.entries[(source.ordinal + 1) % MicSource.entries.size]
                settings.source = source
            }
        }
        item {
            val override = settings.windowOverride
            Text(
                "Horario continuo: ${override ?: profiles.scheduleOr(settings.window)}" + if (override != null) " (prueba)" else "",
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item { ListHeader { Text("Diagnóstico") } }
        item {
            SettingButton("Ping al móvil", enabled = true) { scope.launch { ping() } }
        }
        item {
            SettingButton("Pedir perfiles al móvil", enabled = true) { scope.launch { profiles.requestFromPhone() } }
        }
        item {
            SettingButton("Horario de prueba", enabled = idle) {
                // Franja que empieza dentro de 1 min y dura 2, para probar pausa y reanudación
                // (con un perfil en modo continuo).
                val now = LocalTime.now().withSecond(0).withNano(0)
                settings.windowOverride = DailyWindow(now.plusMinutes(1), now.plusMinutes(3))
                AppLog.i("ui", "Horario de prueba: ${settings.windowOverride}")
            }
        }
        item {
            SettingButton("Horario normal", enabled = idle) {
                settings.windowOverride = null
                AppLog.i("ui", "Horario de prueba desactivado")
            }
        }
        item {
            SettingButton("Borrar pendientes", enabled = idle) {
                store.deleteAll()
                stats = store.stats()
                AppLog.i("ui", "Fragmentos pendientes borrados")
            }
        }
        items(log.takeLast(40).reversed()) { entry ->
            Text(
                text = entry.toString(),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Emparejamiento y envío al móvil. */
private fun ScalingLazyListScope.LinkSection(
    app: EscribaWearApp,
    sendNow: () -> Unit,
    exemptFromBatteryOptimization: () -> Unit,
) {
    item { ListHeader { Text("Móvil") } }
    item {
        val peer by app.link.keys.peer.collectAsStateWithLifecycle()
        val pairing by app.pairing.state.collectAsStateWithLifecycle()
        val upload by app.uploader.stats.collectAsStateWithLifecycle()
        val scope = rememberCoroutineScope()
        val time = remember { DateTimeFormatter.ofPattern("HH:mm") }
        fun at(ms: Long) = if (ms == 0L) "—" else Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(time)

        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            when (val state = pairing) {
                is PairingState.Code -> {
                    Text("Código", fontSize = 12.sp)
                    Text(state.code, fontSize = 30.sp, fontFamily = FontFamily.Monospace)
                    Text(
                        when {
                            state.localConfirmed && !state.peerConfirmed -> "Confirma en el móvil"
                            state.peerConfirmed && !state.localConfirmed -> "El móvil ya confirmó"
                            else -> "¿Es el mismo que en el móvil?"
                        },
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                    )
                    if (!state.localConfirmed) {
                        Button(onClick = { scope.launch { app.pairing.confirm() } }, modifier = Modifier.fillMaxWidth()) { Text("Coincide") }
                    }
                    SettingButton("Cancelar", enabled = true) { app.pairing.cancel() }
                }
                is PairingState.Waiting -> {
                    Text(state.message, fontSize = 12.sp, textAlign = TextAlign.Center)
                    SettingButton("Cancelar", enabled = true) { app.pairing.cancel() }
                }
                else -> {
                    val failed = (state as? PairingState.Failed)?.reason
                    if (failed != null) Text(failed, fontSize = 11.sp, textAlign = TextAlign.Center)
                    Text(
                        peer?.let { "Emparejado con ${it.name}" } ?: "Sin emparejar",
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                    )
                    if (peer != null) {
                        Text(
                            "Enviados ${upload.sentFragments} (${upload.sentBytes / 1024} KB)\n" +
                                "Último envío ${at(upload.lastSuccessMs)}" +
                                (upload.lastOutcome?.let { " · ${it.name.lowercase()}" } ?: "") +
                                (if (upload.lastKbps > 0) "\n${upload.lastKbps} kbps" else ""),
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                        )
                        SettingButton("Enviar ahora", enabled = true, onClick = sendNow)
                    }
                    SettingButton(if (peer == null) "Emparejar" else "Volver a emparejar", enabled = true) {
                        scope.launch {
                            runCatching { app.pairing.startAsWatch() }
                                .onFailure { AppLog.e("enlace", "No se pudo iniciar el emparejamiento", it) }
                        }
                    }
                    SettingButton("Sin optimización de batería", enabled = true, onClick = exemptFromBatteryOptimization)
                }
            }
        }
    }
}

private fun parseColor(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.DarkGray)

@Composable
private fun SettingButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.filledTonalButtonColors(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(label, fontSize = 13.sp)
    }
}
