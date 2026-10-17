package dev.escriba.shared.vault

import dev.escriba.shared.EscribaJson
import dev.escriba.shared.crypto.Crypto
import dev.escriba.shared.recording.DailyWindow
import kotlinx.serialization.Serializable

enum class DailyNoteMode { DATAVIEW, LINK, NONE }

/** A resolved profile (inheritance applied and validated). */
@Serializable
data class Profile(
    val id: String,
    val name: String,
    val color: String,
    val icon: String,
    val favorite: Boolean,
    /** [ProfileLoader.MODE_CONTINUOUS] or [ProfileLoader.MODE_ON_DEMAND]. */
    val mode: String,
    val vad: String,
    val splitAfterSilenceMin: Int,
    val folder: String,
    val template: String?,
    val whisper: String,
    /** "whisper" or "parakeet". */
    val engine: String,
    val llm: String,
    val language: String,
    val vocabulary: List<String>,
    val diarization: Boolean,
    val tasks: Boolean,
    val link: List<String>,
    val discardTrivial: Boolean,
    val saveAudio: Boolean,
    val audioRetentionDays: Int?,
    val roundingMin: Int,
    val dailyNote: DailyNoteMode,
    val client: String?,
    val prompt: String,
) {
    /** Fingerprint of the profile: changes when any setting changes. It travels with every fragment. */
    val hash: String get() = Crypto.hex(Crypto.sha256(EscribaJson.encodeToString(this).encodeToByteArray())).take(12)

    val continuous: Boolean get() = mode == ProfileLoader.MODE_CONTINUOUS

    fun summary() = WatchProfile(id, name, color, icon, favorite, mode, vad, hash)
}

/** What the watch needs to know about a profile. */
@Serializable
data class WatchProfile(
    val id: String,
    val name: String,
    val color: String,
    val icon: String,
    val favorite: Boolean,
    val mode: String,
    val vad: String,
    val hash: String,
) {
    val continuous: Boolean get() = mode == ProfileLoader.MODE_CONTINUOUS
}

/** Phone → watch message with the profile list and the settings that affect the watch. */
@Serializable
data class ProfileList(
    val profiles: List<WatchProfile>,
    val defaultId: String,
    val schedule: String,
) {
    val hash: String get() = Crypto.hex(Crypto.sha256(EscribaJson.encodeToString(this).encodeToByteArray())).take(12)
}

/** Global settings from `_config/escriba/escriba.md`. */
data class GlobalConfig(
    val schedule: DailyWindow = DailyWindow.DEFAULT,
    val timeZone: String = "Europe/Madrid",
    val dailyFolder: String = "Daily",
    val dailyFormat: String = "YYYY-MM-DD",
    val peopleFolder: String = "People",
    val projectsFolder: String = "Projects",
    val clientsFolder: String = "Clients",
    val attachmentsFolder: String = "Attachments/Escriba",
    val defaultProfile: String = "personal",
    val defaultWhisper: String = "large-v3-turbo-q8_0",
    val defaultLlm: String = "gemma-3-4b-it-Q4_K_M",
    val phoneAudioRetentionDays: Int = 7,
)

/** Result of reading every profile in the vault. */
data class ProfileSet(
    val config: GlobalConfig,
    val profiles: Map<String, Profile>,
    /** Errors per file (`escriba.md` or `profiles/<id>.md`). */
    val errors: Map<String, List<String>>,
) {
    val defaultProfile: Profile? get() = profiles[config.defaultProfile] ?: profiles.values.firstOrNull()

    fun forWatch() = ProfileList(
        profiles = profiles.values.sortedWith(compareByDescending<Profile> { it.favorite }.thenBy { it.name }).map { it.summary() },
        defaultId = defaultProfile?.id ?: "",
        schedule = config.schedule.toString().replace('–', '-'),
    )
}

/**
 * Reads, inherits and validates the profiles. A profile with errors is dropped without affecting
 * the others, and every error says which file and property it's in.
 */
object ProfileLoader {

    const val MODE_CONTINUOUS = "continuous"
    const val MODE_ON_DEMAND = "on_demand"

    val ICONS = setOf("mic", "briefcase", "idea", "home", "person", "group", "phone", "car", "book", "star")
    private val MODES = setOf(MODE_CONTINUOUS, MODE_ON_DEMAND)
    private val VADS = setOf("low", "medium", "high")
    private val LANGUAGES = setOf("es", "en", "auto")
    private val ENGINES = setOf("whisper", "parakeet")
    private val LINKS = setOf("people", "projects", "clients")
    private val ID = Regex("[a-z0-9][a-z0-9_-]{0,39}")
    private val COLOR = Regex("#[0-9A-Fa-f]{6}")

    private val KNOWN = setOf(
        "version", "name", "inherits", "color", "icon", "favorite", "mode", "vad", "split_after_silence", "folder",
        "template", "whisper", "engine", "llm", "language", "vocabulary", "diarization", "tasks", "link", "discard_trivial",
        "save_audio", "audio_retention", "rounding", "daily_note", "client",
    )

    private const val UNKNOWN_PROPERTY = "Unknown property"

    /** [profileFiles]: file name without extension → content. */
    fun load(configFile: String?, profileFiles: Map<String, String>): ProfileSet {
        val errors = LinkedHashMap<String, MutableList<String>>()
        val config = configFile?.let { parseConfig(it, errors.getOrPut(CONFIG_FILE) { ArrayList() }) } ?: GlobalConfig()

        val raw = LinkedHashMap<String, Frontmatter>()
        for ((name, text) in profileFiles) {
            val fileErrors = errors.getOrPut("profiles/$name.md") { ArrayList() }
            if (!ID.matches(name)) {
                fileErrors += "The file name must be a lowercase id without spaces (e.g. \"work-acme\")"
                continue
            }
            val (fm, yamlErrors) = Frontmatter.parse(text)
            fileErrors += yamlErrors.map { it.toString() }
            fm.properties.keys.filter { it !in KNOWN }.forEach { fileErrors += "$UNKNOWN_PROPERTY \"$it\" (ignored)" }
            if (yamlErrors.isEmpty()) raw[name] = fm
        }

        val profiles = LinkedHashMap<String, Profile>()
        for (id in raw.keys) {
            val fileErrors = errors.getOrPut("profiles/$id.md") { ArrayList() }
            val chain = inheritanceChain(id, raw, fileErrors) ?: continue
            resolve(id, chain, raw, config, fileErrors)?.let { profiles[id] = it }
        }
        if (configFile != null && config.defaultProfile !in profiles && profiles.isNotEmpty()) {
            errors.getOrPut(CONFIG_FILE) { ArrayList() } += "default_profile \"${config.defaultProfile}\" doesn't exist; using \"${profiles.keys.first()}\""
        }
        return ProfileSet(config, profiles, errors.filterValues { it.isNotEmpty() })
    }

    /** From the most general profile to the most specific. null if inheritance is impossible. */
    private fun inheritanceChain(id: String, raw: Map<String, Frontmatter>, errors: MutableList<String>): List<String>? {
        val chain = ArrayList<String>()
        var current: String? = id
        while (current != null) {
            if (current in chain) {
                errors += "Circular inheritance: ${(chain + current).joinToString(" → ")}"
                return null
            }
            val fm = raw[current]
            if (fm == null) {
                errors += "inherits: the profile \"$current\" doesn't exist or has errors"
                return null
            }
            chain += current
            current = fm.text("inherits")?.trim()?.takeIf { it.isNotEmpty() }
        }
        return chain.reversed()
    }

    private fun resolve(id: String, chain: List<String>, raw: Map<String, Frontmatter>, config: GlobalConfig, errors: MutableList<String>): Profile? {
        val merged = LinkedHashMap<String, YamlValue>()
        var prompt = ""
        for (link in chain) {
            val fm = raw.getValue(link)
            merged.putAll(fm.properties.filterKeys { it != "inherits" && (link == id || it != "name") })
            extractPrompt(fm.body).takeIf { it.isNotBlank() }?.let { prompt = it }
        }
        val before = errors.size
        val p = Reader(merged, errors)

        val name = p.text("name") ?: id.replaceFirstChar { it.uppercase() }
        if (name.length > 24) errors += "name: at most 24 characters (it's shown on the watch)"
        val profile = Profile(
            id = id,
            name = name,
            color = p.text("color", "#607D8B").also { if (!COLOR.matches(it)) errors += "color: use the #RRGGBB format" },
            icon = p.oneOf("icon", ICONS, "mic"),
            favorite = p.bool("favorite", false),
            mode = p.oneOf("mode", MODES, MODE_ON_DEMAND),
            vad = p.oneOf("vad", VADS, "medium"),
            splitAfterSilenceMin = p.minutes("split_after_silence", 10),
            folder = p.text("folder", "Recordings/{{yyyy}}/{{MM}}").trim('/'),
            template = p.text("template"),
            whisper = p.text("whisper", config.defaultWhisper),
            engine = p.oneOf("engine", ENGINES, "whisper"),
            llm = p.text("llm", config.defaultLlm),
            language = p.oneOf("language", LANGUAGES, "auto"),
            vocabulary = p.list("vocabulary"),
            diarization = p.bool("diarization", false),
            tasks = p.bool("tasks", false),
            link = p.list("link").also { l -> l.filter { it !in LINKS }.forEach { errors += "link: \"$it\" isn't valid (${LINKS.joinToString()})" } },
            discardTrivial = p.bool("discard_trivial", false),
            saveAudio = p.bool("save_audio", false),
            audioRetentionDays = p.days("audio_retention", 30),
            roundingMin = p.minutes("rounding", 1),
            dailyNote = when (p.oneOf("daily_note", setOf("dataview", "link", "none"), "dataview")) {
                "link" -> DailyNoteMode.LINK
                "none" -> DailyNoteMode.NONE
                else -> DailyNoteMode.DATAVIEW
            },
            client = p.text("client"),
            prompt = prompt,
        )
        // Unknown-property warnings don't invalidate the profile; everything else does.
        val blocking = errors.drop(before).filterNot { it.startsWith(UNKNOWN_PROPERTY) }
        return if (blocking.isEmpty()) profile else null
    }

    /** The prompt is the body's "## Prompt" section or, if there's none, the whole body. */
    fun extractPrompt(body: String): String {
        val lines = body.lines()
        val start = lines.indexOfFirst { it.trim().equals("## Prompt", ignoreCase = true) }
        if (start < 0) return body.trim()
        val end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("## ") } ?: lines.size
        return lines.subList(start + 1, end).joinToString("\n").trim()
    }

    private fun parseConfig(text: String, errors: MutableList<String>): GlobalConfig {
        val (fm, yamlErrors) = Frontmatter.parse(text)
        errors += yamlErrors.map { it.toString() }
        val p = Reader(fm.properties, errors)
        val d = GlobalConfig()
        return GlobalConfig(
            schedule = p.text("schedule")?.let { DailyWindow.parse(it) ?: null.also { errors += "schedule: use the 07:30-23:30 format" } } ?: d.schedule,
            timeZone = p.text("time_zone", d.timeZone),
            dailyFolder = p.text("daily_note_folder", d.dailyFolder).trim('/'),
            dailyFormat = p.oneOf("daily_note_format", setOf("YYYY-MM-DD", "DD-MM-YYYY"), d.dailyFormat),
            peopleFolder = p.text("people_folder", d.peopleFolder).trim('/'),
            projectsFolder = p.text("projects_folder", d.projectsFolder).trim('/'),
            clientsFolder = p.text("clients_folder", d.clientsFolder).trim('/'),
            attachmentsFolder = p.text("attachments_folder", d.attachmentsFolder).trim('/'),
            defaultProfile = p.text("default_profile", d.defaultProfile),
            defaultWhisper = p.text("default_whisper", d.defaultWhisper),
            defaultLlm = p.text("default_llm", d.defaultLlm),
            phoneAudioRetentionDays = p.days("phone_audio_retention", d.phoneAudioRetentionDays) ?: d.phoneAudioRetentionDays,
        )
    }

    /** Typed access to the properties, collecting readable errors. */
    private class Reader(private val props: Map<String, YamlValue>, private val errors: MutableList<String>) {
        fun text(key: String): String? = when (val v = props[key]) {
            null, YamlValue.Empty -> null
            is YamlValue.Text -> v.value.trim().takeIf { it.isNotEmpty() }
            is YamlValue.Number -> v.value.let { if (it == Math.floor(it)) it.toLong().toString() else it.toString() }
            else -> null.also { errors += "$key: must be text" }
        }

        fun text(key: String, default: String) = text(key) ?: default

        fun bool(key: String, default: Boolean): Boolean = when (val v = props[key]) {
            null, YamlValue.Empty -> default
            is YamlValue.Bool -> v.value
            else -> default.also { errors += "$key: must be true or false" }
        }

        fun list(key: String): List<String> = when (val v = props[key]) {
            null, YamlValue.Empty -> emptyList()
            is YamlValue.Items -> v.values.map { it.trim() }.filter { it.isNotEmpty() }
            is YamlValue.Text -> listOf(v.value.trim())
            else -> emptyList<String>().also { errors += "$key: must be a list" }
        }

        fun oneOf(key: String, allowed: Set<String>, default: String): String {
            val raw = text(key) ?: return default
            // Case-insensitive: "yyyy-mm-dd" is "YYYY-MM-DD". The canonical form is returned.
            allowed.firstOrNull { it.equals(raw.trim(), ignoreCase = true) }?.let { return it }
            errors += "$key: \"$raw\" isn't valid (${allowed.joinToString()})"
            return default
        }

        fun minutes(key: String, default: Int): Int {
            val raw = text(key) ?: return default
            return Durations.minutes(raw) ?: default.also { errors += "$key: use a format like 10m, 1h or 90s" }
        }

        fun days(key: String, default: Int?): Int? {
            val raw = text(key) ?: return default
            if (raw.lowercase() in setOf("never", "forever", "0")) return null
            // "0d" means the same as "0": it never expires (deleting right away makes no sense).
            return Durations.days(raw)?.takeIf { it > 0 } ?: if (Durations.days(raw) == 0) null else default.also { errors += "$key: use a format like 30d or \"forever\"" }
        }
    }

    const val CONFIG_FILE = "escriba.md"
}

object Durations {
    private val PATTERN = Regex("(\\d+)\\s*(s|m|min|h|d)?", RegexOption.IGNORE_CASE)

    private fun seconds(text: String): Long? {
        val m = PATTERN.matchEntire(text.trim()) ?: return null
        val n = m.groupValues[1].toLong()
        return when (m.groupValues[2].lowercase()) {
            "s" -> n
            "", "m", "min" -> n * 60
            "h" -> n * 3600
            "d" -> n * 86_400
            else -> null
        }
    }

    fun minutes(text: String): Int? = seconds(text)?.let { ((it + 59) / 60).toInt() }

    fun days(text: String): Int? {
        val m = PATTERN.matchEntire(text.trim()) ?: return null
        return if (m.groupValues[2].lowercase() == "d") m.groupValues[1].toInt() else null
    }
}
