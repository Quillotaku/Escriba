package dev.escriba.shared.vault

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Los seis colores de perfil del sistema de diseño. En la bóveda se guarda el valor claro; cada
 * app lo traduce a su tema (claro, oscuro o reloj) por su posición en esta lista.
 */
object ProfilePalette {
    val NAMES = listOf("pino", "lapislázuli", "ocre", "ciruela", "pizarra", "teja")
    val LIGHT = listOf(0x2E6B3A, 0x2C4DA8, 0x835400, 0x7A2F73, 0x3F5466, 0x9C3D1C)

    /** Posición de «pizarra»: la de los colores apagados (grises, casi blancos o casi negros). */
    private const val SLATE = 4

    /**
     * Posición del color de la paleta más parecido a [hex] (`#2E7D32`), o null si no es un color.
     * Se compara por tono: la gente escribe colores vivos («#F9A825») y la paleta es oscura, así que
     * la distancia en RGB elegiría mal. Los colores sin saturación van a «pizarra».
     */
    fun nearest(hex: String?): Int? {
        val rgb = hex?.trim()?.removePrefix("#")?.takeIf { it.length == 6 }?.toIntOrNull(16) ?: return null
        val (hue, saturation) = hueSaturation(rgb)
        // Por debajo de 0,45 es un gris o un azul grisáceo (la propia pizarra tiene 0,38; las demás, más de 0,55).
        if (saturation < 0.45f) return SLATE
        return LIGHT.indices.filter { it != SLATE }.minBy { i ->
            val d = abs(hueSaturation(LIGHT[i]).first - hue)
            min(d, 360f - d)
        }
    }

    /** Tono (0–360) y saturación (0–1) de un color RGB. */
    private fun hueSaturation(rgb: Int): Pair<Float, Float> {
        val r = ((rgb shr 16) and 0xff) / 255f
        val g = ((rgb shr 8) and 0xff) / 255f
        val b = (rgb and 0xff) / 255f
        val hi = max(r, max(g, b))
        val lo = min(r, min(g, b))
        val delta = hi - lo
        if (delta == 0f) return 0f to 0f
        val hue = when (hi) {
            r -> 60f * (((g - b) / delta).mod(6f))
            g -> 60f * ((b - r) / delta + 2f)
            else -> 60f * ((r - g) / delta + 4f)
        }
        return hue to delta / hi
    }
}
