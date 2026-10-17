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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.escriba.mobile.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.escriba.datalayer.AppLog
import dev.escriba.mobile.EscribaApp
import dev.escriba.mobile.processing.ProcessWorker
import dev.escriba.mobile.processing.SessionProcessor
import kotlinx.coroutines.launch

/**
 * A session's speakers: how much each one spoke and a few of their lines to recognize them. Naming
 * a speaker enrolls their voice (on the phone only) and reprocesses the session with that name.
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
        // In the app's scope: it carries on even if the dialog closes.
        app.scope.launch {
            runCatching { if (redo) app.processor.redoDiarization(sessionId) else app.processor.ensureDiarization(sessionId) }
                .onFailure { AppLog.e("voices", "Couldn't separate the speakers", it); error = it.message }
            detecting = false
            reload++
        }
    }

    LaunchedEffect(sessionId, reload) {
        speakers = runCatching { app.processor.speakers(sessionId) }.getOrElse { error = it.message; emptyList() }
    }

    AlertDialog(
        onDismissRequest = close,
        title = { Text(stringResource(R.string.session_voices)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.session_voices_hint),
                    style = MaterialTheme.typography.bodySmall,
                )
                val list = speakers
                if (!list.isNullOrEmpty() && !detecting) {
                    OutlinedButton(onClick = { detect(true) }) { Text(pluralStringResource(R.plurals.redetect_speakers, list.size, list.size)) }
                }
                when {
                    list == null -> Text(stringResource(R.string.loading))
                    detecting -> {
                        Text(stringResource(R.string.separating_speakers))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    list.isEmpty() -> {
                        Text(stringResource(R.string.speakers_not_separated))
                        Button(onClick = { detect(false) }) { Text(stringResource(R.string.detect_speakers)) }
                    }
                    else -> list.forEach { s ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(stringResource(R.string.speaker_speech, s.label, s.speechMs / 1000), fontWeight = FontWeight.SemiBold)
                                s.samples.forEach { Text("“$it”", style = MaterialTheme.typography.bodySmall) }
                                if (s.hasEmbedding) {
                                    OutlinedTextField(
                                        value = names[s.cluster] ?: "",
                                        onValueChange = { names[s.cluster] = it },
                                        label = { Text(stringResource(R.string.name)) },
                                        singleLine = true,
                                    )
                                } else {
                                    Text(stringResource(R.string.too_little_speech), style = MaterialTheme.typography.bodySmall)
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
                        AppLog.e("voices", "Couldn't enroll the voice", it)
                        error = it.message
                    }
                }
            }) { Text(stringResource(R.string.save_and_reprocess)) }
        },
        dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.close)) } },
    )
}

/** Enrolled voices, with the option to delete them. */
@Composable
fun EnrolledVoicesCard(app: EscribaApp) {
    val voices by app.db.sessions().observeVoices().collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var toDelete by remember { mutableStateOf<String?>(null) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.settings_voices), fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.voices_local_only), style = MaterialTheme.typography.bodySmall)
            if (voices.isEmpty()) Text(stringResource(R.string.voices_none_yet), style = MaterialTheme.typography.bodySmall)
            voices.forEach { v ->
                Row {
                    Text(pluralStringResource(R.plurals.voice_sessions, v.samples, v.name, v.samples), Modifier.weight(1f).padding(top = 12.dp))
                    TextButton(onClick = { toDelete = v.name }) { Text(stringResource(R.string.delete)) }
                }
            }
        }
    }

    toDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(stringResource(R.string.delete_voice_title, name)) },
            text = { Text(stringResource(R.string.delete_voice_body)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { app.voices.delete(name) }
                    toDelete = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
