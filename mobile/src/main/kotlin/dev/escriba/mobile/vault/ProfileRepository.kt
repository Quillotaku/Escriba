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
 * Perfiles leídos de la bóveda. Los archivos de `_config/escriba/` son la única fuente de verdad:
 * la app los lee cada vez que los necesita (son pocos y pequeños) y, si los edita, escribe en
 * esos mismos archivos.
 */
class ProfileRepository(
    context: Context,
    private val vault: VaultAccess,
    private val link: DataLayerLink,
) {
    private val prefs = context.applicationContext.getSharedPreferences("perfiles", Context.MODE_PRIVATE)
    private val mutex = Mutex()

    private val _set = MutableStateFlow<ProfileSet?>(null)
    val set: StateFlow<ProfileSet?> = _set

    init {
        link.handle(Protocol.PATH_PROFILES_REQUEST) { event ->
            link.openSealed<ProfilesRequest>(event) // solo el reloj emparejado puede pedirlos
            AppLog.i(TAG, "El reloj pide los perfiles")
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

    /** Envía al reloj la lista de perfiles si ha cambiado desde el último envío (o siempre con [force]). */
    suspend fun syncToWatch(force: Boolean = false): Boolean {
        val set = _set.value ?: reload() ?: return false
        if (set.profiles.isEmpty()) return false
        val list = set.forWatch()
        if (!force && prefs.getString(KEY_SENT, null) == list.hash) return true
        val sent = runCatching { link.sendSealed(Protocol.PATH_PROFILES, list) }
            .onFailure { AppLog.e(TAG, "No se pudieron enviar los perfiles", it) }
            .getOrDefault(false)
        if (sent) {
            prefs.edit().putString(KEY_SENT, list.hash).apply()
            AppLog.i(TAG, "Perfiles enviados al reloj: ${list.profiles.joinToString { it.name }}")
        }
        return sent
    }

    fun template(path: String?): String? = path?.let { vault.readText(if (it.endsWith(".md")) it else "$it.md") }

    /** Texto actual de un perfil y su fecha de modificación (para detectar ediciones simultáneas). */
    fun readProfile(id: String): Pair<String, Long?>? {
        val path = "${VaultBootstrap.PROFILES_DIR}/$id.md"
        val text = vault.readText(path) ?: return null
        return text to vault.lastModified(path)
    }

    /**
     * Guarda los cambios hechos desde la app. Conserva las propiedades que la app no edita.
     * Si el archivo cambió desde que se abrió el editor (p. ej. en Obsidian), no sobrescribe.
     */
    suspend fun saveProfile(id: String, changes: Map<String, YamlValue>, prompt: String, openedAt: Long?): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val path = "${VaultBootstrap.PROFILES_DIR}/$id.md"
            val current = vault.readText(path)
            if (current != null && openedAt != null && (vault.lastModified(path) ?: 0) > openedAt) {
                error("El perfil se ha modificado en otro sitio (¿Obsidian?) mientras lo editabas. Vuelve a abrirlo.")
            }
            val (fm, _) = Frontmatter.parse(current ?: "")
            val props = LinkedHashMap(fm.properties)
            changes.forEach { (k, v) -> if (v == YamlValue.Empty) props.remove(k) else props[k] = v }
            vault.writeText(path, Frontmatter.note(props, replacePrompt(fm.body, prompt)))
            AppLog.i(TAG, "Perfil «$id» guardado desde la app")
            reload()
            syncToWatch()
            Unit
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
            "Ninguno. ✓\n"
        } else {
            buildString {
                for ((file, errors) in set.errors) {
                    append("## ").append(file).append('\n')
                    errors.forEach { append("- ").append(it).append('\n') }
                    append('\n')
                }
            }
        }
        val stamp = "Revisado: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
        val report = "# Errores de configuración\n\n$stamp\n\n$body"
        // Solo se escribe si cambia algo más que la fecha: no tiene sentido despertar a YAOS para nada.
        fun withoutStamp(text: String?) = text?.lines()?.filterNot { it.startsWith("Revisado: ") }?.joinToString("\n")
        if (withoutStamp(vault.readText(VaultBootstrap.ERRORS_FILE)) != withoutStamp(report)) {
            runCatching { vault.writeText(VaultBootstrap.ERRORS_FILE, report) }
        }
    }

    private companion object {
        const val TAG = "perfiles"
        const val KEY_SENT = "enviado"
    }
}
