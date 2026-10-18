package dev.escriba.mobile.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.escriba.mobile.R
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

// --- Vault and profiles ------------------------------------------------------

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
            AppLog.i("vault", "Vault chosen: ${app.vault.displayPath()}")
        }
    }

    LaunchedEffect(tree) {
        if (tree != null) {
            missing = withContext(Dispatchers.IO) { VaultBootstrap.missing(app.vault) }
            app.profiles.reload()
        }
    }

    Page(padding) {
        Overline(stringResource(R.string.obsidian_vault))
        EsCard {
            if (tree == null) {
                Text(stringResource(R.string.vault_choose_hint), style = MaterialTheme.typography.bodyLarge, color = c.ink)
                Button(onClick = { picker.launch(null) }) { Text(stringResource(R.string.choose_folder)) }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(app.vault.displayPath() ?: "", style = MaterialTheme.typography.titleSmall, color = c.ink, modifier = Modifier.weight(1f))
                    TextButton(onClick = { picker.launch(null) }) { Text(stringResource(R.string.change)) }
                }
            }
        }

        val toCreate = missing
        if (tree != null && !toCreate.isNullOrEmpty()) {
            EsCard {
                StatusChip(stringResource(R.string.vault_missing_config), Tone.WAIT)
                Text(stringResource(R.string.vault_create_hint), style = MaterialTheme.typography.bodyLarge, color = c.ink)
                Text(toCreate.joinToString("\n") { "· $it" }, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
                Button(onClick = {
                    scope.launch {
                        val created = withContext(Dispatchers.IO) { runCatching { VaultBootstrap.apply(app.vault) } }
                        created.onSuccess { AppLog.i("vault", "Created: ${it.joinToString()}") }
                            .onFailure { AppLog.e("vault", "Couldn't create the configuration", it) }
                        missing = withContext(Dispatchers.IO) { VaultBootstrap.missing(app.vault) }
                        app.profiles.reload()
                        app.profiles.syncToWatch(force = true)
                    }
                }) { Text(stringResource(R.string.vault_create)) }
            }
        }

        val current = set ?: return@Page

        // Warnings from reading the configuration files: Escriba uses the default value until they're fixed.
        if (current.errors.isNotEmpty()) {
            EsCard {
                StatusChip(stringResource(R.string.vault_check_config), Tone.WAIT)
                Text(
                    stringResource(R.string.vault_config_errors),
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

        Overline(stringResource(R.string.profiles), Modifier.padding(top = Space.s3))
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
                Text(stringResource(R.string.new_profile), style = MaterialTheme.typography.titleSmall, color = c.lapis)
            }
        }
        if (creating) NewProfileDialog(app, current.profiles.values.toList(), close = { creating = false }) { id ->
            creating = false
            editProfile(id)
        }
        Text(
            stringResource(R.string.profiles_hint, VaultBootstrap.PROFILES_DIR),
            style = MaterialTheme.typography.bodyMedium, color = c.inkMuted,
        )
        EsCard {
            SettingRow(Icons.Rounded.Refresh, stringResource(R.string.vault_reload), stringResource(R.string.vault_reload_hint), onClick = { scope.launch { app.profiles.reload() } }, trailing = {})
            HorizontalDivider(color = c.rule)
            val sent = stringResource(R.string.profiles_sent)
            val notConnected = stringResource(R.string.watch_not_connected)
            SettingRow(EscribaIcons.Watch, stringResource(R.string.profiles_send), message ?: stringResource(R.string.profiles_send_hint), onClick = {
                scope.launch { message = if (app.profiles.syncToWatch(force = true)) sent else notConnected }
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
                if (p.favorite) Icon(Icons.Rounded.Star, stringResource(R.string.favorite), Modifier.size(16.dp), tint = c.ochre)
                if (isDefault) Text(stringResource(R.string.default_profile), style = MaterialTheme.typography.labelMedium, color = c.inkMuted)
            }
            Text(
                listOfNotNull(
                    stringResource(if (p.continuous) R.string.mode_continuous else R.string.mode_on_demand),
                    if (p.engine == "parakeet") "Parakeet" else null,
                    stringResource(R.string.tag_speakers).takeIf { p.diarization },
                    stringResource(R.string.tag_tasks).takeIf { p.tasks },
                    stringResource(R.string.tag_saves_audio).takeIf { p.saveAudio },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium, color = c.inkMuted,
            )
            Text(p.folder, style = Escriba.type.time.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize), color = c.inkMuted)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.inkMuted)
    }
}

// --- Profile editor (full screen) -------------------------------------------

@Composable
fun ProfileEditorPage(app: EscribaApp, profileId: String, padding: PaddingValues, close: () -> Unit) {
    val set by app.profiles.set.collectAsStateWithLifecycle()
    val profile = set?.profiles?.get(profileId) ?: return
    val scope = rememberCoroutineScope()
    val c = Escriba.colors
    val opened by produceState<Pair<String, Long?>?>(null, profileId) { value = withContext(Dispatchers.IO) { app.profiles.readProfile(profileId) } }
    val ownProps = remember(opened) { opened?.let { Frontmatter.parse(it.first).first.properties } ?: linkedMapOf() }

    // Text lives in TextFieldState: typing updates the field alone, not the whole page.
    val name = remember(profile) { TextFieldState(profile.name) }
    val folder = remember(profile) { TextFieldState(profile.folder) }
    var continuous by remember(profile) { mutableStateOf(profile.continuous) }
    var favorite by remember(profile) { mutableStateOf(profile.favorite) }
    var saveAudio by remember(profile) { mutableStateOf(profile.saveAudio) }
    var diarization by remember(profile) { mutableStateOf(profile.diarization) }
    var engine by remember(profile) { mutableStateOf(profile.engine) }
    var tasks by remember(profile) { mutableStateOf(profile.tasks) }
    var discard by remember(profile) { mutableStateOf(profile.discardTrivial) }
    var icon by remember(profile) { mutableStateOf(profile.icon) }
    var color by remember(profile) { mutableStateOf(profile.color) }
    val prompt = remember(opened) { TextFieldState(opened?.first?.let { ProfileLoader.extractPrompt(Frontmatter.parse(it).first.body) } ?: profile.prompt) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    Page(padding) {
        (ownProps["inherits"] as? YamlValue.Text)?.value?.let { parent ->
            Text(stringResource(R.string.inherits_from, parent), style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
        }
        Overline(stringResource(R.string.general))
        EsCard {
            OutlinedTextField(name, label = { Text(stringResource(R.string.name)) }, lineLimits = TextFieldLineLimits.SingleLine, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(folder, label = { Text(stringResource(R.string.notes_folder)) }, lineLimits = TextFieldLineLimits.SingleLine, modifier = Modifier.fillMaxWidth(),
                supportingText = { Text(stringResource(R.string.notes_folder_hint)) })
            Text(stringResource(R.string.color), style = MaterialTheme.typography.labelLarge, color = c.ink)
            ColorPicker(color) { color = it }
            Text(stringResource(R.string.watch_icon), style = MaterialTheme.typography.labelLarge, color = c.ink)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
                ProfileLoader.ICONS.forEach { i ->
                    FilterChip(
                        selected = icon == i, onClick = { icon = i }, label = { Text(ICON_LABELS[i]?.let { stringResource(it) } ?: i) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = c.lapisSoft, selectedLabelColor = c.ink),
                    )
                }
            }
        }

        Overline(stringResource(R.string.recording), Modifier.padding(top = Space.s3))
        EsCard {
            ToggleRow(stringResource(R.string.continuous_mode), stringResource(R.string.continuous_mode_hint), continuous) { continuous = it }
            HorizontalDivider(color = c.rule)
            ToggleRow(stringResource(R.string.favorite), stringResource(R.string.favorite_hint), favorite) { favorite = it }
        }

        Overline(stringResource(R.string.processing), Modifier.padding(top = Space.s3))
        EsCard {
            Text(stringResource(R.string.asr_engine), style = MaterialTheme.typography.labelLarge, color = c.ink)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("whisper" to "Whisper", "parakeet" to "Parakeet").forEachIndexed { i, (id, label) ->
                    SegmentedButton(selected = engine == id, onClick = { engine = id }, shape = SegmentedButtonDefaults.itemShape(i, 2)) { Text(label) }
                }
            }
            Text(
                stringResource(if (engine == "parakeet") R.string.engine_parakeet_hint else R.string.engine_whisper_hint),
                style = MaterialTheme.typography.bodyMedium, color = c.inkMuted,
            )
            HorizontalDivider(color = c.rule)
            ToggleRow(stringResource(R.string.toggle_diarization), stringResource(R.string.toggle_diarization_hint), diarization) { diarization = it }
            HorizontalDivider(color = c.rule)
            ToggleRow(stringResource(R.string.toggle_tasks), stringResource(R.string.toggle_tasks_hint), tasks) { tasks = it }
            HorizontalDivider(color = c.rule)
            ToggleRow(stringResource(R.string.toggle_discard), stringResource(R.string.toggle_discard_hint), discard) { discard = it }
            HorizontalDivider(color = c.rule)
            ToggleRow(stringResource(R.string.toggle_save_audio), stringResource(R.string.toggle_save_audio_hint), saveAudio) { saveAudio = it }
        }

        Overline(stringResource(R.string.summary_instructions), Modifier.padding(top = Space.s3))
        // Bounded height: a long prompt scrolls inside the field instead of re-laying out the page on every line.
        OutlinedTextField(prompt, modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 360.dp), textStyle = MaterialTheme.typography.bodyLarge)

        error?.let { Text(it, color = c.danger, style = MaterialTheme.typography.bodyMedium) }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s2), modifier = Modifier.padding(top = Space.s2)) {
            Button(enabled = !saving, onClick = {
                saving = true
                scope.launch {
                    // Only the properties that changed are written: what's inherited stays inherited.
                    val changes = linkedMapOf<String, YamlValue>()
                    fun set(key: String, value: YamlValue, inherited: Any) {
                        val now = when (value) {
                            is YamlValue.Text -> value.value
                            is YamlValue.Bool -> value.value
                            else -> value
                        }
                        if (key in ownProps || now != inherited) changes[key] = value
                    }
                    set("name", YamlValue.Text(name.text.trim().toString()), profile.name)
                    set("folder", YamlValue.Text(folder.text.trim().toString()), profile.folder)
                    set("icon", YamlValue.Text(icon), profile.icon)
                    set("color", YamlValue.Text(color), profile.color)
                    set("mode", YamlValue.Text(if (continuous) ProfileLoader.MODE_CONTINUOUS else ProfileLoader.MODE_ON_DEMAND), profile.mode)
                    set("favorite", YamlValue.Bool(favorite), profile.favorite)
                    set("save_audio", YamlValue.Bool(saveAudio), profile.saveAudio)
                    set("diarization", YamlValue.Bool(diarization), profile.diarization)
                    set("engine", YamlValue.Text(engine), profile.engine)
                    set("tasks", YamlValue.Bool(tasks), profile.tasks)
                    set("discard_trivial", YamlValue.Bool(discard), profile.discardTrivial)
                    val own = opened?.first?.let { ProfileLoader.extractPrompt(Frontmatter.parse(it).first.body) }.orEmpty()
                    val text = prompt.text.toString()
                    val promptToSave = if (own.isEmpty() && text.trim() == profile.prompt.trim()) null else text
                    app.profiles.saveProfile(profile.id, changes, promptToSave, opened?.second)
                        .onSuccess { close() }
                        .onFailure { error = it.message; saving = false }
                }
            }) { Text(stringResource(R.string.save)) }
            OutlinedButton(onClick = close) { Text(stringResource(R.string.cancel)) }
        }
    }
}

/** The system's six profile colours, by their light value (the one saved in the vault). */
private val PROFILE_COLORS = listOf(
    R.string.color_pine to "#2E6B3A", R.string.color_lapis to "#2C4DA8", R.string.color_ochre to "#835400",
    R.string.color_plum to "#7A2F73", R.string.color_slate to "#3F5466", R.string.color_terracotta to "#9C3D1C",
)

@Composable
private fun ColorPicker(selected: String, onPick: (String) -> Unit) {
    val c = Escriba.colors
    val current = c.profileColor(selected)
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s3)) {
        PROFILE_COLORS.forEach { (name, hex) ->
            val swatch = c.profileColor(hex)
            val label = stringResource(R.string.color_named, stringResource(name))
            val isSelected = swatch == current
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(swatch)
                    .border(2.dp, if (isSelected) c.ink else c.paperRaised, CircleShape)
                    .clickable { onPick(hex) }
                    .semantics { contentDescription = label },
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
        title = { Text(stringResource(R.string.new_profile)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s3)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.name)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    supportingText = { Text(stringResource(R.string.new_profile_example)) })
                Text(stringResource(R.string.color), style = MaterialTheme.typography.labelLarge, color = c.ink)
                ColorPicker(color) { color = it }
                Text(stringResource(R.string.based_on), style = MaterialTheme.typography.labelLarge, color = c.ink)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s2), verticalArrangement = Arrangement.spacedBy(Space.s1)) {
                    val chip = FilterChipDefaults.filterChipColors(selectedContainerColor = c.lapisSoft, selectedLabelColor = c.ink)
                    FilterChip(selected = basedOn == null, onClick = { basedOn = null }, label = { Text(stringResource(R.string.none)) }, colors = chip)
                    profiles.forEach { p -> FilterChip(selected = basedOn == p.id, onClick = { basedOn = p.id }, label = { Text(p.name) }, colors = chip) }
                }
                Text(
                    stringResource(if (basedOn == null) R.string.new_profile_defaults else R.string.new_profile_inherits),
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
            }) { Text(stringResource(R.string.create)) }
        },
        dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.cancel)) } },
    )
}

private val ICON_LABELS = mapOf(
    "mic" to R.string.icon_mic, "briefcase" to R.string.icon_briefcase, "idea" to R.string.icon_idea, "home" to R.string.icon_home,
    "person" to R.string.icon_person, "group" to R.string.icon_group, "phone" to R.string.icon_phone, "car" to R.string.icon_car,
    "book" to R.string.icon_book, "star" to R.string.icon_star,
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

// --- Storage and retention ---------------------------------------------------

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
        Overline(stringResource(R.string.on_this_phone))
        EsCard {
            Text(stringResource(R.string.audio_mb, onPhone.sumOf { it.bytes } / 1_048_576.0), style = Escriba.type.metric, color = c.ink)
            Text(
                pluralStringResource(R.plurals.sessions_with_audio, onPhone.size, onPhone.size) + " " +
                    stringResource(R.string.phone_retention, set?.config?.phoneAudioRetentionDays ?: 7),
                style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
        }
        Overline(stringResource(R.string.in_the_vault), Modifier.padding(top = Space.s3))
        EsCard {
            Text(pluralStringResource(R.plurals.vault_audios, inVault.size, inVault.size), style = MaterialTheme.typography.titleSmall, color = c.ink)
            set?.profiles?.values?.forEach { p ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
                    ProfileDot(c.profileColor(p.color))
                    Text(p.name, style = MaterialTheme.typography.bodyMedium, color = c.ink, modifier = Modifier.weight(1f))
                    Text(
                        when {
                            !p.saveAudio -> stringResource(R.string.no_audio_saved)
                            p.audioRetentionDays == null -> stringResource(R.string.kept_forever)
                            else -> pluralStringResource(R.plurals.deleted_after_days, p.audioRetentionDays!!, p.audioRetentionDays!!)
                        },
                        style = MaterialTheme.typography.bodyMedium, color = c.inkMuted,
                    )
                }
            }
        }
        Text(stringResource(R.string.cleanup_hint), style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
        OutlinedButton(onClick = { RetentionWorker.runNow(app); ran = true }, enabled = !ran) { Text(stringResource(if (ran) R.string.cleanup_running else R.string.cleanup_now)) }
    }
}
