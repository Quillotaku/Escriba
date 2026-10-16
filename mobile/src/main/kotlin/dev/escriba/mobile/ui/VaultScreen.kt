package dev.escriba.mobile.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.escriba.datalayer.AppLog
import dev.escriba.mobile.EscribaApp
import dev.escriba.mobile.vault.RetentionWorker
import dev.escriba.mobile.vault.VaultBootstrap
import dev.escriba.shared.vault.Frontmatter
import dev.escriba.shared.vault.Profile
import dev.escriba.shared.vault.ProfileLoader
import dev.escriba.shared.vault.YamlValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun VaultScreen(app: EscribaApp) {
    val scope = rememberCoroutineScope()
    val tree by app.vault.tree.collectAsStateWithLifecycle()
    val set by app.profiles.set.collectAsStateWithLifecycle()
    var missing by remember { mutableStateOf<List<String>?>(null) }
    var editing by remember { mutableStateOf<Profile?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            app.vault.choose(uri)
            AppLog.i("boveda", "Bóveda elegida: ${app.vault.displayPath()}")
        }
    }

    LaunchedEffect(tree) {
        if (tree != null) {
            missing = withContext(Dispatchers.IO) { VaultBootstrap.missing(app.vault) }
            app.profiles.reload()
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Bóveda de Obsidian", style = MaterialTheme.typography.titleLarge)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (tree == null) {
                    Text("Elige la carpeta de tu bóveda (p. ej. Documents/Obsidian/Escriba). Escriba solo podrá tocar lo que haya dentro.")
                } else {
                    Text(app.vault.displayPath() ?: "", fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(onClick = { picker.launch(null) }) { Text(if (tree == null) "Elegir carpeta" else "Cambiar de carpeta") }
            }
        }

        val toCreate = missing
        if (tree != null && !toCreate.isNullOrEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Configuración inicial", fontWeight = FontWeight.SemiBold)
                    Text("Falta por crear (no se sobrescribe nada que ya exista):")
                    Text(toCreate.joinToString("\n") { "• $it" }, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = {
                        scope.launch {
                            val created = withContext(Dispatchers.IO) { runCatching { VaultBootstrap.apply(app.vault) } }
                            created.onSuccess { AppLog.i("boveda", "Creado: ${it.joinToString()}") }
                                .onFailure { AppLog.e("boveda", "No se pudo crear la configuración", it) }
                            missing = withContext(Dispatchers.IO) { VaultBootstrap.missing(app.vault) }
                            app.profiles.reload()
                            app.profiles.syncToWatch(force = true)
                        }
                    }) { Text("Crear configuración inicial") }
                }
            }
        }

        val current = set
        if (current != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { scope.launch { app.profiles.reload() } }) { Text("Recargar") }
                OutlinedButton(onClick = {
                    scope.launch { message = if (app.profiles.syncToWatch(force = true)) "Perfiles enviados al reloj" else "El reloj no está conectado" }
                }) { Text("Enviar al reloj") }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            Text("Perfiles", style = MaterialTheme.typography.titleMedium)
            current.profiles.values.forEach { p ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                (if (p.favorite) "★ " else "") + p.name + if (p.id == current.defaultProfile?.id) "  (por defecto)" else "",
                                fontWeight = FontWeight.SemiBold,
                                color = parseColor(p.color),
                            )
                            Text(
                                listOfNotNull(
                                    if (p.mode == "continuo") "continuo" else "bajo demanda",
                                    p.folder,
                                    "audio".takeIf { p.saveAudio },
                                    "diarización".takeIf { p.diarization },
                                    "tareas".takeIf { p.tasks },
                                    p.client,
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        TextButton(onClick = { editing = p }) { Text("Editar") }
                    }
                }
            }

            if (current.errors.isNotEmpty()) {
                Text("Errores", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                current.errors.forEach { (file, errors) ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(file, fontWeight = FontWeight.SemiBold)
                            errors.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                Text("También están en ${VaultBootstrap.ERRORS_FILE}.", style = MaterialTheme.typography.bodySmall)
            }

            OutlinedButton(onClick = { RetentionWorker.runNow(app) }) { Text("Aplicar la retención de audio ahora") }
        }
    }

    editing?.let { p -> ProfileEditor(app, p) { editing = null } }
}

@Composable
private fun ProfileEditor(app: EscribaApp, profile: Profile, close: () -> Unit) {
    val scope = rememberCoroutineScope()
    val opened = remember { app.profiles.readProfile(profile.id) }
    val ownProps = remember { opened?.let { Frontmatter.parse(it.first).first.properties } ?: linkedMapOf() }
    var name by remember { mutableStateOf(profile.name) }
    var folder by remember { mutableStateOf(profile.folder) }
    var continuous by remember { mutableStateOf(profile.mode == "continuo") }
    var favorite by remember { mutableStateOf(profile.favorite) }
    var saveAudio by remember { mutableStateOf(profile.saveAudio) }
    var diarization by remember { mutableStateOf(profile.diarization) }
    var tasks by remember { mutableStateOf(profile.tasks) }
    var discard by remember { mutableStateOf(profile.discardTrivial) }
    var icon by remember { mutableStateOf(profile.icon) }
    var prompt by remember { mutableStateOf(opened?.first?.let { ProfileLoader.extractPrompt(Frontmatter.parse(it).first.body) } ?: profile.prompt) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = close,
        title = { Text("Perfil «${profile.id}»") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if ("hereda" in ownProps) Text("Hereda de «${(ownProps["hereda"] as? YamlValue.Text)?.value}». Lo que cambies aquí se guarda en este perfil.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, singleLine = true)
                OutlinedTextField(folder, { folder = it }, label = { Text("Carpeta") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("mic", "casa", "maletin", "idea", "grupo").forEach { i ->
                        FilterChip(selected = icon == i, onClick = { icon = i }, label = { Text(i) })
                    }
                }
                Toggle("Modo continuo", continuous) { continuous = it }
                Toggle("Favorito (sale primero en el reloj)", favorite) { favorite = it }
                Toggle("Guardar el audio en la bóveda", saveAudio) { saveAudio = it }
                Toggle("Diarización (quién habla)", diarization) { diarization = it }
                Toggle("Extraer tareas", tasks) { tasks = it }
                Toggle("Descartar conversaciones triviales", discard) { discard = it }
                OutlinedTextField(prompt, { prompt = it }, label = { Text("Prompt") }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    // Solo se escriben las propiedades que han cambiado: lo heredado sigue heredándose.
                    val changes = linkedMapOf<String, YamlValue>()
                    fun set(key: String, value: YamlValue, inherited: Any) {
                        val now = when (value) {
                            is YamlValue.Text -> value.value
                            is YamlValue.Bool -> value.value
                            else -> value
                        }
                        if (key in ownProps || now != inherited) changes[key] = value
                    }
                    set("nombre", YamlValue.Text(name.trim()), profile.name)
                    set("carpeta", YamlValue.Text(folder.trim()), profile.folder)
                    set("icono", YamlValue.Text(icon), profile.icon)
                    set("modo", YamlValue.Text(if (continuous) "continuo" else "bajo_demanda"), profile.mode)
                    set("favorito", YamlValue.Bool(favorite), profile.favorite)
                    set("guardar_audio", YamlValue.Bool(saveAudio), profile.saveAudio)
                    set("diarizacion", YamlValue.Bool(diarization), profile.diarization)
                    set("tareas", YamlValue.Bool(tasks), profile.tasks)
                    set("descartar_triviales", YamlValue.Bool(discard), profile.discardTrivial)
                    app.profiles.saveProfile(profile.id, changes, prompt, opened?.second)
                        .onSuccess { close() }
                        .onFailure { error = it.message }
                }
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = close) { Text("Cancelar") } },
    )
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun parseColor(hex: String): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(Color.Unspecified)
