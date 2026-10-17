package dev.escriba.shared.vault

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The design system's six profile colors. The vault stores the light value; each app maps it to
 * its theme (light, dark or watch) by its position in this list.
 */
object ProfilePalette {
    val NAMES = listOf("pine", "lapis", "ochre", "plum", "slate", "terracotta")
    val LIGHT = listOf(0x2E6B3A, 0x2C4DA8, 0x835400, 0x7A2F73, 0x3F5466, 0x9C3D1C)

    /** Position of "slate": where muted colors go (greys, near-whites and near-blacks). */
    private const val SLATE = 4

    /**
     * Position of the palette color closest to [hex] (`#2E7D32`), or null if it isn't a color.
     * Compared by hue: people write bright colors ("#F9A825") and the palette is dark, so RGB
     * distance would pick badly. Colors without saturation go to "slate".
     */
    fun nearest(hex: String?): Int? {
        val rgb = hex?.trim()?.removePrefix("#")?.takeIf { it.length == 6 }?.toIntOrNull(16) ?: return null
        val (hue, saturation) = hueSaturation(rgb)
        // Below 0.45 it's a grey or a greyish blue (slate itself is 0.38; the others are over 0.55).
        if (saturation < 0.45f) return SLATE
        return LIGHT.indices.filter { it != SLATE }.minBy { i ->
            val d = abs(hueSaturation(LIGHT[i]).first - hue)
            min(d, 360f - d)
        }
    }

    /** Hue (0–360) and saturation (0–1) of an RGB color. */
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
