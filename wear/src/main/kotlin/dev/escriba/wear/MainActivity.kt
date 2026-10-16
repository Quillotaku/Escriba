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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import dev.escriba.datalayer.AppLog
import dev.escriba.wear.recorder.RecorderService
import dev.escriba.wear.recorder.RecordingState
import kotlinx.coroutines.launch

/** Fase 1: pantalla de spikes del reloj. */
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

        val link = (application as EscribaWearApp).link
        setContent {
            MaterialTheme {
                SpikeScreen(
                    toggleRecording = {
                        startForegroundService(RecorderService.intent(this, RecorderService.ACTION_TOGGLE, "app"))
                    },
                    ping = {
                        runCatching { link.pingNearby() }
                            .onSuccess { if (it == 0) AppLog.i("ui", "No hay ningún móvil cercano con Escriba") }
                            .onFailure { AppLog.e("ui", "Fallo al enviar el ping", it) }
                    },
                )
            }
        }
    }
}

@Composable
private fun SpikeScreen(toggleRecording: () -> Unit, ping: suspend () -> Unit) {
    val scope = rememberCoroutineScope()
    val active by RecordingState.active.collectAsStateWithLifecycle()
    val log by AppLog.events.entries.collectAsStateWithLifecycle()
    val listState = rememberScalingLazyListState()

    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 32.dp),
    ) {
        item { ListHeader { Text(if (active != null) "● Grabando" else "Escriba") } }
        item {
            Button(onClick = toggleRecording, modifier = Modifier.fillMaxWidth()) {
                Text(if (active != null) "Parar" else "Grabar")
            }
        }
        item {
            Button(onClick = { scope.launch { ping() } }, modifier = Modifier.fillMaxWidth()) {
                Text("Ping al móvil")
            }
        }
        item { ListHeader { Text("Registro") } }
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
