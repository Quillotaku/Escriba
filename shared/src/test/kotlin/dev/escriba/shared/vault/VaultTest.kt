package dev.escriba.shared.vault

import dev.escriba.shared.Segment
import dev.escriba.shared.audio.OggOpusWriter
import dev.escriba.shared.audio.OggReader
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
    fun `lee lo que escribe la interfaz de Propiedades de Obsidian`() {
        val note = """
            ---
            nombre: Trabajo
            hereda: base
            favorito: true
            redondeo: 5
            horario: "07:30-23:30"
            cliente: "[[Acme]]"
            enlazar:
              - personas
              - clientes
            vocabulario: [Kubernetes, "SAP S/4", 'O''Brien']
            vacio:
            # un comentario
            idioma: es # otro comentario
            ---
            ## Prompt
            Haz un acta.
        """.trimIndent()
        val (fm, errors) = Frontmatter.parse(note)
        assertTrue(errors.isEmpty(), errors.toString())
        val p = fm.properties
        assertEquals(YamlValue.Text("Trabajo"), p["nombre"])
        assertEquals(YamlValue.Bool(true), p["favorito"])
        assertEquals(YamlValue.Number(5.0), p["redondeo"])
        assertEquals(YamlValue.Text("07:30-23:30"), p["horario"])
        assertEquals(YamlValue.Text("[[Acme]]"), p["cliente"])
        assertEquals(YamlValue.Items(listOf("personas", "clientes")), p["enlazar"])
        assertEquals(YamlValue.Items(listOf("Kubernetes", "SAP S/4", "O'Brien")), p["vocabulario"])
        assertEquals(YamlValue.Empty, p["vacio"])
        assertEquals(YamlValue.Text("es"), p["idioma"])
        assertEquals("## Prompt\nHaz un acta.", fm.body)
    }

    @Test
    fun `los errores llevan su linea`() {
        val (_, errors) = Frontmatter.parse("---\nnombre: A\n  anidado: x\nsin dos puntos\nmodo: {a: 1}\n---\n")
        assertEquals(listOf(3, 4, 5), errors.map { it.line })
    }

    @Test
    fun `sin frontmatter todo es cuerpo`() {
        val (fm, errors) = Frontmatter.parse("# Nota\ntexto")
        assertTrue(fm.properties.isEmpty() && errors.isEmpty())
        assertEquals("# Nota\ntexto", fm.body)
    }

    @Test
    fun `escribir y volver a leer conserva los valores`() {
        val props = linkedMapOf<String, YamlValue>(
            "inicio" to YamlValue.Text("10:32"),
            "cliente" to YamlValue.Text("[[Acme]]"),
            "titulo" to YamlValue.Text("Reunión: cierre"),
            "respuesta" to YamlValue.Text("no"),
            "duracion_min" to YamlValue.Number(75.0),
            "participantes" to YamlValue.Items(listOf("[[Ana López]]", "Hablante 2")),
            "vacio" to YamlValue.Empty,
        )
        val (fm, errors) = Frontmatter.parse(Frontmatter.note(props, "cuerpo\n"))
        assertTrue(errors.isEmpty(), errors.toString())
        assertEquals(props, fm.properties)
        assertEquals("cuerpo\n", fm.body)
    }
}

class ProfileLoaderTest {

    private val base = """
        ---
        nombre: Trabajo
        color: "#2E7D32"
        icono: maletin
        modo: continuo
        diarizacion: true
        tareas: true
        enlazar: [personas, clientes]
        guardar_audio: true
        retencion_audio: 90d
        separar_tras_silencio: 15m
        carpeta: Trabajo/{{yyyy}}
        ---
        ## Prompt
        Redacta un acta.
    """.trimIndent()

    private val acme = """
        ---
        nombre: Acme
        hereda: trabajo
        cliente: "[[Acme]]"
        favorito: true
        carpeta: Clientes/Acme/Reuniones
        ---
    """.trimIndent()

    @Test
    fun `la herencia aplica los valores del padre y el prompt`() {
        val set = ProfileLoader.load(null, mapOf("trabajo" to base, "acme" to acme))
        assertTrue(set.errors.isEmpty(), set.errors.toString())
        val p = set.profiles.getValue("acme")
        assertEquals("Acme", p.name)
        assertEquals("[[Acme]]", p.client)
        assertEquals("continuo", p.mode)
        assertEquals("maletin", p.icon)
        assertEquals(listOf("personas", "clientes"), p.link)
        assertEquals(90, p.audioRetentionDays)
        assertEquals(15, p.splitAfterSilenceMin)
        assertEquals("Clientes/Acme/Reuniones", p.folder)
        assertEquals("Redacta un acta.", p.prompt)
        assertTrue(p.favorite)
        // El padre no hereda el nombre del hijo, ni el hijo el del padre.
        assertEquals("Trabajo", set.profiles.getValue("trabajo").name)
    }

    @Test
    fun `un perfil invalido no rompe los demas`() {
        val bad = "---\nnombre: Malo\nmodo: siempre\ncolor: verde\n---\n"
        val set = ProfileLoader.load(null, mapOf("trabajo" to base, "malo" to bad))
        assertTrue("trabajo" in set.profiles)
        assertTrue("malo" !in set.profiles)
        val errors = set.errors.getValue("perfiles/malo.md")
        assertTrue(errors.any { it.startsWith("modo:") } && errors.any { it.startsWith("color:") }, errors.toString())
    }

    @Test
    fun `herencia circular o rota`() {
        val a = "---\nhereda: b\n---\n"
        val b = "---\nhereda: a\n---\n"
        val c = "---\nhereda: noexiste\n---\n"
        val set = ProfileLoader.load(null, mapOf("a" to a, "b" to b, "c" to c))
        assertTrue(set.profiles.isEmpty())
        assertTrue(set.errors.getValue("perfiles/a.md").any { "circular" in it })
        assertTrue(set.errors.getValue("perfiles/c.md").any { "no existe" in it })
    }

    @Test
    fun `propiedades desconocidas avisan pero no invalidan`() {
        val set = ProfileLoader.load(null, mapOf("x" to "---\nnombre: X\ncolour: red\n---\n"))
        assertTrue("x" in set.profiles)
        assertTrue(set.errors.getValue("perfiles/x.md").single().contains("colour"))
    }

    @Test
    fun `las opciones cerradas no distinguen mayusculas`() {
        val set = ProfileLoader.load("---\nnota_diaria_formato: yyyy-mm-dd\n---\n", mapOf("trabajo" to base))
        assertEquals("YYYY-MM-DD", set.config.dailyFormat)
        assertTrue(set.errors["escriba.md"].orEmpty().none { "nota_diaria_formato" in it }, "sin error de formato: ${set.errors}")
    }

    @Test
    fun `configuracion global y lista para el reloj`() {
        val config = "---\nhorario: \"08:00-22:00\"\nperfil_por_defecto: acme\nretencion_audio_movil: 3d\n---\n"
        val set = ProfileLoader.load(config, mapOf("trabajo" to base, "acme" to acme))
        assertEquals(LocalTime.of(8, 0), set.config.schedule.start)
        assertEquals(3, set.config.phoneAudioRetentionDays)
        val forWatch = set.forWatch()
        assertEquals("acme", forWatch.defaultId)
        assertEquals("08:00-22:00", forWatch.schedule)
        assertEquals("acme", forWatch.profiles.first().id) // favoritos primero
        // La huella cambia si cambia el perfil.
        val changed = ProfileLoader.load(config, mapOf("trabajo" to base.replace("15m", "20m"), "acme" to acme))
        assertTrue(set.profiles.getValue("acme").hash != changed.profiles.getValue("acme").hash)
    }

    @Test
    fun `duraciones`() {
        assertEquals(10, Durations.minutes("10m"))
        assertEquals(90, Durations.minutes("1h30") ?: 90) // formato no admitido: se queda el valor por defecto
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

    private val profile = ProfileLoader.load(null, mapOf("trabajo" to "---\nnombre: Trabajo\ncliente: \"[[Acme]]\"\ncarpeta: Clientes/{{yyyy}}/{{MM}}\n---\n"))
        .profiles.getValue("trabajo")

    @Test
    fun `una sesion se parte por silencios largos entre fragmentos`() {
        val fragments = listOf(frag(0, 10, 0, 60), frag(1, 10, 2, 60), frag(2, 10, 30, 60), frag(3, 10, 31, 30))
        val conversations = Conversations.split(fragments, splitAfterMin = 10)
        assertEquals(listOf(listOf(0, 1), listOf(2, 3)), conversations.map { c -> c.fragments.map { it.seq } })
        assertEquals(listOf(1, 2), conversations.map { it.index })
        assertEquals(90_000, conversations[1].speechMs)
    }

    @Test
    fun `redondeo al minuto mas cercano o al multiplo del perfil`() {
        // Por defecto, al minuto más cercano.
        assertEquals(1, Conversations.roundedMinutes(20_000))
        assertEquals(1, Conversations.roundedMinutes(89_000))
        assertEquals(2, Conversations.roundedMinutes(90_000))
        assertEquals(41, Conversations.roundedMinutes(41 * 60_000 + 10_000))
        // Con «redondeo: 5m», al múltiplo de 5 más cercano, nunca menos de 5.
        assertEquals(5, Conversations.roundedMinutes(30_000, 5))
        assertEquals(5, Conversations.roundedMinutes(7 * 60_000, 5))
        assertEquals(10, Conversations.roundedMinutes(8 * 60_000, 5))
        assertEquals(75, Conversations.roundedMinutes(73 * 60_000, 5))
    }

    @Test
    fun `la nota tiene frontmatter para Dataview y la transcripcion provisional`() {
        val c = Conversations.split(listOf(frag(0, 10, 32, 45), frag(1, 10, 34, 50)), 10).single()
        val data = NoteData("sesion-1", c, profile, zone, audioLink = "Adjuntos/Escriba/2026-10-16 1032 Trabajo.ogg")
        val note = NoteRenderer.render(data, template = null)
        val (fm, errors) = Frontmatter.parse(note)
        assertTrue(errors.isEmpty(), errors.toString())
        assertEquals("grabacion", fm.text("tipo"))
        assertEquals("2026-10-16", fm.text("fecha"))
        assertEquals("10:32", fm.text("inicio"))
        assertEquals("10:34", fm.text("fin"))
        assertEquals("3", fm.text("duracion_min")) // al minuto más cercano (el perfil no fija redondeo)
        assertEquals("[[Acme]]", fm.text("cliente"))
        assertEquals("[[Adjuntos/Escriba/2026-10-16 1032 Trabajo.ogg]]", fm.text("audio"))
        assertTrue("![[Adjuntos/Escriba/2026-10-16 1032 Trabajo.ogg]]" in fm.body)
        assertTrue("- **10:32** · 45 s de voz" in fm.body)
        assertTrue(!fm.body.contains("{{"))
        assertEquals("2026-10-16 1032 Trabajo", NoteRenderer.baseName(data))
        assertEquals("Clientes/2026/10", NoteRenderer.folder(profile.folder, c.startMs, zone))
    }

    @Test
    fun `plantilla propia con variables`() {
        val c = Conversations.split(listOf(frag(0, 9, 5, 30)), 10).single()
        val note = NoteRenderer.render(NoteData("s", c, profile, zone), "Reunión de {{perfil}} el {{fecha}} a las {{inicio}}\n{{desconocida}}")
        assertTrue(note.endsWith("Reunión de Trabajo el 2026-10-16 a las 09:05\n{{desconocida}}"))
    }

    @Test
    fun `la transcripcion se agrupa en parrafos por pausas`() {
        fun line(s: Int, e: Int, text: String) = dev.escriba.shared.transcription.TranscriptLine(ms(10, 0, s), ms(10, 0, e), text)
        val text = NoteRenderer.transcript(
            listOf(
                line(0, 3, "Luego había que darle"),
                line(3, 5, "una lavada de cara"),
                line(5, 6, "a la interfaz."),
                line(15, 18, "Por ahora va que chuta."), // pausa de 9 s: párrafo nuevo
            ),
            zone,
        )
        assertEquals("**10:00:00** Luego había que darle una lavada de cara a la interfaz.\n\n**10:00:15** Por ahora va que chuta.", text)
    }

    @Test
    fun `nombres de archivo seguros`() {
        assertEquals("Acta cliente proyecto Q4", NoteRenderer.sanitize("Acta: cliente/proyecto? [Q4]"))
    }

    @Test
    fun `enlace en la nota diaria sin duplicar`() {
        val created = DailyNote.addLink(null, "2026-10-16 1032 Trabajo", "2026-10-16")
        assertEquals("# 2026-10-16\n\n## Grabaciones\n- [[2026-10-16 1032 Trabajo]]\n", created)
        val second = DailyNote.addLink(created, "2026-10-16 1500 Casual", "2026-10-16")
        assertEquals(DailyNote.addLink(second, "2026-10-16 1500 Casual", "2026-10-16"), second)
        val userNote = "# Hoy\n\nNotas mías.\n\n## Grabaciones\n- [[a]]\n\n## Otra sección\ntexto\n"
        val updated = DailyNote.addLink(userNote, "b", "x")
        assertEquals("# Hoy\n\nNotas mías.\n\n## Grabaciones\n- [[a]]\n- [[b]]\n\n## Otra sección\ntexto\n", updated)
        assertEquals("# Hoy\ntexto\n\n## Grabaciones\n- [[c]]\n", DailyNote.addLink("# Hoy\ntexto\n", "c", "x"))
    }

    @Test
    fun `unir fragmentos Ogg en uno`() {
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
