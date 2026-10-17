package dev.escriba.mobile.vault

import dev.escriba.datalayer.AppLog
import dev.escriba.mobile.data.EscribaDb
import dev.escriba.mobile.data.NoteEntity
import dev.escriba.shared.EscribaJson
import dev.escriba.shared.Segment
import dev.escriba.shared.crypto.Crypto
import dev.escriba.shared.vault.Conversations
import dev.escriba.shared.vault.DailyNote
import dev.escriba.shared.vault.DailyNoteMode
import dev.escriba.shared.vault.FragmentInfo
import dev.escriba.shared.vault.NoteData
import dev.escriba.shared.vault.NoteRenderer
import dev.escriba.shared.vault.OggConcat
import dev.escriba.shared.vault.Profile
import dev.escriba.shared.transcription.TranscriptLine
import dev.escriba.shared.llm.EntityLinker
import dev.escriba.shared.llm.Insights
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Convierte una sesión recibida en notas de Obsidian: una por conversación.
 *
 * Reglas para no pisar el trabajo del usuario:
 *  - una nota nueva nunca sobrescribe un archivo que ya exista: se le añade «(2)»;
 *  - al reprocesar, si la nota sigue tal y como la escribió Escriba, se reescribe en el mismo
 *    archivo (los enlaces siguen valiendo); si el usuario la editó, se crea una versión nueva;
 *  - la nota diaria solo se toca en el modo `nota_diaria: enlace`, añadiendo una línea.
 */
class NoteWriter(
    private val vault: VaultAccess,
    private val profiles: ProfileRepository,
    private val db: EscribaDb,
) {
    private val mutex = Mutex()

    data class Outcome(val notes: List<String>, val profile: Profile)

    suspend fun process(
        sessionId: String,
        profileOverride: String? = null,
        transcript: List<TranscriptLine>? = null,
        insights: Map<Int, Insights>? = null,
    ): Outcome = mutex.withLock {
        val dao = db.sessions()
        try {
            check(vault.isConfigured) { "Elige la bóveda en la pestaña «Bóveda»" }
            val session = dao.session(sessionId) ?: error("La sesión no existe")
            val set = profiles.reload() ?: error("No se pudo leer la configuración de la bóveda")
            val profile = resolveProfile(set, session.profileId, profileOverride)
            val zone = runCatching { ZoneId.of(set.config.timeZone) }.getOrDefault(ZoneId.systemDefault())

            val fragments = dao.fragments(sessionId)
            check(fragments.isNotEmpty()) { "La sesión no tiene fragmentos" }
            val conversations = Conversations.split(
                fragments.map { f -> FragmentInfo(f.seq, f.startMs, f.endMs, EscribaJson.decodeFromString<List<Segment>>(f.segmentsJson)) },
                profile.splitAfterSilenceMin,
            )
            val previous = dao.notes(sessionId).associateBy { it.conversation }
            val template = profiles.template(profile.template)
            if (profile.template != null && template == null) AppLog.e(TAG, "Plantilla «${profile.template}» no encontrada; se usa la de serie")

            val linker = linker(set.config, profile)
            val written = ArrayList<String>()
            for (conversation in conversations) {
                val found = insights?.get(conversation.index)
                if (found != null && found.trivial && profile.discardTrivial) {
                    AppLog.i(TAG, "Conversación ${conversation.index} de ${sessionId.take(8)} sin contenido útil: no se escribe nota (descartar_triviales)")
                    continue
                }
                // Las frases de esta conversación: las que empiezan dentro de su intervalo.
                val lines = transcript?.filter { it.wallStartMs >= conversation.startMs - 1_000 && it.wallStartMs <= conversation.endMs + 1_000 }
                var base = NoteData(
                    sessionId, conversation, profile, zone,
                    transcript = lines?.let { if (it.isEmpty()) "_Sin voz reconocible._" else NoteRenderer.transcript(it, zone) },
                    status = if (transcript != null) "transcrita" else "pendiente",
                )
                if (found != null) base = withInsights(base, found, profile, linker)
                val folder = NoteRenderer.folder(profile.folder, conversation.startMs, zone)
                val baseName = NoteRenderer.baseName(base)

                val audioPath = if (profile.saveAudio) writeAudio(set.config.attachmentsFolder, baseName, conversation.fragments.map { it.seq }, fragments) else null
                val data = base.copy(audioLink = audioPath)
                val text = NoteRenderer.render(data, template)
                val path = choosePath(previous[conversation.index], "$folder/$baseName")

                vault.writeText(path, text)
                dao.upsertNote(
                    NoteEntity(
                        sessionId = sessionId,
                        conversation = conversation.index,
                        path = path,
                        contentHash = hash(text),
                        audioPath = audioPath,
                        profileId = profile.id,
                        startMs = conversation.startMs,
                        writtenAtMs = System.currentTimeMillis(),
                    ),
                )
                if (profile.dailyNote == DailyNoteMode.ENLACE) linkFromDailyNote(set.config.dailyFolder, set.config.dailyFormat, path, conversation.startMs, zone)
                written += path
                AppLog.i(TAG, "Nota escrita: $path")
            }
            dao.deleteNotesAfter(sessionId, conversations.size)
            dao.upsertSession(session.copy(processedAtMs = System.currentTimeMillis(), processedProfileId = profile.id, processError = null))
            Outcome(written, profile)
        } catch (e: Exception) {
            dao.session(sessionId)?.let { dao.upsertSession(it.copy(processError = e.message ?: e.javaClass.simpleName)) }
            throw e
        }
    }

    /** Rellena la nota con lo que sacó el LLM: título, resumen, decisiones, tareas, participantes y etiquetas. */
    private fun withInsights(base: NoteData, i: Insights, profile: Profile, linker: EntityLinker): NoteData {
        val linkPeople = "personas" in profile.link
        fun person(name: String) = when {
            name.equals("yo", ignoreCase = true) -> "Yo"
            linkPeople -> linker.linkOrPlain(name)
            else -> name.trim()
        }
        val summary = buildString {
            append(i.summary.trim().ifEmpty { "_Sin resumen._" })
            if (i.decisions.isNotEmpty()) {
                append("\n\n**Decisiones**\n")
                i.decisions.forEach { append("- ").append(it.trim()).append('\n') }
            }
        }.trimEnd()
        // Formato del plugin Tasks: «- [ ] texto 📅 AAAA-MM-DD».
        val tasks = if (!profile.tasks) "" else i.tasks.joinToString("\n") { t ->
            buildString {
                append("- [ ] ").append(t.text.trim())
                t.owner?.takeIf { it.isNotBlank() }?.let { append(" — ").append(person(it)) }
                t.due?.let { append(" 📅 ").append(it) }
            }
        }
        val participants = i.people.map(::person).distinct()
        val client = if ("clientes" in profile.link) i.clients.firstNotNullOfOrNull { linker.match(it) }?.let { "[[$it]]" } else null
        val tags = i.tags.map { it.trim().lowercase().replace(Regex("[\\s#]+"), "-") }.filter { it.isNotEmpty() }
        return base.copy(
            title = i.title.trim().ifEmpty { base.title },
            summary = summary,
            tasks = tasks,
            participants = participants,
            client = client,
            tags = tags,
            status = "resumida",
        )
    }

    /** Enlazador con las notas de personas, proyectos y clientes que ya existen en la bóveda. */
    private fun linker(config: dev.escriba.shared.vault.GlobalConfig, profile: Profile): EntityLinker {
        if (profile.link.isEmpty()) return EntityLinker(emptyList())
        val folders = buildList {
            if ("personas" in profile.link) add(config.peopleFolder)
            if ("proyectos" in profile.link) add(config.projectsFolder)
            if ("clientes" in profile.link) add(config.clientsFolder)
        }
        val names = folders.flatMap { folder ->
            runCatching { vault.list(folder).filter { !it.isDirectory && it.name.endsWith(".md") }.map { it.name.removeSuffix(".md") } }.getOrDefault(emptyList())
        }
        return EntityLinker(names)
    }

    /** El perfil con el que se escribe: el pedido, el de la grabación o, si no existe, el de por defecto. */
    fun resolveProfile(set: dev.escriba.shared.vault.ProfileSet, recorded: String, override: String?): Profile =
        (override ?: recorded).let { set.profiles[it] }
            ?: set.defaultProfile?.also { AppLog.i(TAG, "Perfil «$recorded» no encontrado; se usa «${it.id}»") }
            ?: error("No hay ningún perfil válido en la bóveda")

    /** Ruta definitiva de la nota, sin pisar ediciones del usuario ni notas ajenas. */
    private fun choosePath(previous: NoteEntity?, wanted: String): String {
        if (previous != null) {
            val current = vault.readText(previous.path)
            if (current == null || hash(current) == previous.contentHash) return previous.path
            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm"))
            AppLog.i(TAG, "La nota ${previous.path} se editó a mano: se crea una versión reprocesada")
            return free("${previous.path.removeSuffix(".md")} (reprocesado $stamp)")
        }
        return free(wanted)
    }

    private fun free(withoutExtension: String): String {
        if (!vault.exists("$withoutExtension.md")) return "$withoutExtension.md"
        var n = 2
        while (vault.exists("$withoutExtension ($n).md")) n++
        return "$withoutExtension ($n).md"
    }

    private fun writeAudio(folder: String, baseName: String, seqs: List<Int>, fragments: List<dev.escriba.mobile.data.FragmentEntity>): String? {
        val files = fragments.filter { it.seq in seqs }.sortedBy { it.seq }.map { File(it.path) }
        if (files.any { !it.exists() }) {
            AppLog.e(TAG, "El audio de esta sesión ya no está en el móvil (retención); la nota irá sin audio")
            return null
        }
        val path = "$folder/$baseName.ogg"
        vault.writeBytes(path, OggConcat.concat(files.map { it.readBytes() }))
        return path
    }

    private fun linkFromDailyNote(folder: String, format: String, notePath: String, startMs: Long, zone: ZoneId) {
        val name = DailyNote.fileName(startMs, zone, format)
        val path = "$folder/$name.md"
        val updated = DailyNote.addLink(vault.readText(path), notePath.substringAfterLast('/').removeSuffix(".md"), name)
        vault.writeText(path, updated)
    }

    private fun hash(text: String) = Crypto.hex(Crypto.sha256(text.encodeToByteArray()))

    private companion object {
        const val TAG = "notas"
    }
}
