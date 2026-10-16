package dev.escriba.shared

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ContractTest {

    private val header = FragmentHeader(
        sessionId = "7f3c0a4e-0000-4000-8000-000000000001",
        seq = 3,
        profileId = "trabajo",
        profileHash = "abc123",
        sessionStartMs = 1_760_000_000_000,
        startMs = 1_760_000_060_000,
        endMs = 1_760_000_120_000,
        segments = listOf(
            Segment(audioOffsetMs = 0, wallStartMs = 1_760_000_061_000, durationMs = 4_200),
            Segment(audioOffsetMs = 4_200, wallStartMs = 1_760_000_090_000, durationMs = 9_800),
        ),
        sampleRate = 16_000,
        bitrate = 24_000,
        sha256 = "00ff",
        sizeBytes = 42_000,
        last = false,
    )

    @Test
    fun `la cabecera sobrevive a la ida y vuelta por JSON`() {
        val decoded: FragmentHeader = header.toWire().fromWire()
        assertEquals(header, decoded)
        assertEquals("7f3c0a4e-0000-4000-8000-000000000001#3", decoded.key)
    }

    @Test
    fun `los campos desconocidos de versiones futuras se ignoran`() {
        val json = """{"id":"p1","sentAtMs":5,"origin":"wear","campoNuevo":true}"""
        val ping: Ping = json.encodeToByteArray().fromWire()
        assertEquals(Ping("p1", 5, "wear"), ping)
    }

    @Test
    fun `framing separa cabecera y payload`() {
        val payload = ByteArray(1000) { it.toByte() }
        val out = ByteArrayOutputStream()
        Framing.writeHeader(out, header.toWire())
        out.write(payload)

        val input = ByteArrayInputStream(out.toByteArray())
        val decoded: FragmentHeader = Framing.readHeader(input).fromWire()
        assertEquals(header, decoded)
        assertContentEquals(payload, input.readBytes())
    }

    @Test
    fun `framing rechaza longitudes absurdas`() {
        val bogus = byteArrayOf(0x7f, 0, 0, 0, 1, 2, 3)
        assertFailsWith<IOException> { Framing.readHeader(ByteArrayInputStream(bogus)) }
    }

    @Test
    fun `framing detecta canal cerrado`() {
        val e = assertFailsWith<IOException> { Framing.readHeader(ByteArrayInputStream(ByteArray(0))) }
        assertTrue(e.message!!.contains("cerrado"))
    }

    @Test
    fun `las rutas cuelgan del prefijo filtrado`() {
        listOf(Protocol.PATH_PING, Protocol.PATH_PONG, Protocol.PATH_FRAGMENT, Protocol.PATH_ACK)
            .forEach { assertTrue(it.startsWith(Protocol.PATH_PREFIX), it) }
    }
}
