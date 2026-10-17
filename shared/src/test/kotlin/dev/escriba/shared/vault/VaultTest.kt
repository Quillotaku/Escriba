package dev.escriba.shared.vault

import dev.escriba.shared.Segment
import dev.escriba.shared.audio.OggOpusWriter
import dev.escriba.shared.audio.OggReader
import dev.escriba.shared.transcription.TranscriptLine
import java.io.ByteArrayOutputStream
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FrontmatterTest {

    @Test
    fun `reads what Obsidian's Properties view writes`() {
        val note = """
            ---
            name: Work
            inherits: base
            favorite: true
            rounding: 5
            schedule: "07:30-23:30"
            client: "[[Acme]]"
            link:
              - people
              - clients
            vocabulary: [Kubernetes, "SAP S/4", 'O''Brien']
            empty:
            # a comment
            language: en # another comment
            ---
            ## Prompt
            Write the minutes.
        """.trimIndent()
        val (fm, errors) = Frontmatter.parse(note)
        assertTrue(errors.isEmpty(), errors.toString())
        val p = fm.properties
        assertEquals(YamlValue.Text("Work"), p["name"])
        assertEquals(YamlValue.Bool(true), p["favorite"])
        assertEquals(YamlValue.Number(5.0), p["rounding"])
        assertEquals(YamlValue.Text("07:30-23:30"), p["schedule"])
        assertEquals(YamlValue.Text("[[Acme]]"), p["client"])
        assertEquals(YamlValue.Items(listOf("people", "clients")), p["link"])
        assertEquals(YamlValue.Items(listOf("Kubernetes", "SAP S/4", "O'Brien")), p["vocabulary"])
        assertEquals(YamlValue.Empty, p["empty"])
        assertEquals(YamlValue.Text("en"), p["language"])
        assertEquals("## Prompt\nWrite the minutes.", fm.body)
    }

    @Test
    fun `errors carry their line`() {
        val (_, errors) = Frontmatter.parse("---\nname: A\n  nested: x\nno colon\nmode: {a: 1}\n---\n")
        assertEquals(listOf(3, 4, 5), errors.map { it.line })
    }

    @Test
    fun `without frontmatter everything is body`() {
        val (fm, errors) = Frontmatter.parse("# Note\ntext")
        assertTrue(fm.properties.isEmpty() && errors.isEmpty())
        assertEquals("# Note\ntext", fm.body)
    }

    @Test
    fun `writing and reading back keeps the values`() {
        val props = linkedMapOf<String, YamlValue>(
            "start" to YamlValue.Text("10:32"),
            "client" to YamlValue.Text("[[Acme]]"),
            "title" to YamlValue.Text("Meeting: closing"),
            "answer" to YamlValue.Text("no"),
            "duration_min" to YamlValue.Number(75.0),
            "participants" to YamlValue.Items(listOf("[[Ana López]]", "Speaker 2")),
            "empty" to YamlValue.Empty,
        )
        val (fm, errors) = Frontmatter.parse(Frontmatter.note(props, "body\n"))
        assertTrue(errors.isEmpty(), errors.toString())
        assertEquals(props, fm.properties)
        assertEquals("body\n", fm.body)
    }
}

class ProfileLoaderTest {

    private val base = """
        ---
        name: Work
        color: "#2E7D32"
        icon: briefcase
        mode: continuous
        diarization: true
        tasks: true
        link: [people, clients]
        save_audio: true
        audio_retention: 90d
        split_after_silence: 15m
        folder: Work/{{yyyy}}
        ---
        ## Prompt
        Write the minutes.
    """.trimIndent()

    private val acme = """
        ---
        name: Acme
        inherits: work
        client: "[[Acme]]"
        favorite: true
        folder: Clients/Acme/Meetings
        ---
    """.trimIndent()

    @Test
    fun `inheritance applies the parent's values and prompt`() {
        val set = ProfileLoader.load(null, mapOf("work" to base, "acme" to acme))
        assertTrue(set.errors.isEmpty(), set.errors.toString())
        val p = set.profiles.getValue("acme")
        assertEquals("Acme", p.name)
        assertEquals("[[Acme]]", p.client)
        assertEquals(ProfileLoader.MODE_CONTINUOUS, p.mode)
        assertTrue(p.continuous)
        assertEquals("briefcase", p.icon)
        assertEquals(listOf("people", "clients"), p.link)
        assertEquals(90, p.audioRetentionDays)
        assertEquals(15, p.splitAfterSilenceMin)
        assertEquals("Clients/Acme/Meetings", p.folder)
        assertEquals("Write the minutes.", p.prompt)
        assertTrue(p.favorite)
        // The parent doesn't inherit the child's name, nor the child the parent's.
        assertEquals("Work", set.profiles.getValue("work").name)
    }

    @Test
    fun `an invalid profile doesn't break the others`() {
        val bad = "---\nname: Bad\nmode: always\ncolor: green\n---\n"
        val set = ProfileLoader.load(null, mapOf("work" to base, "bad" to bad))
        assertTrue("work" in set.profiles)
        assertTrue("bad" !in set.profiles)
        val errors = set.errors.getValue("profiles/bad.md")
        assertTrue(errors.any { it.startsWith("mode:") } && errors.any { it.startsWith("color:") }, errors.toString())
    }

    @Test
    fun `circular or broken inheritance`() {
        val a = "---\ninherits: b\n---\n"
        val b = "---\ninherits: a\n---\n"
        val c = "---\ninherits: missing\n---\n"
        val set = ProfileLoader.load(null, mapOf("a" to a, "b" to b, "c" to c))
        assertTrue(set.profiles.isEmpty())
        assertTrue(set.errors.getValue("profiles/a.md").any { "Circular" in it })
        assertTrue(set.errors.getValue("profiles/c.md").any { "doesn't exist" in it })
    }

    @Test
    fun `unknown properties warn but don't invalidate`() {
        val set = ProfileLoader.load(null, mapOf("x" to "---\nname: X\ncolour: red\n---\n"))
        assertTrue("x" in set.profiles)
        assertTrue(set.errors.getValue("profiles/x.md").single().contains("colour"))
    }

    @Test
    fun `closed options are case-insensitive`() {
        val set = ProfileLoader.load("---\ndaily_note_format: yyyy-mm-dd\n---\n", mapOf("work" to base))
        assertEquals("YYYY-MM-DD", set.config.dailyFormat)
        assertTrue(set.errors["escriba.md"].orEmpty().none { "daily_note_format" in it }, "no format error: ${set.errors}")
    }

    @Test
    fun `global settings and the list for the watch`() {
        val config = "---\nschedule: \"08:00-22:00\"\ndefault_profile: acme\nphone_audio_retention: 3d\n---\n"
        val set = ProfileLoader.load(config, mapOf("work" to base, "acme" to acme))
        assertEquals(LocalTime.of(8, 0), set.config.schedule.start)
        assertEquals(3, set.config.phoneAudioRetentionDays)
        val forWatch = set.forWatch()
        assertEquals("acme", forWatch.defaultId)
        assertEquals("08:00-22:00", forWatch.schedule)
        assertEquals("acme", forWatch.profiles.first().id) // favorites first
        // The fingerprint changes when the profile changes.
        val changed = ProfileLoader.load(config, mapOf("work" to base.replace("15m", "20m"), "acme" to acme))
        assertTrue(set.profiles.getValue("acme").hash != changed.profiles.getValue("acme").hash)
    }

    @Test
    fun `retention that never expires`() {
        val set = ProfileLoader.load(null, mapOf(
            "a" to "---\naudio_retention: forever\n---\n",
            "b" to "---\naudio_retention: 0d\n---\n",
            "c" to "---\naudio_retention: never\n---\n",
        ))
        assertTrue(set.profiles.values.all { it.audioRetentionDays == null }, set.toString())
    }

    @Test
    fun durations() {
        assertEquals(10, Durations.minutes("10m"))
        assertEquals(90, Durations.minutes("1h30") ?: 90) // unsupported format: the default stays
        assertEquals(2, Durations.minutes("90s"))
        assertEquals(30, Durations.days("30d"))
        assertNull(Durations.days("30"))
    }
}

class NotesTest {

    private val zone = ZoneId.of("Europe/Madrid")
    private fun ms(h: Int, m: Int, s: Int = 0) = ZonedDateTime.of(2026, 10, 16, h, m, s, 0, zone).toInstant().toEpochMilli()

    private fun frag(seq: Int, startH: Int, startM: Int, durS: Long) =
        FragmentInfo(seq, ms(startH, startM), ms(startH, startM) + durS * 1000, listOf(Segment(0, ms(startH, startM), durS * 1000)))

    private val profile = ProfileLoader.load(null, mapOf("work" to "---\nname: Work\nclient: \"[[Acme]]\"\nfolder: Clients/{{yyyy}}/{{MM}}\n---\n"))
        .profiles.getValue("work")

    @Test
    fun `a session splits on long silences between fragments`() {
        val fragments = listOf(frag(0, 10, 0, 60), frag(1, 10, 2, 60), frag(2, 10, 30, 60), frag(3, 10, 31, 30))
        val conversations = Conversations.split(fragments, splitAfterMin = 10)
        assertEquals(listOf(listOf(0, 1), listOf(2, 3)), conversations.map { c -> c.fragments.map { it.seq } })
        assertEquals(listOf(1, 2), conversations.map { it.index })
        assertEquals(90_000, conversations[1].speechMs)
    }

    @Test
    fun `rounding to the nearest minute or to the profile's multiple`() {
        // By default, to the nearest minute.
        assertEquals(1, Conversations.roundedMinutes(20_000))
        assertEquals(1, Conversations.roundedMinutes(89_000))
        assertEquals(2, Conversations.roundedMinutes(90_000))
        assertEquals(41, Conversations.roundedMinutes(41 * 60_000 + 10_000))
        // With "rounding: 5m", to the nearest multiple of 5, never less than 5.
        assertEquals(5, Conversations.roundedMinutes(30_000, 5))
        assertEquals(5, Conversations.roundedMinutes(7 * 60_000, 5))
        assertEquals(10, Conversations.roundedMinutes(8 * 60_000, 5))
        assertEquals(75, Conversations.roundedMinutes(73 * 60_000, 5))
    }

    @Test
    fun `the note has Dataview frontmatter and a placeholder transcript`() {
        val c = Conversations.split(listOf(frag(0, 10, 32, 45), frag(1, 10, 34, 50)), 10).single()
        val data = NoteData("session-1", c, profile, zone, audioLink = "Attachments/Escriba/2026-10-16 1032 Work.ogg")
        val note = NoteRenderer.render(data, template = null)
        val (fm, errors) = Frontmatter.parse(note)
        assertTrue(errors.isEmpty(), errors.toString())
        assertEquals("recording", fm.text("type"))
        assertEquals("2026-10-16", fm.text("date"))
        assertEquals("10:32", fm.text("start"))
        assertEquals("10:34", fm.text("end"))
        assertEquals("3", fm.text("duration_min")) // to the nearest minute (the profile sets no rounding)
        assertEquals("[[Acme]]", fm.text("client"))
        assertEquals("[[Attachments/Escriba/2026-10-16 1032 Work.ogg]]", fm.text("audio"))
        assertTrue("![[Attachments/Escriba/2026-10-16 1032 Work.ogg]]" in fm.body)
        assertTrue("- **10:32** · 45 s of speech" in fm.body)
        assertTrue(!fm.body.contains("{{"))
        assertEquals("2026-10-16 1032 Work", NoteRenderer.baseName(data))
        assertEquals("Clients/2026/10", NoteRenderer.folder(profile.folder, c.startMs, zone))
    }

    @Test
    fun `custom template with variables`() {
        val c = Conversations.split(listOf(frag(0, 9, 5, 30)), 10).single()
        val note = NoteRenderer.render(NoteData("s", c, profile, zone), "{{profile}} meeting on {{date}} at {{start}}\n{{unknown}}")
        assertTrue(note.endsWith("Work meeting on 2026-10-16 at 09:05\n{{unknown}}"))
    }

    @Test
    fun `the transcript is grouped into paragraphs by pauses`() {
        fun line(s: Int, e: Int, text: String) = TranscriptLine(ms(10, 0, s), ms(10, 0, e), text)
        val text = NoteRenderer.transcript(
            listOf(
                line(0, 3, "Then the interface"),
                line(3, 5, "needed a good"),
                line(5, 6, "facelift."),
                line(15, 18, "For now it works fine."), // 9 s pause: new paragraph
            ),
            zone,
        )
        assertEquals("**10:00:00** Then the interface needed a good facelift.\n\n**10:00:15** For now it works fine.", text)
    }

    @Test
    fun `safe file names`() {
        assertEquals("Minutes client project Q4", NoteRenderer.sanitize("Minutes: client/project? [Q4]"))
    }

    @Test
    fun `link in the daily note without duplicates`() {
        val created = DailyNote.addLink(null, "2026-10-16 1032 Work", "2026-10-16")
        assertEquals("# 2026-10-16\n\n## Recordings\n- [[2026-10-16 1032 Work]]\n", created)
        val second = DailyNote.addLink(created, "2026-10-16 1500 Personal", "2026-10-16")
        assertEquals(DailyNote.addLink(second, "2026-10-16 1500 Personal", "2026-10-16"), second)
        val userNote = "# Today\n\nMy notes.\n\n## Recordings\n- [[a]]\n\n## Another section\ntext\n"
        val updated = DailyNote.addLink(userNote, "b", "x")
        assertEquals("# Today\n\nMy notes.\n\n## Recordings\n- [[a]]\n- [[b]]\n\n## Another section\ntext\n", updated)
        assertEquals("# Today\ntext\n\n## Recordings\n- [[c]]\n", DailyNote.addLink("# Today\ntext\n", "c", "x"))
    }

    @Test
    fun `joining Ogg fragments into one`() {
        fun ogg(packets: Int): ByteArray {
            val out = ByteArrayOutputStream()
            OggOpusWriter(out, 1, 312, 16_000).use { w -> repeat(packets) { w.writePacket(byteArrayOf((27 shl 3).toByte(), it.toByte())) } }
            return out.toByteArray()
        }
        val joined = OggReader.read(OggConcat.concat(listOf(ogg(70), ogg(30))))
        assertEquals(100, joined.audioPackets.size)
        assertTrue(joined.endsWithEos)
        assertEquals((100L * 960 - 312) * 1000 / 48_000, joined.durationMs)
    }
}
