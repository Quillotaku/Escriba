package dev.escriba.mobile.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.escriba.mobile.R
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.escriba.datalayer.AppLog
import dev.escriba.mobile.EscribaApp
import dev.escriba.mobile.transcription.Benchmark
import dev.escriba.mobile.transcription.ModelEntry
import dev.escriba.mobile.transcription.ParakeetAsr
import dev.escriba.mobile.ui.components.EsCard
import dev.escriba.mobile.ui.components.EscribaIcons
import dev.escriba.mobile.ui.components.Overline
import dev.escriba.mobile.ui.components.SettingRow
import dev.escriba.mobile.ui.components.StatusChip
import dev.escriba.mobile.ui.components.Tone
import dev.escriba.mobile.ui.theme.Escriba
import dev.escriba.mobile.ui.theme.Space
import dev.escriba.mobile.ui.today.APP_LOCALE
import dev.escriba.mobile.ui.today.local
import dev.escriba.mobile.voices.Diarizer
import dev.escriba.nativeml.WhisperEngine
import java.time.format.DateTimeFormatter

private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss")
private val DAY = DateTimeFormatter.ofPattern("MMM d · HH:mm", APP_LOCALE)

/** A model as a person thinks of it: Parakeet is four files but one model. */
private data class ModelGroup(val name: String, val files: List<ModelEntry>, val inUse: Boolean) {
    val sizeMb: Long get() = files.sumOf { it.sizeBytes } / 1_048_576
}

@Composable
fun ModelsPage(app: EscribaApp, padding: PaddingValues, openBenchmark: () -> Unit) {
    val models by app.models.models.collectAsStateWithLifecycle()
    val folder by app.models.folder.collectAsStateWithLifecycle()
    val set by app.profiles.set.collectAsStateWithLifecycle()
    val c = Escriba.colors
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            runCatching { app.models.chooseFolder(uri) }
                .onSuccess { AppLog.i("models", "Models folder: ${app.models.folderName()} (${app.models.models.value.size} models)") }
                .onFailure { AppLog.e("models", "Couldn't use that folder", it) }
        }
    }
    val profiles = set?.profiles?.values.orEmpty()
    val whisperInUse = profiles.filter { it.engine != "parakeet" }.map { it.whisper }.toSet()
    val llmInUse = profiles.map { it.llm }.toSet()
    fun used(file: String, names: Set<String>) = names.any { n -> file.contains(n, ignoreCase = true) }

    val sections = listOf(
        stringResource(R.string.dash_transcription) to (
            models.filter { it.kind == "whisper" }.map { ModelGroup(it.name, listOf(it), used(it.name, whisperInUse)) } +
                models.filter { it.kind == "parakeet" }.takeIf { it.isNotEmpty() }
                    ?.let { listOf(ModelGroup("Parakeet TDT v3", it, profiles.any { p -> p.engine == "parakeet" })) }.orEmpty()
            ),
        stringResource(R.string.summary) to models.filter { it.kind == "llm" }.map { ModelGroup(it.name, listOf(it), used(it.name, llmInUse)) },
        stringResource(R.string.voices) to models.filter { it.kind == "voice" }.map { m ->
            val label = when (m.name) {
                Diarizer.SEGMENTATION_MODEL -> stringResource(R.string.model_segmentation)
                Diarizer.EMBEDDING_MODEL -> stringResource(R.string.model_embedding)
                else -> m.name
            }
            ModelGroup(label, listOf(m), m.name == Diarizer.SEGMENTATION_MODEL || m.name == Diarizer.EMBEDDING_MODEL)
        },
    )

    Page(padding) {
        Overline(stringResource(R.string.models_folder))
        EsCard {
            if (folder == null) {
                Text(stringResource(R.string.models_folder_hint),
                    style = MaterialTheme.typography.bodyLarge, color = c.ink)
                Button(onClick = { folderPicker.launch(null) }) { Text(stringResource(R.string.choose_folder)) }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(app.models.folderName() ?: "", style = MaterialTheme.typography.titleSmall, color = c.ink, modifier = Modifier.weight(1f))
                    TextButton(onClick = { app.models.refresh() }) { Text(stringResource(R.string.refresh)) }
                    TextButton(onClick = { folderPicker.launch(null) }) { Text(stringResource(R.string.change)) }
                }
            }
        }

        sections.filter { it.second.isNotEmpty() }.forEach { (title, groups) ->
            Overline(title, Modifier.padding(top = Space.s3))
            EsCard {
                groups.forEachIndexed { i, g ->
                    if (i > 0) HorizontalDivider(color = c.rule)
                    Row(Modifier.fillMaxWidth().padding(vertical = Space.s1), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(g.name.removeSuffix(".gguf").removeSuffix(".bin").removeSuffix(".onnx"), style = MaterialTheme.typography.titleSmall, color = c.ink)
                            Text(
                                "${g.sizeMb} MB" + if (g.files.size > 1) " · " + pluralStringResource(R.plurals.files_count, g.files.size, g.files.size) else "",
                                style = MaterialTheme.typography.bodyMedium, color = c.inkMuted,
                            )
                        }
                        if (g.inUse) StatusChip(stringResource(R.string.in_use), Tone.DONE)
                    }
                }
            }
        }
        Text(stringResource(R.string.models_choice_hint), style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)

        Overline(stringResource(R.string.check), Modifier.padding(top = Space.s3))
        EsCard {
            SettingRow(EscribaIcons.Waveform, stringResource(R.string.settings_benchmark), stringResource(R.string.benchmark_hint), onClick = openBenchmark)
        }
    }
}

// --- Benchmarks --------------------------------------------------------------

@Composable
fun BenchmarkPage(app: EscribaApp, padding: PaddingValues) {
    val models by app.models.models.collectAsStateWithLifecycle()
    val sessions by app.db.sessions().observeSessions().collectAsStateWithLifecycle(emptyList())
    val bench by app.benchmark.state.collectAsStateWithLifecycle()
    val withAudio = sessions.filter { it.isComplete && it.audioDeletedAtMs == null }
    val c = Escriba.colors

    var sessionId by remember { mutableStateOf<String?>(null) }
    var asr by remember { mutableStateOf<String?>(null) }
    var llm by remember { mutableStateOf<String?>(null) }
    var threads by remember { mutableIntStateOf(WhisperEngine.defaultThreads()) }
    var flash by remember { mutableStateOf(true) }
    var fast by remember { mutableStateOf(false) }
    val running = bench is Benchmark.State.Running
    val chipColors = FilterChipDefaults.filterChipColors(selectedContainerColor = c.lapisSoft, selectedLabelColor = c.ink)

    Page(padding) {
        Text(stringResource(R.string.benchmark_intro), style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)

        Overline(stringResource(R.string.benchmark_step_session))
        EsCard {
            Picker(
                label = withAudio.firstOrNull { it.id == sessionId }?.let { stringResource(R.string.benchmark_session, it.startMs.local().format(DAY), it.speechMs / 1000) }
                    ?: stringResource(R.string.benchmark_pick_session),
                options = withAudio.map { s ->
                    stringResource(R.string.benchmark_session_profile, s.startMs.local().format(DAY), s.processedProfileId ?: s.profileId, s.speechMs / 1000) to s.id
                },
            ) { sessionId = it }
        }

        Overline(stringResource(R.string.benchmark_step_asr), Modifier.padding(top = Space.s3))
        EsCard {
            val asrOptions = models.filter { it.kind == "whisper" }.map { it.name to it.name } +
                (if (models.count { it.kind == "parakeet" } >= 4) listOf("Parakeet TDT v3" to ParakeetAsr.MODEL_NAME) else emptyList())
            Picker(asrOptions.firstOrNull { it.second == asr }?.first ?: stringResource(R.string.pick_model), asrOptions) { asr = it }
            Text(stringResource(R.string.threads), style = MaterialTheme.typography.labelLarge, color = c.ink)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                listOf(2, 4, 6, 8).forEach { n -> FilterChip(selected = threads == n, onClick = { threads = n }, label = { Text("$n") }, colors = chipColors) }
            }
            if (asr != ParakeetAsr.MODEL_NAME) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                    FilterChip(selected = flash, onClick = { flash = !flash }, label = { Text("Flash attention") }, colors = chipColors)
                    FilterChip(selected = fast, onClick = { fast = !fast }, label = { Text(stringResource(R.string.fast_mode)) }, colors = chipColors)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                Button(enabled = !running && sessionId != null && asr != null, onClick = { app.benchmark.run(sessionId!!, asr!!, threads, flash, fast) }) { Text(stringResource(R.string.transcribe)) }
                if (running) OutlinedButton(onClick = { WhisperEngine.cancel() }) { Text(stringResource(R.string.cancel)) }
            }
        }

        Overline(stringResource(R.string.benchmark_step_llm), Modifier.padding(top = Space.s3))
        EsCard {
            Text(stringResource(R.string.benchmark_llm_hint), style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
            val llmOptions = models.filter { it.kind == "llm" }.map { it.name.removeSuffix(".gguf") to it.name }
            Picker(llmOptions.firstOrNull { it.second == llm }?.first ?: stringResource(R.string.pick_model), llmOptions) { llm = it }
            Button(enabled = !running && sessionId != null && llm != null, onClick = { app.benchmark.runLlm(sessionId!!, llm!!) }) { Text(stringResource(R.string.summarize)) }
        }

        BenchmarkResult(bench)

    }
}

@Composable
private fun Picker(label: String, options: List<Pair<String, String>>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text(label, modifier = Modifier.weight(1f), maxLines = 1)
            Icon(Icons.Rounded.ArrowDropDown, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (options.isEmpty()) DropdownMenuItem(text = { Text(stringResource(R.string.none_available)) }, onClick = { open = false }, enabled = false)
            options.forEach { (text, value) -> DropdownMenuItem(text = { Text(text) }, onClick = { onPick(value); open = false }) }
        }
    }
}

@Composable
private fun BenchmarkResult(bench: Benchmark.State) {
    val c = Escriba.colors
    when (bench) {
        Benchmark.State.Idle -> Unit
        is Benchmark.State.Running -> EsCard {
            StatusChip(stringResource(R.string.measuring), Tone.BUSY)
            Text(bench.message, style = MaterialTheme.typography.bodyLarge, color = c.ink)
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.lapis, trackColor = c.lapisSoft)
        }
        is Benchmark.State.Failed -> EsCard {
            StatusChip(stringResource(R.string.state_failed), Tone.ERROR)
            Text(bench.message, style = MaterialTheme.typography.bodyMedium, color = c.ink)
        }
        is Benchmark.State.Done -> EsCard {
            val st = bench.result.stats
            Overline(stringResource(R.string.result))
            Text(stringResource(R.string.realtime_factor, st.realTimeFactor), style = Escriba.type.metric, color = c.ink)
            Text(
                stringResource(R.string.asr_result, st.model, st.audioMs / 1000, st.transcribeMs / 1000, st.threads, (st.realTimeFactor * 60).toInt()),
                style = MaterialTheme.typography.bodyMedium, color = c.inkMuted,
            )
            Text(stringResource(R.string.asr_result_detail, st.loadMs, st.decodeMs, st.segmentsKept, st.segmentsRaw), style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
            if (bench.timings.isNotBlank()) Text(bench.timings, style = Escriba.type.time.copy(fontSize = 10.sp, lineHeight = 13.sp), color = c.inkMuted)
            HorizontalDivider(color = c.rule)
            bench.result.lines.forEach { l ->
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                    Text(l.wallStartMs.local().format(CLOCK), style = Escriba.type.time, color = c.inkMuted)
                    Text(l.text, style = MaterialTheme.typography.bodyMedium, color = c.ink)
                }
            }
        }
        is Benchmark.State.LlmDone -> EsCard {
            val st = bench.result.stats
            val i = bench.result.insights
            Overline(stringResource(R.string.result))
            Text(stringResource(R.string.tokens_per_second, st.generateTps), style = Escriba.type.metric, color = c.ink)
            Text(
                stringResource(R.string.llm_result, st.model, st.loadMs, st.totalMs / 1000, st.promptTokens, st.promptTps, st.chunks),
                style = MaterialTheme.typography.bodyMedium, color = c.inkMuted,
            )
            HorizontalDivider(color = c.rule)
            Text(i.title, style = MaterialTheme.typography.titleMedium, color = c.ink)
            Text(i.summary, style = MaterialTheme.typography.bodyLarge, color = c.ink)
            if (i.decisions.isNotEmpty()) i.decisions.forEach { Text("· $it", style = MaterialTheme.typography.bodyMedium, color = c.ink) }
            if (i.tasks.isNotEmpty()) i.tasks.forEach { t -> Text("· ${t.text}" + listOfNotNull(t.owner, t.due).joinToString(" · ").let { if (it.isEmpty()) "" else " ($it)" }, style = MaterialTheme.typography.bodyMedium, color = c.ink) }
            Text(
                stringResource(R.string.llm_entities, i.people.joinToString().ifEmpty { "—" }, i.projects.joinToString().ifEmpty { "—" }, i.clients.joinToString().ifEmpty { "—" }),
                style = MaterialTheme.typography.bodyMedium, color = c.inkMuted,
            )
        }
    }
}
