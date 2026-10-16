package dev.escriba.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import dev.escriba.wear.recorder.RecorderService
import dev.escriba.wear.recorder.RecordingState

/**
 * Lista completa de perfiles («Más…» en el Tile). Elegir uno empieza a grabar con él o, si ya
 * se está grabando, cambia de perfil (cierra la sesión y abre otra).
 */
class ProfilePickerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val profiles = (application as EscribaWearApp).profiles
        setContent {
            MaterialTheme {
                val list by profiles.list.collectAsStateWithLifecycle()
                val selected by profiles.selected.collectAsStateWithLifecycle()
                val phase by RecordingState.phase.collectAsStateWithLifecycle()
                ScalingLazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = rememberScalingLazyListState(),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 32.dp),
                ) {
                    item { ListHeader { Text(if (phase == RecordingState.Phase.IDLE) "Grabar con…" else "Cambiar a…") } }
                    val all = list?.profiles.orEmpty()
                    if (all.isEmpty()) {
                        item { Text("Sin perfiles todavía", fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
                    }
                    items(all, key = { it.id }) { p ->
                        val current = phase != RecordingState.Phase.IDLE && p.id == selected?.id
                        Button(
                            onClick = {
                                if (!current) {
                                    runCatching { RecorderService.send(this@ProfilePickerActivity, RecorderService.switchIntent(this@ProfilePickerActivity, p.id, "lista")) }
                                        .onFailure { AppLog.e("ui", "No se pudo iniciar la grabación", it) }
                                }
                                finish()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = parse(p.color)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text((if (current) "■ " else "") + p.name + if (p.mode == "continuo") " · continuo" else "", fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }

    private fun parse(hex: String): Color =
        runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.DarkGray)
}
