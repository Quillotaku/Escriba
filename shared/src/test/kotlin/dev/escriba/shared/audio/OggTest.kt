package dev.escriba.shared.audio

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OggTest {

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Páginas de cabecera escritas por el MediaMuxer de Android (sin audio): referencia del CRC. */
    private val androidHeadPage =
        hex("4f676753000200000000000000005408201200000000dceb277d01134f7075734865616401016800803e0000000000")
    private val androidTagsPage =
        hex("4f676753000000000000000000005408201201000000c3fce72801174f70757354616773070000006c69626f70757300000000")

    /** Paquete Opus sintético: TOC con config 27 (CELT, 20 ms) y código 0 (una trama). */
    private fun packet(size: Int, fill: Int = 0x55) =
        ByteArray(size) { if (it == 0) (27 shl 3).toByte() else fill.toByte() }

    @Test
    fun `el CRC coincide con el de Android`() {
        for (page in listOf(androidHeadPage, androidTagsPage)) {
            val stored = Ogg.getLe(page, 22, 4).toInt()
            val zeroed = page.copyOf().also { for (i in 22..25) it[i] = 0 }
            assertEquals(stored, Ogg.crc(zeroed))
        }
    }

    @Test
    fun `el lector entiende un Ogg de Android`() {
        val result = OggReader.read(androidHeadPage + androidTagsPage)
        assertEquals(2, result.pages.size)
        assertEquals(16_000, result.inputSampleRate)
        assertEquals(androidHeadPage.size + androidTagsPage.size, result.validLength)
    }

    @Test
    fun `duracion de paquetes Opus segun el TOC`() {
        assertEquals(960, Ogg.opusPacketSamples(packet(10))) // CELT 20 ms
        assertEquals(480, Ogg.opusPacketSamples(byteArrayOf((26 shl 3).toByte()))) // CELT 10 ms
        assertEquals(2880, Ogg.opusPacketSamples(byteArrayOf((3 shl 3).toByte()))) // SILK 60 ms
        assertEquals(1920, Ogg.opusPacketSamples(byteArrayOf(((27 shl 3) or 1).toByte()))) // 2 tramas de 20 ms
        assertEquals(3 * 960, Ogg.opusPacketSamples(byteArrayOf(((27 shl 3) or 3).toByte(), 3))) // código 3
        assertEquals(0, Ogg.opusPacketSamples(ByteArray(0)))
    }

    @Test
    fun `ida y vuelta con paginas de un segundo`() {
        val out = ByteArrayOutputStream()
        val packets = List(120) { packet(40 + it % 30, fill = it) }
        OggOpusWriter(out, serial = 7, preSkip = 312, inputSampleRate = 16_000).use { w ->
            packets.forEach(w::writePacket)
        }
        val result = OggReader.read(out.toByteArray())

        // OpusHead + OpusTags + 3 páginas de audio (50 + 50 + 20)
        assertEquals(5, result.pages.size)
        assertEquals(out.size(), result.validLength)
        assertEquals(312, result.preSkip)
        assertTrue(result.endsWithEos)
        assertEquals(Ogg.FLAG_BOS, result.pages.first().flags)
        assertEquals((0 until 5).toList(), result.pages.map { it.sequence })
        assertEquals(listOf(0L, 0L, 50L * 960, 100L * 960, 120L * 960), result.pages.map { it.granule })
        assertEquals(packets.size, result.audioPackets.size)
        packets.zip(result.audioPackets).forEach { (a, b) -> assertContentEquals(a, b) }
        assertEquals((120L * 960 - 312) * 1000 / 48_000, result.durationMs)
    }

    @Test
    fun `paquetes de mas de 255 bytes usan varios valores de lacing`() {
        val out = ByteArrayOutputStream()
        val big = listOf(packet(255), packet(510), packet(600), packet(3))
        OggOpusWriter(out, serial = 1, preSkip = 0, inputSampleRate = 16_000).use { w -> big.forEach(w::writePacket) }
        val result = OggReader.read(out.toByteArray())
        assertEquals(big.size, result.audioPackets.size)
        big.zip(result.audioPackets).forEach { (a, b) -> assertContentEquals(a, b) }
    }

    @Test
    fun `un archivo truncado es valido hasta la ultima pagina completa`() {
        val out = ByteArrayOutputStream()
        val writer = OggOpusWriter(out, serial = 3, preSkip = 312, inputSampleRate = 16_000)
        repeat(130) { writer.writePacket(packet(60)) }
        // Sin close(): simula que el proceso murió en mitad de la grabación.
        val bytes = out.toByteArray()
        val cut = bytes + packet(30) // basura parcial al final

        val result = OggReader.read(cut)
        assertEquals(bytes.size, result.validLength)
        assertEquals(100, result.audioPackets.size) // dos páginas de 50 completas
        assertEquals(false, result.endsWithEos)
    }

    @Test
    fun `un CRC corrupto detiene la lectura`() {
        val out = ByteArrayOutputStream()
        OggOpusWriter(out, serial = 3, preSkip = 0, inputSampleRate = 16_000).use { w ->
            repeat(60) { w.writePacket(packet(60)) }
        }
        val bytes = out.toByteArray()
        val firstAudio = OggReader.read(bytes).pages[2]
        bytes[firstAudio.offset + firstAudio.length - 1] = 0 // estropea el último byte de la página
        assertEquals(firstAudio.offset, OggReader.read(bytes).validLength)
    }
}
