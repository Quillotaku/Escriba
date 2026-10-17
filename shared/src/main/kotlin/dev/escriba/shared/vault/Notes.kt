package dev.escriba.shared.vault

import dev.escriba.shared.Segment
import dev.escriba.shared.audio.OggOpusWriter
import dev.escriba.shared.audio.OggReader
import dev.escriba.shared.transcription.TranscriptLine
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/** A received fragment, with what's needed to group it and write notes. */
data class FragmentInfo(val seq: Int, val startMs: Long, val endMs: Long, val segments: List<Segment>)

/** A conversation: consecutive fragments without long silences between them. */
data class Conversation(
    val index: Int,
    val fragments: List<FragmentInfo>,
) {
    val startMs: Long get() = fragments.first().startMs
    val endMs: Long get() = fragments.last().endMs
    val speechMs: Long get() = fragments.sumOf { f -> f.segments.sumOf { it.durationMs } }
    val segments: List<Segment> get() = fragments.flatMap { it.segments }
}

object Conversations {
    /**
     * Splits a session into conversations wherever there's a silence of at least [splitAfterMin].
     * The cut falls between fragments: a fragment closes after 30 s of silence, so a gap of
     * minutes always lands between two.
     */
    fun split(fragments: List<FragmentInfo>, splitAfterMin: Int): List<Conversation> {
        val sorted = fragments.sortedBy { it.seq }
        if (sorted.isEmpty()) return emptyList()
        val thresholdMs = splitAfterMin.coerceAtLeast(1) * 60_000L
        val groups = ArrayList<MutableList<FragmentInfo>>()
        for (f in sorted) {
            val last = groups.lastOrNull()?.last()
            if (last == null || f.startMs - last.endMs >= thresholdMs) groups += mutableListOf(f) else groups.last() += f
        }
        return groups.mapIndexed { i, g -> Conversation(i + 1, g) }
    }

    /**
     * Duration rounded to the nearest multiple of [roundingMin] (by default, to the minute). Never
     * less than one step: a 20 s conversation counts as 1 min, not 0.
     */
    fun roundedMinutes(durationMs: Long, roundingMin: Int = 1): Int {
        val step = roundingMin.coerceAtLeast(1)
        val steps = Math.round(durationMs / 60_000.0 / step).toInt().coerceAtLeast(1)
        return steps * step
    }
}

/** Everything a note needs. Until the conversation is processed, the text is a placeholder. */
data class NoteData(
    val sessionId: String,
    val conversation: Conversation,
    val profile: Profile,
    val zone: ZoneId,
    val title: String = NoteRenderer.UNTITLED,
    val summary: String = "_Pending: the summary will be written once the conversation is processed._",
    val tasks: String = "",
    val transcript: String? = null,
    val participants: List<String> = emptyList(),
    val audioLink: String? = null,
    val status: String = NoteRenderer.STATUS_PENDING,
    /** Extra tags (the LLM's); `escriba` and the profile are always there. */
    val tags: List<String> = emptyList(),
    /** Detected client; the profile's client takes priority. */
    val client: String? = null,
)

object NoteRenderer {
    const val UNTITLED = "Recording"
    const val STATUS_PENDING = "pending"
    const val STATUS_TRANSCRIBED = "transcribed"
    const val STATUS_SUMMARIZED = "summarized"

    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val TIME = DateTimeFormatter.ofPattern("HH:mm")
    private val FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm")
    private val FORBIDDEN = Regex("[\\\\/:*?\"<>|#^\\[\\]]")

    val DEFAULT_TEMPLATE = """
        # {{title}}

        ## Summary
        {{summary}}

        {{tasks}}

        {{audio}}

        ## Transcript
        {{transcript}}
    """.trimIndent() + "\n"

    fun at(ms: Long, zone: ZoneId): ZonedDateTime = Instant.ofEpochMilli(ms).atZone(zone)

    /** The profile's folder with its date placeholders filled in. */
    fun folder(pattern: String, ms: Long, zone: ZoneId): String {
        val t = at(ms, zone)
        return pattern
            .replace("{{yyyy}}", "%04d".format(t.year))
            .replace("{{MM}}", "%02d".format(t.monthValue))
            .replace("{{dd}}", "%02d".format(t.dayOfMonth))
            .split('/').map(::sanitize).filter { it.isNotEmpty() }.joinToString("/")
    }

    /** File name without extension, e.g. `2026-10-16 1032 Work – Quarterly review`. */
    fun baseName(data: NoteData): String {
        val start = at(data.conversation.startMs, data.zone).format(FILE_TIME)
        val title = data.title.takeIf { it.isNotBlank() && it != UNTITLED }?.let { " – $it" } ?: ""
        return sanitize("$start ${data.profile.name}$title").take(120).trim()
    }

    fun sanitize(name: String): String = name.replace(FORBIDDEN, " ").replace(Regex("\\s+"), " ").trim().trim('.')

    /** Dataview-friendly frontmatter: it's what lets the daily note list the recordings. */
    fun properties(data: NoteData): LinkedHashMap<String, YamlValue> {
        val c = data.conversation
        val start = at(c.startMs, data.zone)
        val end = at(c.endMs, data.zone)
        return linkedMapOf<String, YamlValue>().apply {
            put("type", YamlValue.Text("recording"))
            put("date", YamlValue.Text(start.format(DATE)))
            put("start", YamlValue.Text(start.format(TIME)))
            put("end", YamlValue.Text(end.format(TIME)))
            put("duration_min", YamlValue.Number(Conversations.roundedMinutes(c.endMs - c.startMs, data.profile.roundingMin).toDouble()))
            put("speech_min", YamlValue.Number(Math.ceil(c.speechMs / 60_000.0)))
            put("profile", YamlValue.Text(data.profile.id))
            (data.profile.client ?: data.client)?.let { put("client", YamlValue.Text(it)) }
            put("participants", if (data.participants.isEmpty()) YamlValue.Empty else YamlValue.Items(data.participants))
            put("tags", YamlValue.Items((listOf("escriba", data.profile.id) + data.tags).distinct()))
            data.audioLink?.let { put("audio", YamlValue.Text("[[$it]]")) }
            put("session", YamlValue.Text(data.sessionId))
            put("conversation", YamlValue.Number(c.index.toDouble()))
            put("status", YamlValue.Text(data.status))
            put("escriba", YamlValue.Number(1.0))
        }
    }

    fun render(data: NoteData, template: String?): String {
        val vars = mapOf(
            "title" to data.title,
            "summary" to data.summary,
            "tasks" to if (data.tasks.isBlank()) "" else "## Tasks\n${data.tasks}",
            "audio" to (data.audioLink?.let { "## Audio\n![[$it]]" } ?: ""),
            "transcript" to (data.transcript ?: placeholderTranscript(data)),
            "date" to at(data.conversation.startMs, data.zone).format(DATE),
            "start" to at(data.conversation.startMs, data.zone).format(TIME),
            "end" to at(data.conversation.endMs, data.zone).format(TIME),
            "profile" to data.profile.name,
            "client" to (data.profile.client ?: data.client ?: "—"),
            "participants" to data.participants.joinToString(", ").ifEmpty { "—" },
            "duration" to Conversations.roundedMinutes(data.conversation.endMs - data.conversation.startMs, data.profile.roundingMin).toString(),
        )
        var body = template?.takeIf { it.isNotBlank() } ?: DEFAULT_TEMPLATE
        for ((k, v) in vars) body = body.replace("{{$k}}", v)
        // Empty variables leave gaps: at most one blank line in a row.
        body = body.replace(Regex("\n{3,}"), "\n\n")
        return Frontmatter.note(properties(data), body)
    }

    /**
     * Transcript in paragraphs: Whisper cuts at every small pause (lone "the", "of the watch"), so
     * consecutive sentences are joined and the time goes only at the start of each paragraph. A new
     * paragraph starts after a pause of [pauseMs], when the speaker changes or past [maxChars].
     */
    fun transcript(
        lines: List<TranscriptLine>,
        zone: ZoneId,
        pauseMs: Long = 4_000,
        maxChars: Int = 400,
    ): String {
        val time = DateTimeFormatter.ofPattern("HH:mm:ss")
        val paragraphs = ArrayList<Pair<TranscriptLine, StringBuilder>>()
        var lastEnd = Long.MIN_VALUE
        for (l in lines) {
            val current = paragraphs.lastOrNull()
            val startNew = current == null ||
                l.wallStartMs - lastEnd >= pauseMs ||
                l.speaker != current.first.speaker ||
                current.second.length + l.text.length > maxChars
            if (startNew) paragraphs += l to StringBuilder(l.text) else current.second.append(' ').append(l.text)
            lastEnd = l.wallEndMs
        }
        return paragraphs.joinToString("\n\n") { (first, text) ->
            val t = at(first.wallStartMs, zone).format(time)
            (first.speaker?.let { "**$t · $it** " } ?: "**$t** ") + text
        }
    }

    /** Until there's a transcript: the stretches of speech with their real time. */
    private fun placeholderTranscript(data: NoteData): String =
        data.conversation.segments.joinToString("\n") { s ->
            val t = at(s.wallStartMs, data.zone).format(TIME)
            "- **$t** · ${(s.durationMs + 999) / 1000} s of speech _(transcript pending)_"
        }
}

/** Link to the recording in the daily note (`daily_note: link` mode). */
object DailyNote {
    const val SECTION = "## Recordings"

    /** Dataview query for the daily note template (the default mode). */
    val DATAVIEW_QUERY = """
        ```dataview
        TABLE WITHOUT ID file.link AS "Recording", start AS "Time", duration_min AS "Min", profile AS "Profile"
        WHERE type = "recording" AND date = this.file.day
        SORT start ASC
        ```
    """.trimIndent()

    fun fileName(dateMs: Long, zone: ZoneId, format: String): String {
        val t = NoteRenderer.at(dateMs, zone)
        return if (format == "DD-MM-YYYY") "%02d-%02d-%04d".format(t.dayOfMonth, t.monthValue, t.year)
        else "%04d-%02d-%02d".format(t.year, t.monthValue, t.dayOfMonth)
    }

    /** Adds `- [[link]]` under the recordings section, without duplicates and without touching the rest. */
    fun addLink(existing: String?, link: String, title: String): String {
        val entry = "- [[$link]]"
        if (existing == null) return "# $title\n\n$SECTION\n$entry\n"
        if (existing.lines().any { it.trim() == entry }) return existing
        val lines = existing.trimEnd('\n').lines().toMutableList()
        val section = lines.indexOfFirst { it.trim() == SECTION }
        if (section < 0) return existing.trimEnd('\n') + "\n\n$SECTION\n$entry\n"
        var insertAt = section + 1
        while (insertAt < lines.size && lines[insertAt].trimStart().startsWith("- ")) insertAt++
        lines.add(insertAt, entry)
        return lines.joinToString("\n") + "\n"
    }
}

/** Joins a conversation's fragments into a single Ogg/Opus file for the vault. */
object OggConcat {
    fun concat(fragments: List<ByteArray>): ByteArray {
        val readers = fragments.map(OggReader::read)
        val first = readers.firstOrNull() ?: return ByteArray(0)
        val out = ByteArrayOutputStream()
        OggOpusWriter(out, serial = Random.nextInt(), preSkip = first.preSkip ?: 312, inputSampleRate = first.inputSampleRate ?: 16_000).use { w ->
            readers.forEach { r -> r.audioPackets.forEach(w::writePacket) }
        }
        return out.toByteArray()
    }
}
