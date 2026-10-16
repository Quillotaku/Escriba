package dev.escriba.wear

import android.Manifest
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
                        startForegroundService(RecorderService.intent(this, RecorderService.ACTION_TOGGLE, "app"))
                    },
                    ping = {
                        runCatching { app.link.pingNearby() }
                            .onSuccess { if (it == 0) AppLog.i("ui", "No hay ningún móvil cercano con Escriba") }
                            .onFailure { AppLog.e("ui", "Fallo al enviar el ping", it) }
                    },
                    batteryNow = { BatteryLog.read(this)?.level },
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
            Button(onClick = toggleRecording, modifier = Modifier.fillMaxWidth()) {
                Text(if (idle) "Grabar" else "Parar")
            }
        }
        item { ListHeader { Text("Ajustes") } }
        item {
            SettingButton("Modo: ${mode.label}", enabled = idle) {
                mode = RecordingMode.entries[(mode.ordinal + 1) % RecordingMode.entries.size]
                settings.mode = mode
            }
        }
        item {
            SettingButton("Fuente: ${source.label}", enabled = idle) {
                source = MicSource.entries[(source.ordinal + 1) % MicSource.entries.size]
                settings.source = source
            }
        }
        item {
            SettingButton("VAD: ${vad.name.lowercase()}", enabled = idle) {
                vad = VadSensitivity.entries[(vad.ordinal + 1) % VadSensitivity.entries.size]
                settings.sensitivity = vad
            }
        }
        item {
            Text("Horario continuo: ${settings.window}", fontSize = 11.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        item { ListHeader { Text("Diagnóstico") } }
        item {
            SettingButton("Ping al móvil", enabled = true) { scope.launch { ping() } }
        }
        item {
            SettingButton("Horario de prueba", enabled = idle) {
                // Franja que empieza dentro de 1 min y dura 2: sirve para probar pausa y reanudación.
                val now = LocalTime.now().withSecond(0).withNano(0)
                settings.window = DailyWindow(now.plusMinutes(1), now.plusMinutes(3))
                mode = RecordingMode.CONTINUO
                settings.mode = mode
                AppLog.i("ui", "Horario de prueba: ${settings.window}, modo continuo")
            }
        }
        item {
            SettingButton("Horario normal", enabled = idle) {
                settings.window = DailyWindow.DEFAULT
                AppLog.i("ui", "Horario restaurado: ${settings.window}")
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
