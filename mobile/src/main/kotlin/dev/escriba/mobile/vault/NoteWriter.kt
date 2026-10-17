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
import dev.escriba.shared.voices.VoiceMath
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Turns a received session into Obsidian notes: one per conversation.
 *
 * Rules so the user's work is never overwritten:
 *  - a new note never overwrites an existing file: "(2)" is appended;
 *  - when reprocessing, if the note is still exactly as Escriba wrote it, it's rewritten in the
 *    same file (links keep working); if the user edited it, a new version is created;
 *  - the daily note is only touched in `daily_note: link` mode, by appending a line.
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
        /** Speakers recognized by their voice (without the "Speaker N" ones). */
        speakers: List<String> = emptyList(),
    ): Outcome = mutex.withLock {
        val dao = db.sessions()
        try {
            check(vault.isConfigured) { "Choose the vault in Settings → Vault" }
            val session = dao.session(sessionId) ?: error("The session doesn't exist")
            val set = profiles.reload() ?: error("Couldn't read the vault settings")
            val profile = resolveProfile(set, session.profileId, profileOverride)
            val zone = runCatching { ZoneId.of(set.config.timeZone) }.getOrDefault(ZoneId.systemDefault())

            val fragments = dao.fragments(sessionId)
            check(fragments.isNotEmpty()) { "The session has no fragments" }
            val conversations = Conversations.split(
                fragments.map { f -> FragmentInfo(f.seq, f.startMs, f.endMs, EscribaJson.decodeFromString<List<Segment>>(f.segmentsJson)) },
                profile.splitAfterSilenceMin,
            )
            val previous = dao.notes(sessionId).associateBy { it.conversation }
            val template = profiles.template(profile.template)
            if (profile.template != null && template == null) AppLog.e(TAG, "Template \"${profile.template}\" not found; using the built-in one")

            val linker = linker(set.config, profile)
            val written = ArrayList<String>()
            for (conversation in conversations) {
                val found = insights?.get(conversation.index)
                if (found != null && found.trivial && profile.discardTrivial) {
                    AppLog.i(TAG, "Conversation ${conversation.index} of ${sessionId.take(8)} has no useful content: no note written (discard_trivial)")
                    continue
                }
                // This conversation's lines: the ones that start within its interval.
                val lines = transcript?.filter { it.wallStartMs >= conversation.startMs - 1_000 && it.wallStartMs <= conversation.endMs + 1_000 }
                var base = NoteData(
                    sessionId, conversation, profile, zone,
                    transcript = lines?.let { if (it.isEmpty()) "_No recognizable speech._" else NoteRenderer.transcript(it, zone) },
                    status = if (transcript != null) NoteRenderer.STATUS_TRANSCRIBED else NoteRenderer.STATUS_PENDING,
                )
                if (found != null) base = withInsights(base, found, profile, linker)
                // Whoever speaks in this conversation, recognized by voice, is a certain participant.
                val heard = lines.orEmpty().mapNotNull { it.speaker }.filter { it in speakers }.distinct()
                    .map { if (it == VoiceMath.ME || "people" !in profile.link) it else linker.linkOrPlain(it) }
                if (heard.isNotEmpty()) base = base.copy(participants = (heard + base.participants).distinctBy { EntityLinker.key(it) })
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
                if (profile.dailyNote == DailyNoteMode.LINK) linkFromDailyNote(set.config.dailyFolder, set.config.dailyFormat, path, conversation.startMs, zone)
                written += path
                AppLog.i(TAG, "Note written: $path")
            }
            dao.deleteNotesAfter(sessionId, conversations.size)
            dao.upsertSession(session.copy(processedAtMs = System.currentTimeMillis(), processedProfileId = profile.id, processError = null))
            Outcome(written, profile)
        } catch (e: Exception) {
            dao.session(sessionId)?.let { dao.upsertSession(it.copy(processError = e.message ?: e.javaClass.simpleName)) }
            throw e
        }
    }

    /** Fills the note with what the LLM extracted: title, summary, decisions, tasks, participants and tags. */
    private fun withInsights(base: NoteData, i: Insights, profile: Profile, linker: EntityLinker): NoteData {
        val linkPeople = "people" in profile.link
        fun person(name: String) = when {
            name.equals(VoiceMath.ME, ignoreCase = true) -> VoiceMath.ME
            linkPeople -> linker.linkOrPlain(name)
            else -> name.trim()
        }
        val summary = buildString {
            append(i.summary.trim().ifEmpty { "_No summary._" })
            if (i.decisions.isNotEmpty()) {
                append("\n\n**Decisions**\n")
                i.decisions.forEach { append("- ").append(it.trim()).append('\n') }
            }
        }.trimEnd()
        // Tasks plugin format: "- [ ] text 📅 YYYY-MM-DD".
        val tasks = if (!profile.tasks) "" else i.tasks.joinToString("\n") { t ->
            buildString {
                append("- [ ] ").append(t.text.trim())
                t.owner?.takeIf { it.isNotBlank() }?.let { append(" — ").append(person(it)) }
                t.due?.let { append(" 📅 ").append(it) }
            }
        }
        val participants = i.people.map(::person).distinct()
        val client = if ("clients" in profile.link) i.clients.firstNotNullOfOrNull { linker.match(it) }?.let { "[[$it]]" } else null
        val tags = i.tags.map { it.trim().lowercase().replace(Regex("[\\s#]+"), "-") }.filter { it.isNotEmpty() }
        return base.copy(
            title = i.title.trim().ifEmpty { base.title },
            summary = summary,
            tasks = tasks,
            participants = participants,
            client = client,
            tags = tags,
            status = NoteRenderer.STATUS_SUMMARIZED,
        )
    }

    /** Linker with the people, project and client notes that already exist in the vault. */
    private fun linker(config: dev.escriba.shared.vault.GlobalConfig, profile: Profile): EntityLinker {
        if (profile.link.isEmpty()) return EntityLinker(emptyList())
        val folders = buildList {
            if ("people" in profile.link) add(config.peopleFolder)
            if ("projects" in profile.link) add(config.projectsFolder)
            if ("clients" in profile.link) add(config.clientsFolder)
        }
        val names = folders.flatMap { folder ->
            runCatching { vault.list(folder).filter { !it.isDirectory && it.name.endsWith(".md") }.map { it.name.removeSuffix(".md") } }.getOrDefault(emptyList())
        }
        return EntityLinker(names)
    }

    /** The profile used to write: the requested one, the recording's or, if it doesn't exist, the default one. */
    fun resolveProfile(set: dev.escriba.shared.vault.ProfileSet, recorded: String, override: String?): Profile =
        (override ?: recorded).let { set.profiles[it] }
            ?: set.defaultProfile?.also { AppLog.i(TAG, "Profile \"$recorded\" not found; using \"${it.id}\"") }
            ?: error("There's no valid profile in the vault")

    /** Final path of the note, without overwriting the user's edits or other notes. */
    private fun choosePath(previous: NoteEntity?, wanted: String): String {
        if (previous != null) {
            val current = vault.readText(previous.path)
            if (current == null || hash(current) == previous.contentHash) return previous.path
            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm"))
            AppLog.i(TAG, "The note ${previous.path} was edited by hand: creating a reprocessed version")
            return free("${previous.path.removeSuffix(".md")} (reprocessed $stamp)")
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
            AppLog.e(TAG, "This session's audio is no longer on the phone (retention); the note goes without audio")
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
        const val TAG = "notes"
    }
}
