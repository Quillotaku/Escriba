package dev.escriba.shared

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Formato de un fragmento en el canal: `[u32 big-endian: longitud][cabecera JSON][payload]`.
 * La cabecera va primero para que el receptor sepa qué recibe antes de leer el audio.
 */
object Framing {
    /** Las cabeceras reales ocupan unos cientos de bytes; esto protege de basura en el canal. */
    const val MAX_HEADER_BYTES = 64 * 1024

    fun writeHeader(out: OutputStream, header: ByteArray) {
        require(header.size in 1..MAX_HEADER_BYTES) { "Cabecera de ${header.size} bytes fuera de rango" }
        val n = header.size
        out.write(byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()))
        out.write(header)
    }

    /** Lee la cabecera y deja [input] posicionado al inicio del payload. */
    fun readHeader(input: InputStream): ByteArray {
        val data = DataInputStream(input)
        val n = try {
            data.readInt()
        } catch (e: EOFException) {
            throw IOException("Canal cerrado antes de la cabecera", e)
        }
        if (n !in 1..MAX_HEADER_BYTES) throw IOException("Longitud de cabecera inválida: $n")
        val header = ByteArray(n)
        data.readFully(header)
        return header
    }
}
