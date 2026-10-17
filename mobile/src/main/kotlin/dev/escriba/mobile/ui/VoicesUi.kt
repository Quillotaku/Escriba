package dev.escriba.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.escriba.datalayer.AppLog
import dev.escriba.mobile.EscribaApp
import dev.escriba.mobile.processing.ProcessWorker
import dev.escriba.mobile.processing.SessionProcessor
import kotlinx.coroutines.launch

/**
 * Hablantes de una sesión: cuánto habló cada uno y unas frases suyas para reconocerlo. Poner
 * nombre a un hablante registra su voz (solo en el móvil) y reprocesa la sesión con ese nombre.
 */
@Composable
fun SessionVoicesDialog(app: EscribaApp, sessionId: String, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    var speakers by remember { mutableStateOf<List<SessionProcessor.SessionSpeaker>?>(null) }
    val names = remember { mutableStateMapOf<Int, String>() }
    var error by remember { mutableStateOf<String?>(null) }
    var detecting by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }

    fun detect(redo: Boolean) {
        detecting = true
        error = null
        // En el ámbito de la app: sigue aunque se cierre el diálogo.
        app.scope.launch {
            runCatching { if (redo) app.processor.redoDiarization(sessionId) else app.processor.ensureDiarization(sessionId) }
                .onFailure { AppLog.e("voces", "No se pudieron separar los hablantes", it); error = it.message }
            detecting = false
            reload++
        }
    }

    LaunchedEffect(sessionId, reload) {
        speakers = runCatching { app.processor.speakers(sessionId) }.getOrElse { error = it.message; emptyList() }
    }

    AlertDialog(
        onDismissRequest = close,
        title = { Text("Voces de la sesión") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Pon nombre a quien reconozcas («Yo», «Ana López»…). Su huella de voz se guarda solo en este móvil " +
                        "y sirve para reconocerle en las siguientes sesiones.",
                    style = MaterialTheme.typography.bodySmall,
                )
                val list = speakers
                if (!list.isNullOrEmpty() && !detecting) {
                    OutlinedButton(onClick = { detect(true) }) { Text("Volver a detectar (${list.size} hablantes)") }
                }
                when {
                    list == null -> Text("Cargando…")
                    detecting -> {
                        Text("Separando hablantes…")
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    list.isEmpty() -> {
                        Text("Aún no se han separado los hablantes de esta sesión.")
                        Button(onClick = { detect(false) }) { Text("Detectar hablantes") }
                    }
                    else -> list.forEach { s ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("${s.label} · ${s.speechMs / 1000} s de voz", fontWeight = FontWeight.SemiBold)
                                s.samples.forEach { Text("«$it»", style = MaterialTheme.typography.bodySmall) }
                                if (s.hasEmbedding) {
                                    OutlinedTextField(
                                        value = names[s.cluster] ?: "",
                                        onValueChange = { names[s.cluster] = it },
                                        label = { Text("Nombre") },
                                        singleLine = true,
                                    )
                                } else {
                                    Text("Muy poca voz para registrarla.", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = names.values.any { it.isNotBlank() }, onClick = {
                scope.launch {
                    runCatching {
                        names.filterValues { it.isNotBlank() }.forEach { (cluster, name) -> app.processor.enrollSpeaker(sessionId, cluster, name) }
                    }.onSuccess {
                        ProcessWorker.enqueue(app, sessionId, now = true)
                        close()
                    }.onFailure {
                        AppLog.e("voces", "No se pudo registrar la voz", it)
                        error = it.message
                    }
                }
            }) { Text("Guardar y reprocesar") }
        },
        dismissButton = { TextButton(onClick = close) { Text("Cerrar") } },
    )
}

/** Voces registradas, con la opción de borrarlas. */
@Composable
fun EnrolledVoicesCard(app: EscribaApp) {
    val voices by app.db.sessions().observeVoices().collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var toDelete by remember { mutableStateOf<String?>(null) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Voces registradas", fontWeight = FontWeight.SemiBold)
            Text("Solo en este móvil: nunca van a la bóveda ni al reloj.", style = MaterialTheme.typography.bodySmall)
            if (voices.isEmpty()) Text("Ninguna todavía. Ponles nombre desde «Voces» en una sesión.", style = MaterialTheme.typography.bodySmall)
            voices.forEach { v ->
                Row {
                    Text("${v.name} · ${v.samples} ${if (v.samples == 1) "sesión" else "sesiones"}", Modifier.weight(1f).padding(top = 12.dp))
                    TextButton(onClick = { toDelete = v.name }) { Text("Borrar") }
                }
            }
        }
    }

    toDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("¿Borrar la voz «$name»?") },
            text = { Text("Se borra su huella de voz. Las notas ya escritas no cambian.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { app.voices.delete(name) }
                    toDelete = null
                }) { Text("Borrar") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancelar") } },
        )
    }
}
