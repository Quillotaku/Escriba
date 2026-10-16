package dev.escriba.shared.vault

import dev.escriba.shared.Segment
import dev.escriba.shared.audio.OggOpusWriter
import dev.escriba.shared.audio.OggReader
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/** Fragmento recibido, con lo necesario para agrupar y escribir notas. */
data class FragmentInfo(val seq: Int, val startMs: Long, val endMs: Long, val segments: List<Segment>)

/** Una conversación: fragmentos consecutivos sin silencios largos entre ellos. */
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
     * Parte una sesión en conversaciones donde hay un silencio de al menos [splitAfterMin].
     * Se corta entre fragmentos: un fragmento se cierra tras 30 s de silencio, así que un hueco
     * de minutos siempre cae entre dos.
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

    /** Duración redondeada hacia arriba al múltiplo de [roundingMin] (mínimo uno). */
    fun roundedMinutes(durationMs: Long, roundingMin: Int): Int {
        val minutes = Math.ceil(durationMs / 60_000.0).toInt().coerceAtLeast(1)
        val step = roundingMin.coerceAtLeast(1)
        return ((minutes + step - 1) / step) * step
    }
}

/** Todo lo que necesita una nota. En la fase 4 el texto es provisional; en las fases 6–7 llega la IA. */
data class NoteData(
    val sessionId: String,
    val conversation: Conversation,
    val profile: Profile,
    val zone: ZoneId,
    val title: String = "Grabación",
    val summary: String = "_Pendiente: el resumen llegará con la IA local (fase 7)._",
    val tasks: String = "",
    val transcript: String? = null,
    val participants: List<String> = emptyList(),
    val audioLink: String? = null,
    val status: String = "pendiente",
)

object NoteRenderer {
    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val TIME = DateTimeFormatter.ofPattern("HH:mm")
    private val FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm")
    private val FORBIDDEN = Regex("[\\\\/:*?\"<>|#^\\[\\]]")

    val DEFAULT_TEMPLATE = """
        # {{titulo}}

        ## Resumen
        {{resumen}}

        {{tareas}}

        {{audio}}

        ## Transcripción
        {{transcripcion}}
    """.trimIndent() + "\n"

    fun at(ms: Long, zone: ZoneId): ZonedDateTime = Instant.ofEpochMilli(ms).atZone(zone)

    /** Carpeta del perfil con sus marcadores de fecha resueltos. */
    fun folder(pattern: String, ms: Long, zone: ZoneId): String {
        val t = at(ms, zone)
        return pattern
            .replace("{{yyyy}}", "%04d".format(t.year))
            .replace("{{MM}}", "%02d".format(t.monthValue))
            .replace("{{dd}}", "%02d".format(t.dayOfMonth))
            .split('/').map(::sanitize).filter { it.isNotEmpty() }.joinToString("/")
    }

    /** Nombre de archivo sin extensión, p. ej. `2026-10-16 1032 Trabajo – Revisión`. */
    fun baseName(data: NoteData): String {
        val start = at(data.conversation.startMs, data.zone).format(FILE_TIME)
        val title = data.title.takeIf { it.isNotBlank() && it != "Grabación" }?.let { " – $it" } ?: ""
        return sanitize("$start ${data.profile.name}$title").take(120).trim()
    }

    fun sanitize(name: String): String = name.replace(FORBIDDEN, " ").replace(Regex("\\s+"), " ").trim().trim('.')

    /** Frontmatter compatible con Dataview: es lo que permite listar las grabaciones en la nota diaria. */
    fun properties(data: NoteData): LinkedHashMap<String, YamlValue> {
        val c = data.conversation
        val start = at(c.startMs, data.zone)
        val end = at(c.endMs, data.zone)
        return linkedMapOf<String, YamlValue>().apply {
            put("tipo", YamlValue.Text("grabacion"))
            put("fecha", YamlValue.Text(start.format(DATE)))
            put("inicio", YamlValue.Text(start.format(TIME)))
            put("fin", YamlValue.Text(end.format(TIME)))
            put("duracion_min", YamlValue.Number(Conversations.roundedMinutes(c.endMs - c.startMs, data.profile.roundingMin).toDouble()))
            put("duracion_habla_min", YamlValue.Number(Math.ceil(c.speechMs / 60_000.0)))
            put("perfil", YamlValue.Text(data.profile.id))
            data.profile.client?.let { put("cliente", YamlValue.Text(it)) }
            put("participantes", if (data.participants.isEmpty()) YamlValue.Empty else YamlValue.Items(data.participants))
            put("etiquetas", YamlValue.Items(listOf("escriba", data.profile.id)))
            data.audioLink?.let { put("audio", YamlValue.Text("[[$it]]")) }
            put("sesion", YamlValue.Text(data.sessionId))
            put("conversacion", YamlValue.Number(c.index.toDouble()))
            put("estado", YamlValue.Text(data.status))
            put("escriba", YamlValue.Number(1.0))
        }
    }

    fun render(data: NoteData, template: String?): String {
        val vars = mapOf(
            "titulo" to data.title,
            "resumen" to data.summary,
            "tareas" to if (data.tasks.isBlank()) "" else "## Tareas\n${data.tasks}",
            "audio" to (data.audioLink?.let { "## Audio\n![[$it]]" } ?: ""),
            "transcripcion" to (data.transcript ?: placeholderTranscript(data)),
            "fecha" to at(data.conversation.startMs, data.zone).format(DATE),
            "inicio" to at(data.conversation.startMs, data.zone).format(TIME),
            "fin" to at(data.conversation.endMs, data.zone).format(TIME),
            "perfil" to data.profile.name,
            "cliente" to (data.profile.client ?: "—"),
            "participantes" to data.participants.joinToString(", ").ifEmpty { "—" },
            "duracion" to Conversations.roundedMinutes(data.conversation.endMs - data.conversation.startMs, data.profile.roundingMin).toString(),
        )
        var body = template?.takeIf { it.isNotBlank() } ?: DEFAULT_TEMPLATE
        for ((k, v) in vars) body = body.replace("{{$k}}", v)
        // Las variables vacías dejan huecos: como mucho una línea en blanco seguida.
        body = body.replace(Regex("\n{3,}"), "\n\n")
        return Frontmatter.note(properties(data), body)
    }

    /**
     * Transcripción en párrafos: Whisper corta en cada pausa pequeña («el», «del reloj» sueltos),
     * así que las frases seguidas se unen y solo se pone la hora al principio de cada párrafo.
     * Un párrafo nuevo empieza tras una pausa de [pauseMs], al cambiar de hablante o al pasar
     * de [maxChars] caracteres.
     */
    fun transcript(
        lines: List<dev.escriba.shared.transcription.TranscriptLine>,
        zone: ZoneId,
        pauseMs: Long = 4_000,
        maxChars: Int = 400,
    ): String {
        val time = DateTimeFormatter.ofPattern("HH:mm:ss")
        val paragraphs = ArrayList<Pair<dev.escriba.shared.transcription.TranscriptLine, StringBuilder>>()
        var lastEnd = Long.MIN_VALUE
        for (l in lines) {
            val current = paragraphs.lastOrNull()
            val startNew = current == null ||
                l.wallStartMs - lastEnd >= pauseMs ||
                l.speaker != current.first.speaker ||
                current.second.length + l.text.length > maxChars
            if (startNew) paragraphs += l to StringBuilder(l.text) else current!!.second.append(' ').append(l.text)
            lastEnd = l.wallEndMs
        }
        return paragraphs.joinToString("\n\n") { (first, text) ->
            val t = at(first.wallStartMs, zone).format(time)
            (first.speaker?.let { "**$t · $it** " } ?: "**$t** ") + text
        }
    }

    /** Mientras no hay transcripción: los tramos de voz con su hora real. */
    private fun placeholderTranscript(data: NoteData): String =
        data.conversation.segments.joinToString("\n") { s ->
            val t = at(s.wallStartMs, data.zone).format(TIME)
            "- **$t** · ${(s.durationMs + 999) / 1000} s de voz _(transcripción pendiente)_"
        }
}

/** Enlace a la grabación en la nota diaria (modo `nota_diaria: enlace`). */
object DailyNote {
    const val SECTION = "## Grabaciones"

    /** Consulta Dataview para la plantilla de la nota diaria (modo por defecto). */
    val DATAVIEW_QUERY = """
        ```dataview
        TABLE WITHOUT ID file.link AS "Grabación", inicio AS "Hora", duracion_min AS "Min", perfil AS "Perfil"
        WHERE tipo = "grabacion" AND fecha = this.file.day
        SORT inicio ASC
        ```
    """.trimIndent()

    fun fileName(dateMs: Long, zone: ZoneId, format: String): String {
        val t = NoteRenderer.at(dateMs, zone)
        return if (format == "DD-MM-YYYY") "%02d-%02d-%04d".format(t.dayOfMonth, t.monthValue, t.year)
        else "%04d-%02d-%02d".format(t.year, t.monthValue, t.dayOfMonth)
    }

    /** Añade `- [[enlace]]` bajo la sección de grabaciones, sin duplicar y sin tocar el resto. */
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

/** Une los fragmentos de una conversación en un único Ogg/Opus para la bóveda. */
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
