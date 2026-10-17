package dev.escriba.mobile.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.escriba.datalayer.AppLog
import dev.escriba.mobile.EscribaApp
import dev.escriba.mobile.ui.components.EsCard
import dev.escriba.mobile.ui.components.EscribaIcons
import dev.escriba.mobile.ui.components.Overline
import dev.escriba.mobile.ui.components.ProfileDot
import dev.escriba.mobile.ui.components.SettingRow
import dev.escriba.mobile.ui.components.StatusChip
import dev.escriba.mobile.ui.components.Tone
import dev.escriba.mobile.ui.components.profileColor
import dev.escriba.mobile.ui.theme.Escriba
import dev.escriba.mobile.ui.theme.Space
import dev.escriba.mobile.vault.RetentionWorker
import dev.escriba.mobile.vault.VaultBootstrap
import dev.escriba.shared.vault.Frontmatter
import dev.escriba.shared.vault.Profile
import dev.escriba.shared.vault.ProfileLoader
import dev.escriba.shared.vault.YamlValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// --- Bóveda y perfiles -------------------------------------------------------

@Composable
fun VaultPage(app: EscribaApp, padding: PaddingValues, editProfile: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val tree by app.vault.tree.collectAsStateWithLifecycle()
    val set by app.profiles.set.collectAsStateWithLifecycle()
    var missing by remember { mutableStateOf<List<String>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    val c = Escriba.colors

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            app.vault.choose(uri)
            AppLog.i("boveda", "Bóveda elegida: ${app.vault.displayPath()}")
        }
    }

    LaunchedEffect(tree) {
        if (tree != null) {
            withContext(Dispatchers.IO) { runCatching { VaultBootstrap.repair(app.vault) } }.getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { AppLog.i("boveda", "Reparado: ${it.joinToString()}") }
            missing = withContext(Dispatchers.IO) { VaultBootstrap.missing(app.vault) }
            app.profiles.reload()
        }
    }

    Page(padding) {
        Overline("Bóveda de Obsidian")
        EsCard {
            if (tree == null) {
                Text("Elige la carpeta de tu bóveda (p. ej. Documents/Obsidian/Escriba). Escriba solo podrá tocar lo que haya dentro.", style = MaterialTheme.typography.bodyLarge, color = c.ink)
                Button(onClick = { picker.launch(null) }) { Text("Elegir carpeta") }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(app.vault.displayPath() ?: "", style = MaterialTheme.typography.titleSmall, color = c.ink, modifier = Modifier.weight(1f))
                    TextButton(onClick = { picker.launch(null) }) { Text("Cambiar") }
                }
            }
        }

        val toCreate = missing
        if (tree != null && !toCreate.isNullOrEmpty()) {
            EsCard {
                StatusChip("Falta configuración", Tone.WAIT)
                Text("Escriba necesita crear esto en tu bóveda. No se sobrescribe nada que ya exista:", style = MaterialTheme.typography.bodyLarge, color = c.ink)
                Text(toCreate.joinToString("\n") { "· $it" }, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
                Button(onClick = {
                    scope.launch {
                        val created = withContext(Dispatchers.IO) { runCatching { VaultBootstrap.apply(app.vault) } }
                        created.onSuccess { AppLog.i("boveda", "Creado: ${it.joinToString()}") }
                            .onFailure { AppLog.e("boveda", "No se pudo crear la configuración", it) }
                        missing = withContext(Dispatchers.IO) { VaultBootstrap.missing(app.vault) }
                        app.profiles.reload()
                        app.profiles.syncToWatch(force = true)
                    }
                }) { Text("Crear configuración") }
            }
        }

        val current = set ?: return@Page

        // Avisos al leer los archivos de configuración: Escriba usa el valor por defecto hasta que se corrijan.
        if (current.errors.isNotEmpty()) {
            EsCard {
                StatusChip("Revisa la configuración", Tone.WAIT)
                Text(
                    "Hay valores que Escriba no entiende en estos archivos de la bóveda. Mientras tanto usa el valor por defecto.",
                    style = MaterialTheme.typography.bodyMedium, color = c.inkMuted,
                )
                current.errors.forEach { (file, errors) ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("_config/escriba/$file", style = Escriba.type.time, color = c.ink)
                        errors.forEach { Text("· $it", style = MaterialTheme.typography.bodyMedium, color = c.ink) }
                    }
                }
            }
        }

        Overline("Perfiles", Modifier.padding(top = Space.s3))
        EsCard {
            current.profiles.values.forEachIndexed { i, p ->
                if (i > 0) HorizontalDivider(color = c.rule)
                ProfileRow(p, isDefault = p.id == current.defaultProfile?.id) { editProfile(p.id) }
            }
            HorizontalDivider(color = c.rule)
            Row(
                Modifier.fillMaxWidth().heightIn(min = Space.s7).clickable { creating = true }.padding(vertical = Space.s2),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.s2),
            ) {
                Icon(Icons.Rounded.Add, null, tint = c.lapis)
                Text("Nuevo perfil", style = MaterialTheme.typography.titleSmall, color = c.lapis)
            }
        }
        if (creating) NewProfileDialog(app, current.profiles.values.toList(), close = { creating = false }) { id ->
            creating = false
            editProfile(id)
        }
        Text(
            "Los perfiles son archivos de _config/escriba/perfiles en tu bóveda: puedes editarlos aquí o en Obsidian.",
            style = MaterialTheme.typography.bodyMedium, color = c.inkMuted,
        )
        EsCard {
            SettingRow(Icons.Rounded.Refresh, "Volver a leer la bóveda", "Si has cambiado perfiles en Obsidian", onClick = { scope.launch { app.profiles.reload() } }, trailing = {})
            HorizontalDivider(color = c.rule)
            SettingRow(EscribaIcons.Watch, "Enviar perfiles al reloj", message ?: "Se envían solos al cambiar; esto lo fuerza", onClick = {
                scope.launch { message = if (app.profiles.syncToWatch(force = true)) "Enviados" else "El reloj no está conectado" }
            }, trailing = {})
        }
    }
}

@Composable
private fun ProfileRow(p: Profile, isDefault: Boolean, onClick: () -> Unit) {
    val c = Escriba.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = Space.s7).clickable(onClick = onClick).padding(vertical = Space.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                ProfileDot(c.profileColor(p.color))
                Text(p.name, style = MaterialTheme.typography.titleSmall, color = c.ink)
                if (p.favorite) Icon(Icons.Rounded.Star, "Favorito", Modifier.size(16.dp), tint = c.ochre)
                if (isDefault) Text("Por defecto", style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
            }
            Text(
                listOfNotNull(
                    if (p.mode == "continuo") "Continuo" else "Bajo demanda",
                    if (p.engine == "parakeet") "Parakeet" else null,
                    "quién habla".takeIf { p.diarization },
                    "tareas".takeIf { p.tasks },
                    "guarda audio".takeIf { p.saveAudio },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium, color = c.inkMuted,
            )
            Text(p.folder, style = Escriba.type.time.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize), color = c.inkMuted)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.inkMuted)
    }
}

// --- Editor de perfil (pantalla completa) ------------------------------------

@Composable
fun ProfileEditorPage(app: EscribaApp, profileId: String, padding: PaddingValues, close: () -> Unit) {
    val set by app.profiles.set.collectAsStateWithLifecycle()
    val profile = set?.profiles?.get(profileId) ?: return
    val scope = rememberCoroutineScope()
    val c = Escriba.colors
    val opened by produceState<Pair<String, Long?>?>(null, profileId) { value = withContext(Dispatchers.IO) { app.profiles.readProfile(profileId) } }
    val ownProps = remember(opened) { opened?.let { Frontmatter.parse(it.first).first.properties } ?: linkedMapOf() }

    var name by remember(profile) { mutableStateOf(profile.name) }
    var folder by remember(profile) { mutableStateOf(profile.folder) }
    var continuous by remember(profile) { mutableStateOf(profile.mode == "continuo") }
    var favorite by remember(profile) { mutableStateOf(profile.favorite) }
    var saveAudio by remember(profile) { mutableStateOf(profile.saveAudio) }
    var diarization by remember(profile) { mutableStateOf(profile.diarization) }
    var engine by remember(profile) { mutableStateOf(profile.engine) }
    var tasks by remember(profile) { mutableStateOf(profile.tasks) }
    var discard by remember(profile) { mutableStateOf(profile.discardTrivial) }
    var icon by remember(profile) { mutableStateOf(profile.icon) }
    var color by remember(profile) { mutableStateOf(profile.color) }
    var prompt by remember(opened) { mutableStateOf(opened?.first?.let { ProfileLoader.extractPrompt(Frontmatter.parse(it).first.body) } ?: profile.prompt) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    Page(padding) {
        (ownProps["hereda"] as? YamlValue.Text)?.value?.let { parent ->
            Text("Hereda de «$parent». Lo que cambies aquí se guarda solo en este perfil.", style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
        }
        Overline("General")
        EsCard {
            OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(folder, { folder = it }, label = { Text("Carpeta de las notas") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("Admite {{yyyy}} y {{MM}}") })
            Text("Color", style = MaterialTheme.typography.labelLarge, color = c.ink)
            ColorPicker(color) { color = it }
            Text("Icono en el reloj", style = MaterialTheme.typography.labelLarge, color = c.ink)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
                ProfileLoader.ICONS.forEach { i ->
                    FilterChip(
                        selected = icon == i, onClick = { icon = i }, label = { Text(ICON_LABELS[i] ?: i) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = c.lapisSoft, selectedLabelColor = c.ink),
                    )
                }
            }
        }

        Overline("Grabación", Modifier.padding(top = Space.s3))
        EsCard {
            ToggleRow("Modo continuo", "Graba todo el horario; si no, solo cuando pulsas", continuous) { continuous = it }
            HorizontalDivider(color = c.rule)
            ToggleRow("Favorito", "Sale el primero en el reloj", favorite) { favorite = it }
        }

        Overline("Procesado", Modifier.padding(top = Space.s3))
        EsCard {
            Text("Motor de transcripción", style = MaterialTheme.typography.labelLarge, color = c.ink)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("whisper" to "Whisper", "parakeet" to "Parakeet").forEachIndexed { i, (id, label) ->
                    SegmentedButton(selected = engine == id, onClick = { engine = id }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
                }
            }
            Text(
                if (engine == "parakeet") "Unas 10 veces más rápido; sin vocabulario del perfil." else "Más preciso con nombres propios gracias al vocabulario del perfil.",
                style = MaterialTheme.typography.bodyMedium, color = c.inkMuted,
            )
            HorizontalDivider(color = c.rule)
            ToggleRow("Separar quién habla", "Pone el nombre de cada voz en la transcripción", diarization) { diarization = it }
            HorizontalDivider(color = c.rule)
            ToggleRow("Extraer tareas", "Con responsable y fecha cuando se mencionen", tasks) { tasks = it }
            HorizontalDivider(color = c.rule)
            ToggleRow("Descartar lo trivial", "No escribe nota de conversaciones sin contenido", discard) { discard = it }
            HorizontalDivider(color = c.rule)
            ToggleRow("Guardar el audio en la bóveda", "Junto a la nota, para escucharlo luego", saveAudio) { saveAudio = it }
        }

        Overline("Instrucciones para el resumen", Modifier.padding(top = Space.s3))
        OutlinedTextField(prompt, { prompt = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp), textStyle = MaterialTheme.typography.bodyLarge)

        error?.let { Text(it, color = c.danger, style = MaterialTheme.typography.bodyMedium) }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s2), modifier = Modifier.padding(top = Space.s2)) {
            Button(enabled = !saving, onClick = {
                saving = true
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
                    set("color", YamlValue.Text(color), profile.color)
                    set("modo", YamlValue.Text(if (continuous) "continuo" else "bajo_demanda"), profile.mode)
                    set("favorito", YamlValue.Bool(favorite), profile.favorite)
                    set("guardar_audio", YamlValue.Bool(saveAudio), profile.saveAudio)
                    set("diarizacion", YamlValue.Bool(diarization), profile.diarization)
                    set("motor", YamlValue.Text(engine), profile.engine)
                    set("tareas", YamlValue.Bool(tasks), profile.tasks)
                    set("descartar_triviales", YamlValue.Bool(discard), profile.discardTrivial)
                    val own = opened?.first?.let { ProfileLoader.extractPrompt(Frontmatter.parse(it).first.body) }.orEmpty()
                    val promptToSave = if (own.isEmpty() && prompt.trim() == profile.prompt.trim()) null else prompt
                    app.profiles.saveProfile(profile.id, changes, promptToSave, opened?.second)
                        .onSuccess { close() }
                        .onFailure { error = it.message; saving = false }
                }
            }) { Text("Guardar") }
            OutlinedButton(onClick = close) { Text("Cancelar") }
        }
    }
}

/** Los seis colores de perfil del sistema, por su valor claro (el que se guarda en la bóveda). */
private val PROFILE_COLORS = listOf(
    "pino" to "#2E6B3A", "lapislázuli" to "#2C4DA8", "ocre" to "#835400",
    "ciruela" to "#7A2F73", "pizarra" to "#3F5466", "teja" to "#9C3D1C",
)

@Composable
private fun ColorPicker(selected: String, onPick: (String) -> Unit) {
    val c = Escriba.colors
    val current = c.profileColor(selected)
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
        PROFILE_COLORS.forEach { (name, hex) ->
            val swatch = c.profileColor(hex)
            val isSelected = swatch == current
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(swatch)
                    .border(2.dp, if (isSelected) c.ink else c.paperRaised, CircleShape)
                    .clickable { onPick(hex) }
                    .semantics { contentDescription = "Color $name" },
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = c.paperRaised)
            }
        }
    }
}

@Composable
private fun NewProfileDialog(app: EscribaApp, profiles: List<Profile>, close: () -> Unit, created: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val c = Escriba.colors
    var name by remember { mutableStateOf("") }
    var basedOn by remember { mutableStateOf<String?>(null) }
    var color by remember { mutableStateOf(PROFILE_COLORS[(profiles.size) % PROFILE_COLORS.size].second) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Nuevo perfil") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text("P. ej. «Reuniones Acme»") })
                Text("Color", style = MaterialTheme.typography.labelLarge, color = c.ink)
                ColorPicker(color) { color = it }
                Text("Basado en", style = MaterialTheme.typography.labelLarge, color = c.ink)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
                    val chip = FilterChipDefaults.filterChipColors(selectedContainerColor = c.lapisSoft, selectedLabelColor = c.ink)
                    FilterChip(selected = basedOn == null, onClick = { basedOn = null }, label = { Text("Ninguno") }, colors = chip)
                    profiles.forEach { p -> FilterChip(selected = basedOn == p.id, onClick = { basedOn = p.id }, label = { Text(p.name) }, colors = chip) }
                }
                Text(
                    if (basedOn == null) "Empieza con valores de partida; los ajustas en la siguiente pantalla."
                    else "Hereda todo de ese perfil: solo se guarda lo que cambies.",
                    style = MaterialTheme.typography.bodyMedium, color = c.inkMuted,
                )
                error?.let { Text(it, color = c.danger, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = {
            Button(enabled = name.isNotBlank(), onClick = {
                scope.launch {
                    app.profiles.createProfile(name, basedOn, color)
                        .onSuccess(created)
                        .onFailure { error = it.message }
                }
            }) { Text("Crear") }
        },
        dismissButton = { TextButton(onClick = close) { Text("Cancelar") } },
    )
}

private val ICON_LABELS = mapOf(
    "mic" to "Micrófono", "maletin" to "Maletín", "idea" to "Idea", "casa" to "Casa", "persona" to "Persona",
    "grupo" to "Grupo", "telefono" to "Teléfono", "coche" to "Coche", "libro" to "Libro", "estrella" to "Estrella",
)

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = Escriba.colors
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = Space.s1), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.ink)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
        }
        Spacer(Modifier.width(Space.s3))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = c.lapis, checkedThumbColor = c.onLapis, uncheckedBorderColor = c.ruleStrong, uncheckedThumbColor = c.ruleStrong, uncheckedTrackColor = c.paperSunken),
        )
    }
}

// --- Almacenamiento y retención ----------------------------------------------

@Composable
fun StoragePage(app: EscribaApp, padding: PaddingValues) {
    val sessions by app.db.sessions().observeSessions().collectAsStateWithLifecycle(emptyList())
    val notes by app.db.sessions().observeNotes().collectAsStateWithLifecycle(emptyList())
    val set by app.profiles.set.collectAsStateWithLifecycle()
    var ran by remember { mutableStateOf(false) }
    val c = Escriba.colors
    val onPhone = sessions.filter { it.audioDeletedAtMs == null }
    val inVault = notes.filter { it.audioPath != null && it.audioDeletedAtMs == null }

    Page(padding) {
        Overline("En este móvil")
        EsCard {
            Text("%.1f MB de audio".format(onPhone.sumOf { it.bytes } / 1_048_576.0), style = Escriba.type.metric, color = c.ink)
            Text("${onPhone.size} sesiones con audio. Se borra ${set?.config?.phoneAudioRetentionDays ?: 7} días después de escribir su nota (retencion_audio_movil en escriba.md).",
                style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
        }
        Overline("En la bóveda", Modifier.padding(top = Space.s3))
        EsCard {
            Text("${inVault.size} audios junto a sus notas", style = MaterialTheme.typography.titleSmall, color = c.ink)
            set?.profiles?.values?.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                    ProfileDot(c.profileColor(p.color))
                    Text(p.name, style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.weight(1f))
                    Text(
                        when {
                            !p.saveAudio -> "No guarda audio"
                            p.audioRetentionDays == null -> "Se guarda siempre"
                            else -> "Se borra a los ${p.audioRetentionDays} días"
                        },
                        style = MaterialTheme.typography.bodyMedium, color = c.inkMuted,
                    )
                }
            }
        }
        Text("La limpieza se hace sola una vez al día. Las notas no se borran nunca: solo el audio, y la nota lo indica.", style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
        OutlinedButton(onClick = { RetentionWorker.runNow(app); ran = true }, enabled = !ran) { Text(if (ran) "Limpieza en marcha" else "Limpiar ahora el audio caducado") }
    }
}
