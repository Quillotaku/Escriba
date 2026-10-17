package dev.escriba.shared.vault

import kotlin.math.abs

/**
 * Los seis colores de perfil del sistema de diseño. En la bóveda se guarda el valor claro; cada
 * app lo traduce a su tema (claro, oscuro o reloj) por su posición en esta lista.
 */
object ProfilePalette {
    val NAMES = listOf("pino", "lapislázuli", "ocre", "ciruela", "pizarra", "teja")
    val LIGHT = listOf(0x2E6B3A, 0x2C4DA8, 0x835400, 0x7A2F73, 0x3F5466, 0x9C3D1C)

    /** Posición del color de la paleta más parecido a [hex] (`#2E7D32`), o null si no es un color. */
    fun nearest(hex: String?): Int? {
        val rgb = hex?.trim()?.removePrefix("#")?.takeIf { it.length == 6 }?.toIntOrNull(16) ?: return null
        fun channels(c: Int) = Triple((c shr 16) and 0xff, (c shr 8) and 0xff, c and 0xff)
        val (r, g, b) = channels(rgb)
        // Distancia ponderada (el ojo distingue más el verde que el azul).
        return LIGHT.indices.minBy { i ->
            val (pr, pg, pb) = channels(LIGHT[i])
            abs(pr - r) * 3 + abs(pg - g) * 4 + abs(pb - b) * 2
        }
    }
}
