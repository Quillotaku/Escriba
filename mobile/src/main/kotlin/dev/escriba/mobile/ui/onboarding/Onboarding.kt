package dev.escriba.mobile.ui.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.annotation.StringRes
import dev.escriba.mobile.R
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.escriba.datalayer.AppLog
import dev.escriba.mobile.EscribaApp
import dev.escriba.mobile.ui.components.EsCard
import dev.escriba.mobile.ui.components.StatusChip
import dev.escriba.mobile.ui.components.Tone
import dev.escriba.mobile.ui.settings.SystemActions
import dev.escriba.mobile.ui.settings.WatchPage
import dev.escriba.mobile.ui.theme.Escriba
import dev.escriba.mobile.ui.theme.Space
import dev.escriba.mobile.vault.VaultBootstrap
import dev.escriba.mobile.voices.Diarizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Whether the welcome tour was done. It can be repeated from Settings. */
class Onboarding(context: Context) {
    private val prefs = context.getSharedPreferences("tour", Context.MODE_PRIVATE)
    private val _done = MutableStateFlow(prefs.getBoolean(KEY, false))
    val done: StateFlow<Boolean> = _done

    fun finish() = set(true)
    fun restart() = set(false)

    private fun set(value: Boolean) {
        prefs.edit().putBoolean(KEY, value).apply()
        _done.value = value
    }

    private companion object {
        const val KEY = "done"
    }
}

private enum class Step(@StringRes val title: Int) {
    WELCOME(R.string.app_name),
    VAULT(R.string.tour_vault),
    MODELS(R.string.tour_models),
    WATCH(R.string.tour_watch),
    SYSTEM(R.string.tour_system),
    READY(R.string.tour_ready),
}

/** Welcome tour: goes step by step through what Escriba needs to work. */
@Composable
fun OnboardingScreen(app: EscribaApp, actions: SystemActions) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    val step = Step.entries[index]
    val c = Escriba.colors

    Column(Modifier.fillMaxSize().background(c.paper).safeDrawingPadding().padding(horizontal = Space.s4)) {
        // Progress: one dot per step; the current one stretches.
        Row(Modifier.fillMaxWidth().padding(top = Space.s4), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Step.entries.forEachIndexed { i, _ ->
                val width by animateDpAsState(if (i == index) 24.dp else 8.dp, tween(250), label = "progress")
                Box(Modifier.size(width, 8.dp).clip(CircleShape).background(if (i <= index) c.lapis else c.rule))
            }
            Spacer(Modifier.weight(1f))
            // On the last step there's nothing to skip, but the space is kept so nothing moves.
            TextButton(onClick = { app.onboarding.finish() }, enabled = step != Step.READY, modifier = Modifier.alpha(if (step == Step.READY) 0f else 1f)) { Text(stringResource(R.string.skip)) }
        }

        AnimatedContent(
            targetState = index,
            transitionSpec = {
                val direction = if (targetState > initialState) 1 else -1
                (slideInHorizontally(tween(280)) { direction * it / 4 } + fadeIn(tween(220)))
                    .togetherWith(slideOutHorizontally(tween(220)) { -direction * it / 4 } + fadeOut(tween(160)))
            },
            modifier = Modifier.weight(1f),
            label = "tour",
        ) { i ->
            val s = Step.entries[i]
            Column(Modifier.fillMaxSize().padding(top = Space.s5), verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                Text(stringResource(s.title), style = MaterialTheme.typography.displaySmall, color = c.ink)
                Box(Modifier.weight(1f)) {
                    when (s) {
                        Step.WELCOME -> WelcomeStep()
                        Step.VAULT -> VaultStep(app)
                        Step.MODELS -> ModelsStep(app)
                        Step.WATCH -> WatchStep(app)
                        Step.SYSTEM -> SystemStep(actions)
                        Step.READY -> ReadyStep(app)
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(vertical = Space.s4), horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
            if (index > 0) OutlinedButton(onClick = { index-- }) { Text(stringResource(R.string.back)) }
            Spacer(Modifier.weight(1f))
            Button(onClick = { if (step == Step.READY) app.onboarding.finish() else index++ }) {
                Text(
                    stringResource(
                        when (step) {
                            Step.WELCOME -> R.string.tour_start
                            Step.READY -> R.string.tour_finish
                            else -> R.string.next
                        },
                    ),
                )
            }
        }
    }
}

@Composable
private fun StepBody(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.s3)) { content() }
}

@Composable
private fun Lead(text: String) = Text(text, style = MaterialTheme.typography.bodyLarge, color = Escriba.colors.ink)

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = Escriba.colors.inkMuted)

@Composable
private fun WelcomeStep() = StepBody {
    Lead(stringResource(R.string.tour_welcome))
    listOf(
        R.string.tour_welcome_private,
        R.string.tour_welcome_profiles,
        R.string.tour_welcome_minutes,
    ).forEach { EsCard { Text(stringResource(it), style = MaterialTheme.typography.bodyLarge, color = Escriba.colors.ink) } }
}

@Composable
private fun VaultStep(app: EscribaApp) = StepBody {
    val tree by app.vault.tree.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var created by remember { mutableStateOf<List<String>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            app.vault.choose(uri)
            // Whatever is missing (folders, sample profiles, templates) is created: nothing is overwritten.
            scope.launch {
                created = withContext(Dispatchers.IO) { runCatching { VaultBootstrap.apply(app.vault) }.getOrDefault(emptyList()) }
                app.profiles.reload()
                app.profiles.syncToWatch(force = true)
            }
        }
    }
    Lead(stringResource(R.string.tour_vault_lead))
    EsCard {
        if (tree == null) {
            StatusChip(stringResource(R.string.not_chosen), Tone.WAIT)
            Button(onClick = { picker.launch(null) }) { Text(stringResource(R.string.tour_choose_vault)) }
        } else {
            StatusChip(stringResource(R.string.chosen), Tone.DONE)
            Text(app.vault.displayPath() ?: "", style = MaterialTheme.typography.titleSmall, color = Escriba.colors.ink)
            created?.takeIf { it.isNotEmpty() }?.let { Hint(stringResource(R.string.created_list, it.joinToString())) }
            TextButton(onClick = { picker.launch(null) }) { Text(stringResource(R.string.change)) }
        }
    }
    Hint(stringResource(R.string.profiles_hint, VaultBootstrap.PROFILES_DIR))
}

@Composable
private fun ModelsStep(app: EscribaApp) = StepBody {
    val folder by app.models.folder.collectAsStateWithLifecycle()
    val models by app.models.models.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) runCatching { app.models.chooseFolder(uri) }.onFailure { AppLog.e("models", "Couldn't use that folder", it) }
    }
    Lead(stringResource(R.string.models_folder_hint))
    EsCard {
        if (folder == null) {
            StatusChip(stringResource(R.string.not_chosen), Tone.WAIT)
            Button(onClick = { picker.launch(null) }) { Text(stringResource(R.string.tour_choose_models)) }
        } else {
            Text(app.models.folderName() ?: "", style = MaterialTheme.typography.titleSmall, color = Escriba.colors.ink)
            TextButton(onClick = { picker.launch(null) }) { Text(stringResource(R.string.change)) }
        }
    }
    val has = { kind: String -> models.any { it.kind == kind } }
    ModelNeed(stringResource(R.string.dash_transcription), "ggml-large-v3-turbo-q8_0.bin (whisper.cpp)", folder != null, has("whisper"))
    ModelNeed(stringResource(R.string.summary), "gemma-3-4b-it-Q4_K_M.gguf", folder != null, has("llm"))
    ModelNeed(
        stringResource(R.string.tour_need_speakers),
        "${Diarizer.SEGMENTATION_MODEL}, ${Diarizer.EMBEDDING_MODEL}",
        folder != null,
        models.any { it.name == Diarizer.SEGMENTATION_MODEL } && models.any { it.name == Diarizer.EMBEDDING_MODEL },
        optional = true,
    )
    ModelNeed(stringResource(R.string.tour_need_parakeet), "parakeet-tdt-0.6b-v3: encoder, decoder, joiner, tokens", folder != null, models.count { it.kind == "parakeet" } >= 4, optional = true)
}

@Composable
private fun ModelNeed(title: String, file: String, folderChosen: Boolean, found: Boolean, optional: Boolean = false) {
    EsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Escriba.colors.ink, modifier = Modifier.weight(1f))
            when {
                !folderChosen -> Unit
                found -> StatusChip(stringResource(R.string.found), Tone.DONE)
                optional -> StatusChip(stringResource(R.string.not_there), Tone.WAIT)
                else -> StatusChip(stringResource(R.string.missing), Tone.ERROR)
            }
        }
        Text(file, style = Escriba.type.time, color = Escriba.colors.inkMuted)
    }
}

@Composable
private fun WatchStep(app: EscribaApp) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
        Lead(stringResource(R.string.tour_watch_lead))
        // The same screen as Settings → Watch, with its own scrolling.
        Box(Modifier.weight(1f)) { WatchPage(app, PaddingValues(0.dp)) }
    }
}

@Composable
private fun SystemStep(actions: SystemActions) = StepBody {
    val context = LocalContext.current
    fun notificationsGranted() = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    var notifications by remember { mutableStateOf(notificationsGranted()) }
    var exempt by remember { mutableStateOf(actions.isBatteryExempt()) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifications = it }
    // When coming back from the system settings, it's checked again.
    LaunchedEffect(Unit) {
        while (true) {
            exempt = actions.isBatteryExempt()
            notifications = notificationsGranted()
            delay(1_500)
        }
    }
    EsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.notifications), style = MaterialTheme.typography.titleSmall, color = Escriba.colors.ink, modifier = Modifier.weight(1f))
            if (notifications) StatusChip(stringResource(R.string.enabled), Tone.DONE)
        }
        Hint(stringResource(R.string.notifications_hint))
        if (!notifications) Button(onClick = { ask.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text(stringResource(R.string.allow_notifications)) }
    }
    EsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.no_battery_optimization), style = MaterialTheme.typography.titleSmall, color = Escriba.colors.ink, modifier = Modifier.weight(1f))
            if (exempt) StatusChip(stringResource(R.string.done), Tone.DONE)
        }
        Hint(stringResource(R.string.battery_hint))
        if (!exempt) Button(onClick = actions.requestBatteryExemption) { Text(stringResource(R.string.battery_exempt_action)) }
    }
    EsCard {
        Text(stringResource(R.string.tour_magicos), style = MaterialTheme.typography.titleSmall, color = Escriba.colors.ink)
        Hint(stringResource(R.string.tour_magicos_hint))
        OutlinedButton(onClick = actions.openAppSettings) { Text(stringResource(R.string.battery_open_settings)) }
    }
}

@Composable
private fun ReadyStep(app: EscribaApp) = StepBody {
    val tree by app.vault.tree.collectAsStateWithLifecycle()
    val folder by app.models.folder.collectAsStateWithLifecycle()
    val peer by app.link.keys.peer.collectAsStateWithLifecycle()
    Lead(stringResource(R.string.tour_ready_lead))
    listOf(
        R.string.vault to (tree != null),
        R.string.models_folder to (folder != null),
        R.string.watch_paired to (peer != null),
    ).forEach { (label, ok) ->
        EsCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(label), style = MaterialTheme.typography.titleSmall, color = Escriba.colors.ink, modifier = Modifier.weight(1f))
                if (ok) StatusChip(stringResource(R.string.ready), Tone.DONE) else StatusChip(stringResource(R.string.pending), Tone.WAIT)
            }
        }
    }
    Hint(stringResource(R.string.tour_ready_hint))
    Spacer(Modifier.height(Space.s2))
    Spacer(Modifier.width(0.dp))
}
