package dev.escriba.shared.vault

import dev.escriba.shared.EscribaJson
import dev.escriba.shared.crypto.Crypto
import dev.escriba.shared.recording.DailyWindow
import kotlinx.serialization.Serializable

enum class DailyNoteMode { DATAVIEW, ENLACE, NINGUNA }

/** Perfil ya resuelto (herencia aplicada y validado). */
@Serializable
data class Profile(
    val id: String,
    val name: String,
    val color: String,
    val icon: String,
    val favorite: Boolean,
    val mode: String,
    val vad: String,
    val splitAfterSilenceMin: Int,
    val folder: String,
    val template: String?,
    val whisper: String,
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
    /** Huella del perfil: cambia si cambia cualquier ajuste. Viaja con cada fragmento. */
    val hash: String get() = Crypto.hex(Crypto.sha256(EscribaJson.encodeToString(this).encodeToByteArray())).take(12)

    fun summary() = WatchProfile(id, name, color, icon, favorite, mode, vad, hash)
}

/** Lo que el reloj necesita saber de un perfil. */
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
)

/** Mensaje móvil → reloj con la lista de perfiles y la configuración que afecta al reloj. */
@Serializable
data class ProfileList(
    val profiles: List<WatchProfile>,
    val defaultId: String,
    val schedule: String,
) {
    val hash: String get() = Crypto.hex(Crypto.sha256(EscribaJson.encodeToString(this).encodeToByteArray())).take(12)
}

/** Configuración global de `_config/escriba/escriba.md`. */
data class GlobalConfig(
    val schedule: DailyWindow = DailyWindow.DEFAULT,
    val timeZone: String = "Europe/Madrid",
    val dailyFolder: String = "Diario",
    val dailyFormat: String = "YYYY-MM-DD",
    val peopleFolder: String = "Personas",
    val projectsFolder: String = "Proyectos",
    val clientsFolder: String = "Clientes",
    val attachmentsFolder: String = "Adjuntos/Escriba",
    val defaultProfile: String = "casual",
    val defaultWhisper: String = "large-v3-turbo-q8_0",
    val phoneAudioRetentionDays: Int = 7,
)

/** Resultado de leer todos los perfiles de la bóveda. */
data class ProfileSet(
    val config: GlobalConfig,
    val profiles: Map<String, Profile>,
    /** Errores por archivo (`escriba.md` o `perfiles/<id>.md`). */
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
 * Lee, hereda y valida los perfiles. Un perfil con errores se descarta sin afectar a los demás,
 * y cada error dice en qué archivo y propiedad está.
 */
object ProfileLoader {

    val ICONS = setOf("mic", "maletin", "idea", "casa", "persona", "grupo", "telefono", "coche", "libro", "estrella")
    private val MODES = setOf("continuo", "bajo_demanda")
    private val VADS = setOf("baja", "media", "alta")
    private val LANGUAGES = setOf("es", "en", "auto")
    private val LINKS = setOf("personas", "proyectos", "clientes")
    private val ID = Regex("[a-z0-9][a-z0-9_-]{0,39}")
    private val COLOR = Regex("#[0-9A-Fa-f]{6}")

    private val KNOWN = setOf(
        "version", "nombre", "hereda", "color", "icono", "favorito", "modo", "vad", "separar_tras_silencio", "carpeta",
        "plantilla", "whisper", "idioma", "vocabulario", "diarizacion", "tareas", "enlazar", "descartar_triviales",
        "guardar_audio", "retencion_audio", "redondeo", "nota_diaria", "cliente",
    )

    /** [profileFiles]: nombre de archivo sin extensión → contenido. */
    fun load(configFile: String?, profileFiles: Map<String, String>): ProfileSet {
        val errors = LinkedHashMap<String, MutableList<String>>()
        val config = configFile?.let { parseConfig(it, errors.getOrPut(CONFIG_FILE) { ArrayList() }) } ?: GlobalConfig()

        val raw = LinkedHashMap<String, Frontmatter>()
        for ((name, text) in profileFiles) {
            val fileErrors = errors.getOrPut("perfiles/$name.md") { ArrayList() }
            if (!ID.matches(name)) {
                fileErrors += "El nombre del archivo debe ser un id en minúsculas, sin espacios (p. ej. «trabajo-acme»)"
                continue
            }
            val (fm, yamlErrors) = Frontmatter.parse(text)
            fileErrors += yamlErrors.map { it.toString() }
            fm.properties.keys.filter { it !in KNOWN }.forEach { fileErrors += "Propiedad desconocida «$it» (se ignora)" }
            if (yamlErrors.isEmpty()) raw[name] = fm
        }

        val profiles = LinkedHashMap<String, Profile>()
        for (id in raw.keys) {
            val fileErrors = errors.getOrPut("perfiles/$id.md") { ArrayList() }
            val chain = inheritanceChain(id, raw, fileErrors) ?: continue
            resolve(id, chain, raw, config, fileErrors)?.let { profiles[id] = it }
        }
        if (configFile != null && config.defaultProfile !in profiles && profiles.isNotEmpty()) {
            errors.getOrPut(CONFIG_FILE) { ArrayList() } += "perfil_por_defecto «${config.defaultProfile}» no existe; se usa «${profiles.keys.first()}»"
        }
        return ProfileSet(config, profiles, errors.filterValues { it.isNotEmpty() })
    }

    /** Del perfil más general al más concreto. null si la herencia es imposible. */
    private fun inheritanceChain(id: String, raw: Map<String, Frontmatter>, errors: MutableList<String>): List<String>? {
        val chain = ArrayList<String>()
        var current: String? = id
        while (current != null) {
            if (current in chain) {
                errors += "Herencia circular: ${(chain + current).joinToString(" → ")}"
                return null
            }
            val fm = raw[current]
            if (fm == null) {
                errors += "hereda: el perfil «$current» no existe o tiene errores"
                return null
            }
            chain += current
            current = fm.text("hereda")?.trim()?.takeIf { it.isNotEmpty() }
        }
        return chain.reversed()
    }

    private fun resolve(id: String, chain: List<String>, raw: Map<String, Frontmatter>, config: GlobalConfig, errors: MutableList<String>): Profile? {
        val merged = LinkedHashMap<String, YamlValue>()
        var prompt = ""
        for (link in chain) {
            val fm = raw.getValue(link)
            merged.putAll(fm.properties.filterKeys { it != "hereda" && (link == id || it != "nombre") })
            extractPrompt(fm.body).takeIf { it.isNotBlank() }?.let { prompt = it }
        }
        val before = errors.size
        val p = Reader(merged, errors)

        val name = p.text("nombre") ?: id.replaceFirstChar { it.uppercase() }
        if (name.length > 24) errors += "nombre: como mucho 24 caracteres (se ve en el reloj)"
        val profile = Profile(
            id = id,
            name = name,
            color = p.text("color", "#607D8B").also { if (!COLOR.matches(it)) errors += "color: usa el formato #RRGGBB" },
            icon = p.oneOf("icono", ICONS, "mic"),
            favorite = p.bool("favorito", false),
            mode = p.oneOf("modo", MODES, "bajo_demanda"),
            vad = p.oneOf("vad", VADS, "media"),
            splitAfterSilenceMin = p.minutes("separar_tras_silencio", 10),
            folder = p.text("carpeta", "Grabaciones/{{yyyy}}/{{MM}}").trim('/'),
            template = p.text("plantilla"),
            whisper = p.text("whisper", config.defaultWhisper),
            language = p.oneOf("idioma", LANGUAGES, "es"),
            vocabulary = p.list("vocabulario"),
            diarization = p.bool("diarizacion", false),
            tasks = p.bool("tareas", false),
            link = p.list("enlazar").also { l -> l.filter { it !in LINKS }.forEach { errors += "enlazar: «$it» no es válido (${LINKS.joinToString()})" } },
            discardTrivial = p.bool("descartar_triviales", false),
            saveAudio = p.bool("guardar_audio", false),
            audioRetentionDays = p.days("retencion_audio", 30),
            roundingMin = p.minutes("redondeo", 5),
            dailyNote = when (p.oneOf("nota_diaria", setOf("dataview", "enlace", "ninguna"), "dataview")) {
                "enlace" -> DailyNoteMode.ENLACE
                "ninguna" -> DailyNoteMode.NINGUNA
                else -> DailyNoteMode.DATAVIEW
            },
            client = p.text("cliente"),
            prompt = prompt,
        )
        // Los avisos de propiedades desconocidas no invalidan el perfil; el resto sí.
        val blocking = errors.drop(before).filterNot { it.startsWith("Propiedad desconocida") }
        return if (blocking.isEmpty()) profile else null
    }

    /** El prompt es la sección «## Prompt» del cuerpo o, si no la hay, el cuerpo entero. */
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
            schedule = p.text("horario")?.let { DailyWindow.parse(it) ?: null.also { errors += "horario: usa el formato 07:30-23:30" } } ?: d.schedule,
            timeZone = p.text("zona_horaria", d.timeZone),
            dailyFolder = p.text("nota_diaria_carpeta", d.dailyFolder).trim('/'),
            dailyFormat = p.oneOf("nota_diaria_formato", setOf("YYYY-MM-DD", "DD-MM-YYYY"), d.dailyFormat),
            peopleFolder = p.text("carpeta_personas", d.peopleFolder).trim('/'),
            projectsFolder = p.text("carpeta_proyectos", d.projectsFolder).trim('/'),
            clientsFolder = p.text("carpeta_clientes", d.clientsFolder).trim('/'),
            attachmentsFolder = p.text("carpeta_adjuntos", d.attachmentsFolder).trim('/'),
            defaultProfile = p.text("perfil_por_defecto", d.defaultProfile),
            defaultWhisper = p.text("whisper_por_defecto", d.defaultWhisper),
            phoneAudioRetentionDays = p.days("retencion_audio_movil", d.phoneAudioRetentionDays) ?: d.phoneAudioRetentionDays,
        )
    }

    /** Acceso tipado a las propiedades, acumulando errores legibles. */
    private class Reader(private val props: Map<String, YamlValue>, private val errors: MutableList<String>) {
        fun text(key: String): String? = when (val v = props[key]) {
            null, YamlValue.Empty -> null
            is YamlValue.Text -> v.value.trim().takeIf { it.isNotEmpty() }
            is YamlValue.Number -> v.value.let { if (it == Math.floor(it)) it.toLong().toString() else it.toString() }
            else -> null.also { errors += "$key: debe ser un texto" }
        }

        fun text(key: String, default: String) = text(key) ?: default

        fun bool(key: String, default: Boolean): Boolean = when (val v = props[key]) {
            null, YamlValue.Empty -> default
            is YamlValue.Bool -> v.value
            else -> default.also { errors += "$key: debe ser true o false" }
        }

        fun list(key: String): List<String> = when (val v = props[key]) {
            null, YamlValue.Empty -> emptyList()
            is YamlValue.Items -> v.values.map { it.trim() }.filter { it.isNotEmpty() }
            is YamlValue.Text -> listOf(v.value.trim())
            else -> emptyList<String>().also { errors += "$key: debe ser una lista" }
        }

        fun oneOf(key: String, allowed: Set<String>, default: String): String {
            val v = text(key)?.lowercase() ?: return default
            if (v in allowed) return v
            errors += "$key: «$v» no es válido (${allowed.joinToString()})"
            return default
        }

        fun minutes(key: String, default: Int): Int {
            val raw = text(key) ?: return default
            return Durations.minutes(raw) ?: default.also { errors += "$key: usa un formato como 10m, 1h o 90s" }
        }

        fun days(key: String, default: Int?): Int? {
            val raw = text(key) ?: return default
            if (raw.lowercase() in setOf("nunca", "siempre", "0")) return null
            return Durations.days(raw) ?: default.also { errors += "$key: usa un formato como 30d o «siempre»" }
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
