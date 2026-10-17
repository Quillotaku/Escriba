package dev.escriba.mobile.vault

import android.content.Context
import dev.escriba.datalayer.AppLog
import dev.escriba.datalayer.DataLayerLink
import dev.escriba.shared.Protocol
import dev.escriba.shared.ProfilesRequest
import dev.escriba.shared.vault.Frontmatter
import dev.escriba.shared.vault.ProfileLoader
import dev.escriba.shared.vault.ProfileSet
import dev.escriba.shared.vault.YamlValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Profiles read from the vault. The files in `_config/escriba/` are the single source of truth:
 * the app reads them every time it needs them (they're few and small) and, if it edits them, it
 * writes to those same files.
 */
class ProfileRepository(
    context: Context,
    private val vault: VaultAccess,
    private val link: DataLayerLink,
) {
    private val prefs = context.applicationContext.getSharedPreferences("profiles", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    private val _set = MutableStateFlow<ProfileSet?>(null)
    val set: StateFlow<ProfileSet?> = _set

    init {
        link.handle(Protocol.PATH_PROFILES_REQUEST) { event ->
            link.openSealed<ProfilesRequest>(event) // only the paired watch can ask for them
            AppLog.i(TAG, "The watch asks for the profiles")
            reload()
            syncToWatch(force = true)
        }
    }

    suspend fun reload(): ProfileSet? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!vault.isConfigured) return@withLock null
            val config = vault.readText(VaultBootstrap.CONFIG_FILE)
            val files = vault.list(VaultBootstrap.PROFILES_DIR)
                .filter { !it.isDirectory && it.name.endsWith(".md") }
                .associate { it.name.removeSuffix(".md") to (vault.readText("${VaultBootstrap.PROFILES_DIR}/${it.name}") ?: "") }
            val set = ProfileLoader.load(config, files)
            writeErrorReport(set)
            _set.value = set
            set
        }
    }

    /** Sends the profile list to the watch if it changed since the last time (or always with [force]). */
    suspend fun syncToWatch(force: Boolean = false): Boolean {
        val set = _set.value ?: reload() ?: return false
        if (set.profiles.isEmpty()) return false
        val list = set.forWatch()
        if (!force && prefs.getString(KEY_SENT, null) == list.hash) return true
        val sent = runCatching { link.sendSealed(Protocol.PATH_PROFILES, list) }
            .onFailure { AppLog.e(TAG, "Couldn't send the profiles", it) }
            .getOrDefault(false)
        if (sent) {
            prefs.edit().putString(KEY_SENT, list.hash).apply()
            AppLog.i(TAG, "Profiles sent to the watch: ${list.profiles.joinToString { it.name }}")
        }
        return sent
    }

    fun template(path: String?): String? = path?.let { vault.readText(if (it.endsWith(".md")) it else "$it.md") }

    /** A profile's current text and modification date (to detect concurrent edits). */
    fun readProfile(id: String): Pair<String, Long?>? {
        val path = "${VaultBootstrap.PROFILES_DIR}/$id.md"
        val text = vault.readText(path) ?: return null
        return text to vault.lastModified(path)
    }

    /**
     * Saves the changes made from the app. It keeps the properties the app doesn't edit.
     * If the file changed since the editor was opened (e.g. in Obsidian), it doesn't overwrite.
     */
    suspend fun saveProfile(id: String, changes: Map<String, YamlValue>, prompt: String?, openedAt: Long?): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val path = "${VaultBootstrap.PROFILES_DIR}/$id.md"
            val current = vault.readText(path)
            if (current != null && openedAt != null && (vault.lastModified(path) ?: 0) > openedAt) {
                error("The profile was changed somewhere else (Obsidian?) while you were editing it. Open it again.")
            }
            val (fm, _) = Frontmatter.parse(current ?: "")
            val props = LinkedHashMap(fm.properties)
            changes.forEach { (k, v) -> if (v == YamlValue.Empty) props.remove(k) else props[k] = v }
            // null prompt: the body isn't touched (a child profile keeps inheriting its parent's prompt).
            vault.writeText(path, Frontmatter.note(props, if (prompt == null) fm.body else replacePrompt(fm.body, prompt)))
            AppLog.i(TAG, "Profile \"$id\" saved from the app")
            reload()
            syncToWatch()
            Unit
        }
    }

    /**
     * Creates a new profile in the vault and returns its id (the file name). If it's based on
     * another, it only carries `inherits:` and its own values; otherwise, sensible defaults.
     */
    suspend fun createProfile(name: String, basedOn: String?, color: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val clean = name.trim()
            require(clean.isNotEmpty()) { "Give the profile a name" }
            val base = profileSlug(clean).ifEmpty { "profile" }
            var id = base
            var n = 2
            while (vault.exists("${VaultBootstrap.PROFILES_DIR}/$id.md")) id = "$base-${n++}"
            val props = linkedMapOf<String, YamlValue>("name" to YamlValue.Text(clean), "color" to YamlValue.Text(color))
            val body = if (basedOn != null) {
                props["inherits"] = YamlValue.Text(basedOn)
                // Empty body: without its own "## Prompt", the prompt is inherited too.
                ""
            } else {
                props["icon"] = YamlValue.Text("mic")
                props["mode"] = YamlValue.Text(ProfileLoader.MODE_ON_DEMAND)
                props["folder"] = YamlValue.Text(clean)
                "## Prompt\nSummarize the conversation in a few lines and extract the decisions and tasks.\n"
            }
            vault.writeText("${VaultBootstrap.PROFILES_DIR}/$id.md", Frontmatter.note(props, body))
            AppLog.i(TAG, "Profile \"$id\" created from the app" + (basedOn?.let { " (inherits from $it)" } ?: ""))
            reload()
            syncToWatch()
            id
        }
    }

    private fun replacePrompt(body: String, prompt: String): String {
        val lines = body.lines()
        val start = lines.indexOfFirst { it.trim().equals("## Prompt", ignoreCase = true) }
        if (start < 0) return "## Prompt\n${prompt.trim()}\n" + if (body.isBlank()) "" else "\n$body"
        val end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("## ") } ?: lines.size
        return (lines.subList(0, start + 1) + prompt.trim() + "" + lines.subList(end, lines.size)).joinToString("\n").trimEnd() + "\n"
    }

    private fun writeErrorReport(set: ProfileSet) {
        val body = if (set.errors.isEmpty()) {
            "None. ✓\n"
        } else {
            buildString {
                for ((file, errors) in set.errors) {
                    append("## ").append(file).append('\n')
                    errors.forEach { append("- ").append(it).append('\n') }
                    append('\n')
                }
            }
        }
        val stamp = "Checked: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        val report = "# Configuration errors\n\n$stamp\n\n$body"
        // Only written if more than the date changed: no point waking up the sync tool for nothing.
        fun withoutStamp(text: String?) = text?.lines()?.filterNot { it.startsWith("Checked: ") }?.joinToString("\n")
        if (withoutStamp(vault.readText(VaultBootstrap.ERRORS_FILE)) != withoutStamp(report)) {
            runCatching { vault.writeText(VaultBootstrap.ERRORS_FILE, report) }
        }
    }

    private companion object {
        const val TAG = "profiles"
        const val KEY_SENT = "sent"
    }
}

/** "Acme Meetings" → "acme-meetings": a profile's id is its file name. */
internal fun profileSlug(text: String): String =
    java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
