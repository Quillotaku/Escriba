package dev.escriba.mobile.ui.theme

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import dev.escriba.mobile.R

/*
 * Tema de Escriba (sistema de diseño «Escriba»): papel y tinta ferrogálica, lapislázuli para la
 * acción, minio solo para grabar. Los valores salen de tokens.json; aquí no se inventa ninguno.
 */

/** Colores que Material 3 no tiene: estados y perfiles. */
@Immutable
data class EscribaColors(
    val paper: Color,
    val paperRaised: Color,
    val paperSunken: Color,
    val rule: Color,
    val ruleStrong: Color,
    val ink: Color,
    val inkMuted: Color,
    val lapis: Color,
    val onLapis: Color,
    val lapisSoft: Color,
    val minium: Color,
    val onMinium: Color,
    val miniumSoft: Color,
    val verdigris: Color,
    val verdigrisSoft: Color,
    val ochre: Color,
    val ochreSoft: Color,
    val danger: Color,
    val dangerSoft: Color,
    val focus: Color,
    /** pino, lapis, ocre, ciruela, pizarra, teja. */
    val profiles: List<Color>,
)

private fun c(hex: Long) = Color(0xFF000000 or hex)

val LightColors = EscribaColors(
    paper = c(0xf3f5f7), paperRaised = c(0xffffff), paperSunken = c(0xe7ebef),
    rule = c(0xcfd7de), ruleStrong = c(0x7d8a96),
    ink = c(0x141c24), inkMuted = c(0x53606c),
    lapis = c(0x2c4da8), onLapis = c(0xffffff), lapisSoft = c(0xe2e8f8),
    minium = c(0xc2341b), onMinium = c(0xffffff), miniumSoft = c(0xfbe5df),
    verdigris = c(0x0d6570), verdigrisSoft = c(0xdbeef0),
    ochre = c(0x835400), ochreSoft = c(0xf6ead2),
    danger = c(0xa3172f), dangerSoft = c(0xf8e1e5),
    focus = c(0x2c4da8),
    profiles = listOf(c(0x2e6b3a), c(0x2c4da8), c(0x835400), c(0x7a2f73), c(0x3f5466), c(0x9c3d1c)),
)

val DarkColors = EscribaColors(
    paper = c(0x0f1418), paperRaised = c(0x182027), paperSunken = c(0x0a0e11),
    rule = c(0x2b353e), ruleStrong = c(0x6f7d89),
    ink = c(0xe8edf1), inkMuted = c(0x9ba9b5),
    lapis = c(0x93abf2), onLapis = c(0x0f1418), lapisSoft = c(0x1c2a4d),
    minium = c(0xff7d61), onMinium = c(0x1a0a06), miniumSoft = c(0x3d1912),
    verdigris = c(0x67cfdb), verdigrisSoft = c(0x0f2f33),
    ochre = c(0xf0b65c), ochreSoft = c(0x352710),
    danger = c(0xff94a6), dangerSoft = c(0x3b1520),
    focus = c(0xb5c7ff),
    profiles = listOf(c(0x7fcf8e), c(0x93abf2), c(0xf0b65c), c(0xe19ad8), c(0xa9bccc), c(0xf2a07f)),
)

private fun EscribaColors.scheme(dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = lapis, onPrimary = onLapis,
        primaryContainer = lapisSoft, onPrimaryContainer = ink,
        secondary = inkMuted, onSecondary = paper,
        secondaryContainer = lapisSoft, onSecondaryContainer = ink,
        tertiary = minium, onTertiary = onMinium,
        tertiaryContainer = miniumSoft, onTertiaryContainer = ink,
        error = danger, onError = paperRaised,
        errorContainer = dangerSoft, onErrorContainer = ink,
        background = paper, onBackground = ink,
        surface = paper, onSurface = ink,
        surfaceVariant = paperSunken, onSurfaceVariant = inkMuted,
        surfaceContainerLowest = paperRaised, surfaceContainerLow = paperRaised,
        surfaceContainer = paperRaised, surfaceContainerHigh = paperRaised,
        surfaceContainerHighest = paperSunken,
        surfaceBright = paperRaised, surfaceDim = paperSunken,
        inverseSurface = ink, inverseOnSurface = paper, inversePrimary = lapisSoft,
        outline = ruleStrong, outlineVariant = rule,
        scrim = Color(0x99000000),
    )
}

/**
 * Familias de la marca, empaquetadas (OFL, licencias en docs/licencias): la app no tiene permiso
 * de Internet, así que no se descargan. Las tres son variables: cada peso es una instancia.
 */
@OptIn(ExperimentalTextApi::class)
object EscribaFonts {
    private fun variable(res: Int, weight: FontWeight, vararg extra: FontVariation.Setting) =
        Font(res, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight), *extra))

    /** Literata con tamaño óptico de titular. */
    val display: FontFamily = FontFamily(
        variable(R.font.literata, FontWeight.Medium, FontVariation.Setting("opsz", 24f)),
        variable(R.font.literata, FontWeight.SemiBold, FontVariation.Setting("opsz", 24f)),
        variable(R.font.literata, FontWeight.Bold, FontVariation.Setting("opsz", 24f)),
    )
    val sans: FontFamily = FontFamily(
        variable(R.font.atkinson_next, FontWeight.Normal),
        variable(R.font.atkinson_next, FontWeight.Medium),
        variable(R.font.atkinson_next, FontWeight.SemiBold),
        variable(R.font.atkinson_next, FontWeight.Bold),
    )
    val mono: FontFamily = FontFamily(
        variable(R.font.atkinson_mono, FontWeight.Normal),
        variable(R.font.atkinson_mono, FontWeight.Medium),
        variable(R.font.atkinson_mono, FontWeight.SemiBold),
    )
}

/** Estilos sin equivalente en Material 3. */
@Immutable
data class EscribaType(
    val time: TextStyle,
    val metric: TextStyle,
    val overline: TextStyle,
)

private val type = EscribaType(
    time = TextStyle(fontFamily = EscribaFonts.mono, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
    // Cifra grande en la sans con números tabulares: en mono «3 h 55 min» se lee como una máquina de escribir.
    metric = TextStyle(fontFamily = EscribaFonts.sans, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
    overline = TextStyle(fontFamily = EscribaFonts.sans, fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.08.em),
)

private val typography = Typography().let { t ->
    fun sans(size: Int, line: Int, weight: FontWeight, spacing: Double = 0.0) =
        TextStyle(fontFamily = EscribaFonts.sans, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = spacing.em)
    fun display(size: Int, line: Int) = TextStyle(fontFamily = EscribaFonts.display, fontSize = size.sp, lineHeight = line.sp, fontWeight = FontWeight.SemiBold)
    t.copy(
        displaySmall = display(34, 40),
        headlineSmall = display(24, 30),
        titleLarge = display(24, 30),
        titleMedium = display(19, 26),
        titleSmall = sans(16, 24, FontWeight.SemiBold),
        bodyLarge = sans(16, 24, FontWeight.Normal),
        bodyMedium = sans(14, 20, FontWeight.Normal),
        bodySmall = sans(12, 16, FontWeight.Medium, 0.01),
        labelLarge = sans(14, 20, FontWeight.SemiBold),
        labelMedium = sans(12, 16, FontWeight.SemiBold),
        labelSmall = sans(12, 16, FontWeight.Medium, 0.01),
    )
}

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

/** Rejilla de 4 dp del sistema de diseño. */
object Space {
    val s1 = 4.dp
    val s2 = 8.dp
    val s3 = 12.dp
    val s4 = 16.dp
    val s5 = 24.dp
    val s6 = 32.dp
    val s7 = 48.dp
}

val LocalEscribaColors = staticCompositionLocalOf { LightColors }
val LocalEscribaType = staticCompositionLocalOf { type }

object Escriba {
    val colors: EscribaColors @Composable get() = LocalEscribaColors.current
    val type: EscribaType @Composable get() = LocalEscribaType.current
}

@Composable
fun EscribaTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    CompositionLocalProvider(LocalEscribaColors provides colors, LocalEscribaType provides type) {
        MaterialTheme(colorScheme = colors.scheme(dark), typography = typography, shapes = shapes, content = content)
    }
}

/**
 * Apariencia elegida por el usuario (Ajustes → Apariencia): «Claro» u «Oscuro». Hasta que elige,
 * sigue al sistema.
 */
class Appearance(context: Context) {
    private val prefs = context.getSharedPreferences("apariencia", Context.MODE_PRIVATE)
    private val _dark = MutableStateFlow(if (prefs.contains(KEY)) prefs.getBoolean(KEY, false) else null)

    /** `null` mientras el usuario no haya elegido. */
    val dark: StateFlow<Boolean?> = _dark

    fun set(dark: Boolean) {
        prefs.edit().putBoolean(KEY, dark).apply()
        _dark.value = dark
    }

    private companion object {
        const val KEY = "oscuro"
    }
}

@Composable
fun Appearance.resolve(dark: Boolean?): Boolean = dark ?: isSystemInDarkTheme()
