package dev.escriba.mobile.ui.sessions

import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.escriba.datalayer.AppLog
import dev.escriba.mobile.EscribaApp
import dev.escriba.mobile.processing.ProcessWorker
import dev.escriba.mobile.processing.ProcessingStatus
import dev.escriba.mobile.ui.SessionVoicesDialog
import dev.escriba.mobile.ui.components.EsCard
import dev.escriba.mobile.ui.components.EscribaIcons
import dev.escriba.mobile.ui.components.MetaRow
import dev.escriba.mobile.ui.components.Overline
import dev.escriba.mobile.ui.components.ProfileChip
import dev.escriba.mobile.ui.components.StatusChip
import dev.escriba.mobile.ui.components.Tone
import dev.escriba.mobile.ui.components.TranscriptBlock
import dev.escriba.mobile.ui.components.TranscriptParagraph
import dev.escriba.mobile.ui.components.profileColor
import dev.escriba.mobile.ui.theme.Escriba
import dev.escriba.mobile.ui.theme.Space
import dev.escriba.mobile.ui.today.HM
import dev.escriba.mobile.ui.today.chip
import dev.escriba.mobile.ui.today.conversationsOf
import dev.escriba.mobile.ui.today.formatMinutes
import dev.escriba.mobile.ui.today.formatSpeech
import dev.escriba.mobile.ui.today.local
import dev.escriba.shared.llm.Insights
import dev.escriba.shared.transcription.TranscriptLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter

private val HMS = DateTimeFormatter.ofPattern("HH:mm:ss")

/** Agrupa frases en párrafos como la nota: cambio de hablante, 4 s de silencio o 400 caracteres. */
fun paragraphs(lines: List<TranscriptLine>, known: Set<String>, pauseMs: Long = 4_000, maxChars: Int = 400): List<TranscriptParagraph> {
    val out = ArrayList<TranscriptParagraph>()
    var startLine: TranscriptLine? = null
    val text = StringBuilder()
    var lastEnd = Long.MIN_VALUE
    fun flush() {
        val first = startLine ?: return
        out += TranscriptParagraph(first.wallStartMs.local().format(HMS), first.speaker, first.speaker in known || first.speaker == "Yo", text.toString())
        text.clear()
    }
    for (l in lines) {
        val first = startLine
        if (first == null || l.wallStartMs - lastEnd >= pauseMs || l.speaker != first.speaker || text.length + l.text.length > maxChars) {
            flush()
            startLine = l
            text.append(l.text)
        } else {
            text.append(' ').append(l.text)
        }
        lastEnd = l.wallEndMs
    }
    flush()
    return out
}

@Composable
fun SessionDetailScreen(app: EscribaApp, sessionId: String, focus: Int, padding: PaddingValues, close: () -> Unit) {
    val dao = app.db.sessions()
    val session by remember(sessionId) { dao.observeSession(sessionId) }.collectAsStateWithLifecycle(null)
    val fragments by remember(sessionId) { dao.observeFragments(sessionId) }.collectAsStateWithLifecycle(emptyList())
    val notes by remember(sessionId) { dao.observeNotes(sessionId) }.collectAsStateWithLifecycle(emptyList())
    val allInsights by dao.observeInsights().collectAsStateWithLifecycle(emptyList())
    val set by app.profiles.set.collectAsStateWithLifecycle()
    val processing by ProcessingStatus.current.collectAsStateWithLifecycle()
    val watch by app.inbox.watchStatus.collectAsStateWithLifecycle()
    val insights = remember(allInsights, sessionId) { allInsights.filter { it.sessionId == sessionId } }
    // Las frases con hablante cambian al reprocesar: se recargan cuando cambian notas o insights.
    val labeled by produceState(emptyList<TranscriptLine>() to emptySet<String>(), sessionId, notes, insights) {
        value = runCatching { app.processor.labeledLines(sessionId) }.getOrDefault(emptyList<TranscriptLine>() to emptySet())
    }
    val c = Escriba.colors
    val s = session ?: return
    val conversations = remember(s, fragments, notes, insights, set, processing, watch) {
        conversationsOf(listOf(s), fragments, notes, insights, set?.profiles.orEmpty(), set?.defaultProfile, processing?.sessionId, watch?.phase == "CAPTURING")
            .sortedBy { it.startMs }
    }
    val listState = rememberLazyListState()
    LaunchedEffect(conversations.size, focus) {
        val i = conversations.indexOfFirst { it.index == focus }
        if (i > 0) listState.scrollToItem(i + 1)
    }

    var voices by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val profile = set?.profiles?.get(s.processedProfileId ?: s.profileId)

    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = Space.s4, end = Space.s4, top = padding.calculateTopPadding() + Space.s2, bottom = padding.calculateBottomPadding() + Space.s6),
        verticalArrangement = Arrangement.spacedBy(Space.s4),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                // Con varias conversaciones, la cabecera resume la sesión; con una, ya lo dice la conversación.
                if (conversations.size != 1) {
                    val end = s.endMs ?: s.lastSpeechEndMs
                    MetaRow(formatSpeech(s.speechMs) + " de voz", "${conversations.size} conversaciones", time = "${s.startMs.local().format(HM)}–${end.local().format(HM)}")
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
                    ProfileChip(profile?.name ?: s.profileId, c.profileColor(profile?.color))
                    when {
                        processing?.sessionId == s.id -> StatusChip(processing?.step ?: "Procesando", Tone.BUSY)
                        s.endMs == null -> StatusChip("En curso", Tone.RECORDING)
                        !s.isComplete -> StatusChip("Faltan fragmentos", Tone.WAIT)
                    }
                    if (s.audioDeletedAtMs != null) StatusChip("Audio borrado (retención)", Tone.WAIT)
                }
                s.processError?.let { err ->
                    EsCard {
                        StatusChip("Error", Tone.ERROR)
                        Text(err, style = MaterialTheme.typography.bodyMedium, color = c.ink)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalAlignment = Alignment.CenterVertically) {
                    if (s.isComplete) {
                        Button(onClick = { ProcessWorker.enqueue(app, s.id, now = true) }) { Text(if (notes.isEmpty()) "Procesar ahora" else "Reprocesar") }
                        OutlinedButton(onClick = { voices = true }) { Text("Voces") }
                    }
                    Spacer(Modifier.weight(1f))
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Más acciones", tint = c.ink) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (s.isComplete) {
                                set?.profiles?.values?.forEach { p ->
                                    DropdownMenuItem(text = { Text("Reprocesar como ${p.name}") }, onClick = {
                                        ProcessWorker.enqueue(app, s.id, p.id, now = true)
                                        menu = false
                                    })
                                }
                            }
                            DropdownMenuItem(text = { Text("Borrar la sesión", color = c.danger) }, onClick = {
                                menu = false
                                confirmDelete = true
                            })
                        }
                    }
                }
            }
        }

        conversations.forEach { conv ->
            item(key = "conv-${conv.index}") {
                val note = notes.firstOrNull { it.conversation == conv.index }
                val result = insights.firstOrNull { it.conversation == conv.index }?.let { runCatching { Insights.parse(it.json) }.getOrNull() }
                val own = labeled.first.filter { it.wallStartMs >= conv.startMs - 1_000 && it.wallStartMs <= conv.endMs + 1_000 }
                Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                    if (conversations.size > 1) Overline("Conversación ${conv.index}", Modifier.padding(top = Space.s3))
                    Text(conv.title ?: "Conversación de las ${conv.startMs.local().format(HM)}", style = MaterialTheme.typography.headlineSmall, color = c.ink)
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
                        MetaRow(formatMinutes(conv.roundedMin), formatSpeech(conv.speechMs) + " de voz", time = "${conv.startMs.local().format(HM)}–${conv.endMs.local().format(HM)}")
                    }
                    conv.state.chip()
                    if (result != null) {
                        EsCard {
                            Overline("Resumen")
                            Text(result.summary, style = MaterialTheme.typography.bodyLarge, color = c.ink)
                            if (result.decisions.isNotEmpty()) {
                                Overline("Decisiones", Modifier.padding(top = Space.s2))
                                result.decisions.forEach { Text("· $it", style = MaterialTheme.typography.bodyLarge, color = c.ink) }
                            }
                            if (result.tasks.isNotEmpty()) {
                                Overline("Tareas", Modifier.padding(top = Space.s2))
                                result.tasks.forEach { t ->
                                    Text("· ${t.text}" + listOfNotNull(t.owner, t.due).joinToString(" · ").let { if (it.isEmpty()) "" else " ($it)" }, style = MaterialTheme.typography.bodyLarge, color = c.ink)
                                }
                            }
                        }
                    }
                    if (note != null) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                            val audio = note.audioPath?.takeIf { note.audioDeletedAtMs == null }
                            if (audio != null) AudioButton(app, audio)
                            OpenInObsidian(app, note.path)
                        }
                    }
                    if (own.isNotEmpty()) TranscriptBlock(paragraphs(own, labeled.second))
                    else if (conv.state != dev.escriba.mobile.ui.today.ConversationState.RECORDING) {
                        Text("Sin transcripción todavía.", style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
                    }
                }
            }
        }
    }

    if (voices) SessionVoicesDialog(app, s.id) { voices = false }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("¿Borrar la sesión?") },
            text = { Text("Se borra su audio del móvil. Las notas ya escritas en la bóveda no se tocan. No se puede deshacer.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    scope.launch {
                        app.inbox.delete(s.id)
                        close()
                    }
                }) { Text("Borrar", color = c.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }
}

/** Reproduce el audio de la conversación guardado en la bóveda. */
@Composable
private fun AudioButton(app: EscribaApp, path: String) {
    val context = LocalContext.current
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    DisposableEffect(path) {
        onDispose {
            player?.release()
            player = null
        }
    }
    OutlinedButton(onClick = {
        val p = player
        when {
            p != null && playing -> { p.pause(); playing = false }
            p != null -> { p.start(); playing = true }
            else -> scope.launch {
                val uri = withContext(Dispatchers.IO) { app.vault.uri(path) }
                if (uri == null) {
                    AppLog.e("ui", "No se encuentra el audio $path en la bóveda")
                    return@launch
                }
                runCatching {
                    MediaPlayer().apply {
                        setDataSource(context, uri)
                        setOnCompletionListener { playing = false }
                        prepare()
                        start()
                    }
                }.onSuccess { player = it; playing = true }
                    .onFailure { AppLog.e("ui", "No se pudo reproducir el audio", it) }
            }
        }
    }) {
        Icon(if (playing) EscribaIcons.Pause else Icons.Rounded.PlayArrow, null, Modifier.size(18.dp))
        Text(if (playing) "  Pausar" else "  Escuchar")
    }
}

/** Abre la nota en Obsidian (esquema obsidian://, sin Internet). */
@Composable
private fun OpenInObsidian(app: EscribaApp, path: String) {
    val context = LocalContext.current
    val vault = app.vault.vaultName() ?: return
    OutlinedButton(onClick = {
        val uri = Uri.Builder().scheme("obsidian").authority("open")
            .appendQueryParameter("vault", vault)
            .appendQueryParameter("file", path.removeSuffix(".md"))
            .build()
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            AppLog.e("ui", "Obsidian no está instalado", e)
        }
    }) { Text("Abrir en Obsidian") }
}
