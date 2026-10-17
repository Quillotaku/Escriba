package dev.escriba.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Typography
import dev.escriba.shared.vault.ProfilePalette
import dev.escriba.wear.R

/*
 * The "watch" theme of Escriba's design system: pure black for the OLED screen, lapis for what
 * can be touched and minium only for recording. Always the same: the watch has no light theme.
 */

private fun c(hex: Long) = Color(0xFF000000 or hex)

@Immutable
object WatchColors {
    val paper = c(0x000000)
    val paperRaised = c(0x161c21)
    val paperSunken = c(0x0b0f12)
    val rule = c(0x2b353e)
    val ruleStrong = c(0x7d8a96)
    val ink = c(0xffffff)
    val inkMuted = c(0xa9b6c1)
    val lapis = c(0xa3b8f5)
    val onLapis = c(0x000000)
    val lapisSoft = c(0x1c2a4d)
    val minium = c(0xff7d61)
    val onMinium = c(0x000000)
    val miniumSoft = c(0x3d1912)
    val verdigris = c(0x67cfdb)
    val ochre = c(0xf0b65c)
    val danger = c(0xff94a6)
    val dangerSoft = c(0x3b1520)

    /** pine, lapis, ochre, plum, slate, terracotta (watch theme values). */
    private val profiles = listOf(c(0x7fcf8e), c(0xa3b8f5), c(0xf0b65c), c(0xe19ad8), c(0xa9bccc), c(0xf2a07f))

    fun profile(hex: String?): Color = ProfilePalette.nearest(hex)?.let { profiles[it] } ?: inkMuted
}

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: FontWeight) =
    Font(res, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))

/** Atkinson Hyperlegible Next: made to be read at a glance, on the wrist too. */
val WatchSans = FontFamily(
    variable(R.font.atkinson_next, FontWeight.Normal),
    variable(R.font.atkinson_next, FontWeight.Medium),
    variable(R.font.atkinson_next, FontWeight.SemiBold),
    variable(R.font.atkinson_next, FontWeight.Bold),
)

/** For the recorded time: fixed-width digits that don't jump around as they change. */
val WatchMono = FontFamily(
    variable(R.font.atkinson_mono, FontWeight.Medium),
    variable(R.font.atkinson_mono, FontWeight.SemiBold),
)

private val scheme = ColorScheme(
    primary = WatchColors.lapis,
    primaryDim = WatchColors.lapis,
    primaryContainer = WatchColors.lapisSoft,
    onPrimary = WatchColors.onLapis,
    onPrimaryContainer = WatchColors.ink,
    secondary = WatchColors.inkMuted,
    secondaryDim = WatchColors.inkMuted,
    secondaryContainer = WatchColors.paperRaised,
    onSecondary = WatchColors.paper,
    onSecondaryContainer = WatchColors.ink,
    tertiary = WatchColors.minium,
    tertiaryDim = WatchColors.minium,
    tertiaryContainer = WatchColors.miniumSoft,
    onTertiary = WatchColors.onMinium,
    onTertiaryContainer = WatchColors.ink,
    surfaceContainerLow = WatchColors.paperSunken,
    surfaceContainer = WatchColors.paperRaised,
    surfaceContainerHigh = WatchColors.paperRaised,
    onSurface = WatchColors.ink,
    onSurfaceVariant = WatchColors.inkMuted,
    outline = WatchColors.ruleStrong,
    outlineVariant = WatchColors.rule,
    background = WatchColors.paper,
    onBackground = WatchColors.ink,
    error = WatchColors.danger,
    errorDim = WatchColors.danger,
    errorContainer = WatchColors.dangerSoft,
    onError = WatchColors.paper,
    onErrorContainer = WatchColors.ink,
)

@Composable
fun EscribaWearTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = Typography(defaultFontFamily = WatchSans), content = content)
}
