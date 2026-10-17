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

/** Si ya se hizo el tour de inicio. Se puede repetir desde Ajustes. */
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
        const val KEY = "hecho"
    }
}

private enum class Step(val title: String) {
    WELCOME("Escriba"),
    VAULT("Tu bóveda de Obsidian"),
    MODELS("Los modelos"),
    WATCH("El reloj"),
    SYSTEM("Notificaciones y batería"),
    READY("Todo listo"),
}

/** Tour de inicio: lleva paso a paso por lo que Escriba necesita para funcionar. */
@Composable
fun OnboardingScreen(app: EscribaApp, actions: SystemActions) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    val step = Step.entries[index]
    val c = Escriba.colors

    Column(Modifier.fillMaxSize().background(c.paper).safeDrawingPadding().padding(horizontal = Space.s4)) {
        // Progreso: un punto por paso; el actual se alarga.
        Row(Modifier.fillMaxWidth().padding(top = Space.s4), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Step.entries.forEachIndexed { i, _ ->
                val width by animateDpAsState(if (i == index) 24.dp else 8.dp, tween(250), label = "progreso")
                Box(Modifier.size(width, 8.dp).clip(CircleShape).background(if (i <= index) c.lapis else c.rule))
            }
            Spacer(Modifier.weight(1f))
            // En el último paso no hay nada que saltar, pero se guarda el hueco para que nada se mueva.
            TextButton(onClick = { app.onboarding.finish() }, enabled = step != Step.READY, modifier = Modifier.alpha(if (step == Step.READY) 0f else 1f)) { Text("Saltar") }
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
                Text(s.title, style = MaterialTheme.typography.displaySmall, color = c.ink)
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
            if (index > 0) OutlinedButton(onClick = { index-- }) { Text("Atrás") }
            Spacer(Modifier.weight(1f))
            Button(onClick = { if (step == Step.READY) app.onboarding.finish() else index++ }) {
                Text(
                    when (step) {
                        Step.WELCOME -> "Empezar"
                        Step.READY -> "Empezar a usar Escriba"
                        else -> "Siguiente"
                    },
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
    Lead("Graba en el reloj, transcribe y resume en el móvil y deja cada conversación como una nota en tu bóveda de Obsidian.")
    listOf(
        "Todo ocurre en este móvil: sin Internet y sin cuentas. El audio viaja del reloj cifrado.",
        "Cada perfil (trabajo, un cliente, ideas…) decide dónde van las notas, qué resumen se hace y si se guarda el audio.",
        "En unos minutos lo dejas listo: la bóveda, los modelos, el reloj y dos permisos.",
    ).forEach { EsCard { Text(it, style = MaterialTheme.typography.bodyLarge, color = Escriba.colors.ink) } }
}

@Composable
private fun VaultStep(app: EscribaApp) = StepBody {
    val tree by app.vault.tree.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var created by remember { mutableStateOf<List<String>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            app.vault.choose(uri)
            // Lo que falte (carpetas, perfiles de ejemplo, plantillas) se crea solo: no se sobrescribe nada.
            scope.launch {
                created = withContext(Dispatchers.IO) { runCatching { VaultBootstrap.apply(app.vault) }.getOrDefault(emptyList()) }
                app.profiles.reload()
                app.profiles.syncToWatch(force = true)
            }
        }
    }
    Lead("Escriba escribe las notas en una carpeta de tu bóveda (p. ej. Documents/Obsidian/Escriba) y solo puede tocar lo que haya dentro.")
    EsCard {
        if (tree == null) {
            StatusChip("Sin elegir", Tone.WAIT)
            Button(onClick = { picker.launch(null) }) { Text("Elegir carpeta de la bóveda") }
        } else {
            StatusChip("Elegida", Tone.DONE)
            Text(app.vault.displayPath() ?: "", style = MaterialTheme.typography.titleSmall, color = Escriba.colors.ink)
            created?.takeIf { it.isNotEmpty() }?.let { Hint("Creado: " + it.joinToString()) }
            TextButton(onClick = { picker.launch(null) }) { Text("Cambiar") }
        }
    }
    Hint("Los perfiles quedan en _config/escriba/perfiles: puedes editarlos aquí o en Obsidian.")
}

@Composable
private fun ModelsStep(app: EscribaApp) = StepBody {
    val folder by app.models.folder.collectAsStateWithLifecycle()
    val models by app.models.models.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) runCatching { app.models.chooseFolder(uri) }.onFailure { AppLog.e("modelos", "No se pudo usar esa carpeta", it) }
    }
    Lead("Los modelos no van dentro de la app: déjalos en una carpeta (p. ej. Descargas/models) y elígela. Escriba los lee desde ahí.")
    EsCard {
        if (folder == null) {
            StatusChip("Sin elegir", Tone.WAIT)
            Button(onClick = { picker.launch(null) }) { Text("Elegir carpeta de modelos") }
        } else {
            Text(app.models.folderName() ?: "", style = MaterialTheme.typography.titleSmall, color = Escriba.colors.ink)
            TextButton(onClick = { picker.launch(null) }) { Text("Cambiar") }
        }
    }
    val has = { kind: String -> models.any { it.kind == kind } }
    ModelNeed("Transcripción", "ggml-large-v3-turbo-q8_0.bin (whisper.cpp)", folder != null, has("whisper"))
    ModelNeed("Resumen", "gemma-3-4b-it-Q4_K_M.gguf", folder != null, has("llm"))
    ModelNeed(
        "Quién habla (opcional)",
        "${Diarizer.SEGMENTATION_MODEL} y ${Diarizer.EMBEDDING_MODEL}",
        folder != null,
        models.any { it.name == Diarizer.SEGMENTATION_MODEL } && models.any { it.name == Diarizer.EMBEDDING_MODEL },
        optional = true,
    )
    ModelNeed("Parakeet (opcional, más rápido)", "parakeet-tdt-0.6b-v3: encoder, decoder, joiner y tokens", folder != null, models.count { it.kind == "parakeet" } >= 4, optional = true)
}

@Composable
private fun ModelNeed(title: String, file: String, folderChosen: Boolean, found: Boolean, optional: Boolean = false) {
    EsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Escriba.colors.ink, modifier = Modifier.weight(1f))
            when {
                !folderChosen -> Unit
                found -> StatusChip("Encontrado", Tone.DONE)
                optional -> StatusChip("No está", Tone.WAIT)
                else -> StatusChip("Falta", Tone.ERROR)
            }
        }
        Text(file, style = Escriba.type.time, color = Escriba.colors.inkMuted)
    }
}

@Composable
private fun WatchStep(app: EscribaApp) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
        Lead("Instala Escriba en el reloj, ábrelo y pulsa «Emparejar» aquí. Los dos mostrarán el mismo código: compruébalo y confirma.")
        // La misma pantalla de Ajustes → Reloj, con su propio desplazamiento.
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
    // Al volver de los ajustes del sistema, se comprueba otra vez.
    LaunchedEffect(Unit) {
        while (true) {
            exempt = actions.isBatteryExempt()
            notifications = notificationsGranted()
            delay(1_500)
        }
    }
    EsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Notificaciones", style = MaterialTheme.typography.titleSmall, color = Escriba.colors.ink, modifier = Modifier.weight(1f))
            if (notifications) StatusChip("Activadas", Tone.DONE)
        }
        Hint("Para ver qué se está transcribiendo o resumiendo.")
        if (!notifications) Button(onClick = { ask.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Permitir notificaciones") }
    }
    EsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Sin optimización de batería", style = MaterialTheme.typography.titleSmall, color = Escriba.colors.ink, modifier = Modifier.weight(1f))
            if (exempt) StatusChip("Hecho", Tone.DONE)
        }
        Hint("Si el sistema cierra Escriba, el audio se queda esperando en el reloj.")
        if (!exempt) Button(onClick = actions.requestBatteryExemption) { Text("Excluir a Escriba") }
    }
    EsCard {
        Text("En MagicOS, además", style = MaterialTheme.typography.titleSmall, color = Escriba.colors.ink)
        Hint("Ajustes → Batería → Inicio de aplicaciones → Escriba: desactiva «Gestionar automáticamente» y activa las tres opciones.")
        OutlinedButton(onClick = actions.openAppSettings) { Text("Abrir los ajustes de Escriba") }
    }
}

@Composable
private fun ReadyStep(app: EscribaApp) = StepBody {
    val tree by app.vault.tree.collectAsStateWithLifecycle()
    val folder by app.models.folder.collectAsStateWithLifecycle()
    val peer by app.link.keys.peer.collectAsStateWithLifecycle()
    Lead("Ya puedes grabar desde el reloj: el botón de la app, el Tile o la complicación. Las notas aparecerán en tu bóveda.")
    listOf(
        "Bóveda" to (tree != null),
        "Carpeta de modelos" to (folder != null),
        "Reloj emparejado" to (peer != null),
    ).forEach { (label, ok) ->
        EsCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.titleSmall, color = Escriba.colors.ink, modifier = Modifier.weight(1f))
                if (ok) StatusChip("Listo", Tone.DONE) else StatusChip("Pendiente", Tone.WAIT)
            }
        }
    }
    Hint("Lo pendiente se puede hacer luego en Ajustes, y este tour se repite desde Ajustes → Tour de inicio.")
    Spacer(Modifier.height(Space.s2))
    Spacer(Modifier.width(0.dp))
}
