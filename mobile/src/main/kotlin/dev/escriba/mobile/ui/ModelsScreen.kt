package dev.escriba.mobile.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.escriba.datalayer.AppLog
import dev.escriba.mobile.EscribaApp
import dev.escriba.mobile.transcription.Benchmark
import dev.escriba.nativeml.WhisperEngine
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss")
private val DAY = DateTimeFormatter.ofPattern("d MMM HH:mm")

@Composable
fun ModelsScreen(app: EscribaApp) {
    val models by app.models.models.collectAsStateWithLifecycle()
    val sessions by app.db.sessions().observeSessions().collectAsStateWithLifecycle(initialValue = emptyList())
    val bench by app.benchmark.state.collectAsStateWithLifecycle()
    val withAudio = sessions.filter { it.isComplete && it.audioDeletedAtMs == null }

    var sessionId by remember { mutableStateOf<String?>(null) }
    var modelName by remember { mutableStateOf<String?>(null) }
    var threads by remember { mutableIntStateOf(WhisperEngine.defaultThreads()) }
    var pickSession by remember { mutableStateOf(false) }
    var pickModel by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf(true) }
    var fast by remember { mutableStateOf(false) }
    var llmName by remember { mutableStateOf<String?>(null) }
    var pickLlm by remember { mutableStateOf(false) }
    val folder by app.models.folder.collectAsStateWithLifecycle()

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            runCatching { app.models.chooseFolder(uri) }
                .onSuccess { AppLog.i("modelos", "Carpeta de modelos: ${app.models.folderName()} (${app.models.models.value.size} modelos)") }
                .onFailure { AppLog.e("modelos", "No se pudo usar esa carpeta", it) }
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Modelos", style = MaterialTheme.typography.titleLarge)
        Text(
            "Los modelos no van dentro de la app: déjalos en una carpeta (p. ej. Descargas) y elígela aquí. " +
                "Escriba los lee directamente desde ahí, sin copiarlos.",
            style = MaterialTheme.typography.bodySmall,
        )
        models.forEach { m ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(m.name, fontWeight = FontWeight.SemiBold)
                        Text("${m.sizeBytes / 1_048_576} MB", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Text("Carpeta: " + (folder?.let { app.models.folderName() } ?: "sin elegir"), fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { folderPicker.launch(null) }) { Text(if (folder == null) "Elegir carpeta de modelos" else "Cambiar carpeta") }
            if (folder != null) OutlinedButton(onClick = { app.models.refresh() }) { Text("Actualizar") }
        }

        Text("Prueba de rendimiento", style = MaterialTheme.typography.titleMedium)
        Box {
            OutlinedButton(onClick = { pickSession = true }) {
                Text(withAudio.firstOrNull { it.id == sessionId }?.let { s ->
                    Instant.ofEpochMilli(s.startMs).atZone(ZoneId.systemDefault()).format(DAY) + " · voz ${s.speechMs / 1000} s"
                } ?: "Elegir sesión")
            }
            DropdownMenu(expanded = pickSession, onDismissRequest = { pickSession = false }) {
                withAudio.forEach { s ->
                    DropdownMenuItem(
                        text = { Text(Instant.ofEpochMilli(s.startMs).atZone(ZoneId.systemDefault()).format(DAY) + " · ${s.profileId} · voz ${s.speechMs / 1000} s") },
                        onClick = { sessionId = s.id; pickSession = false },
                    )
                }
            }
        }
        Box {
            OutlinedButton(onClick = { pickModel = true }) { Text(modelName ?: "Elegir modelo") }
            DropdownMenu(expanded = pickModel, onDismissRequest = { pickModel = false }) {
                models.filter { it.kind == "whisper" }.forEach { m -> DropdownMenuItem(text = { Text(m.name) }, onClick = { modelName = m.name; pickModel = false }) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Hilos:", Modifier.padding(top = 8.dp))
            listOf(2, 4, 6, 8).forEach { n -> FilterChip(selected = threads == n, onClick = { threads = n }, label = { Text("$n") }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = flash, onClick = { flash = !flash }, label = { Text("Flash attention") })
            FilterChip(selected = fast, onClick = { fast = !fast }, label = { Text("Modo rápido") })
        }
        val running = bench is Benchmark.State.Running
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !running && sessionId != null && modelName != null, onClick = {
                app.benchmark.run(sessionId!!, modelName!!, threads, flash, fast)
            }) { Text("Transcribir") }
            if (running) OutlinedButton(onClick = { WhisperEngine.cancel() }) { Text("Cancelar") }
        }

        when (val s = bench) {
            is Benchmark.State.Running -> {
                Text(s.message)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            is Benchmark.State.Failed -> Text(s.message, color = MaterialTheme.colorScheme.error)
            is Benchmark.State.LlmDone -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val st = s.result.stats
                    val i = s.result.insights
                    Text(st.model, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Carga ${st.loadMs} ms · total ${st.totalMs / 1000} s · ${st.chunks} trozo(s)\n" +
                            "Lectura ${st.promptTokens} tokens a %.0f t/s · escritura ${st.generatedTokens} tokens a %.1f t/s".format(st.promptTps, st.generateTps),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(i.title, fontWeight = FontWeight.SemiBold)
                    Text(i.summary, fontSize = 13.sp)
                    if (i.decisions.isNotEmpty()) Text("Decisiones:\n" + i.decisions.joinToString("\n") { "• $it" }, fontSize = 13.sp)
                    if (i.tasks.isNotEmpty()) Text("Tareas:\n" + i.tasks.joinToString("\n") { t -> "☐ ${t.text}" + (t.owner?.let { " — $it" } ?: "") + (t.due?.let { " 📅 $it" } ?: "") }, fontSize = 13.sp)
                    Text(
                        "Personas: ${i.people.joinToString().ifEmpty { "—" }} · Proyectos: ${i.projects.joinToString().ifEmpty { "—" }} · " +
                            "Clientes: ${i.clients.joinToString().ifEmpty { "—" }}\nEtiquetas: ${i.tags.joinToString()} · trivial: ${i.trivial}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            is Benchmark.State.Done -> Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val st = s.result.stats
                    Text(st.model, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Audio ${st.audioMs / 1000} s · carga ${st.loadMs} ms · decodificación ${st.decodeMs} ms\n" +
                            "Transcripción ${st.transcribeMs / 1000} s con ${st.threads} hilos${if (st.flashAttn) " + flash" else ""}${if (st.fast) " + rápido" else ""} → ×%.2f del tiempo real\n".format(st.realTimeFactor) +
                            "1 h de voz tardaría ≈ ${(st.realTimeFactor * 60).toInt()} min · frases ${st.segmentsKept}/${st.segmentsRaw} tras el filtro",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(s.timings, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
                    s.result.lines.forEach { l ->
                        Text(
                            "[${Instant.ofEpochMilli(l.wallStartMs).atZone(ZoneId.systemDefault()).format(CLOCK)}] ${l.text}",
                            fontSize = 13.sp,
                        )
                    }
                }
            }
            Benchmark.State.Idle -> Unit
        }

        Text("Prueba del LLM (resumen, tareas…)", style = MaterialTheme.typography.titleMedium)
        Text("Usa la transcripción ya guardada de la sesión elegida arriba y el perfil con que se procesó.", style = MaterialTheme.typography.bodySmall)
        Box {
            OutlinedButton(onClick = { pickLlm = true }) { Text(llmName ?: "Elegir LLM") }
            DropdownMenu(expanded = pickLlm, onDismissRequest = { pickLlm = false }) {
                models.filter { it.kind == "llm" }.forEach { m -> DropdownMenuItem(text = { Text(m.name) }, onClick = { llmName = m.name; pickLlm = false }) }
            }
        }
        Button(enabled = !running && sessionId != null && llmName != null, onClick = { app.benchmark.runLlm(sessionId!!, llmName!!) }) { Text("Resumir") }

        Text("CPU: " + remember { runCatching { WhisperEngine.systemInfo() }.getOrDefault("?") }, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
    }
}
