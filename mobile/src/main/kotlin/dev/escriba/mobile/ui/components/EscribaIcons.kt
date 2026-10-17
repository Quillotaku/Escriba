package dev.escriba.mobile.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Los pocos iconos que no trae `material-icons-core`, dibujados aquí sobre una cuadrícula de 24
 * con el mismo trazo redondeado que Material Symbols Rounded.
 */
object EscribaIcons {

    private fun icon(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            // Par-impar: un círculo dentro de otro deja un aro, sin cuidar el sentido del trazo.
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd, pathBuilder = block)
        }.build()

    /** Círculo como dos semicírculos. */
    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx, cy - r)
        arcTo(r, r, 0f, false, true, cx, cy + r)
        arcTo(r, r, 0f, false, true, cx, cy - r)
        close()
    }

    /** Micrófono. */
    val Mic: ImageVector by lazy {
        icon("Mic") {
            // Cápsula de 6 × 12 centrada.
            moveTo(12f, 3f)
            curveTo(10.34f, 3f, 9f, 4.34f, 9f, 6f)
            verticalLineTo(12f)
            curveTo(9f, 13.66f, 10.34f, 15f, 12f, 15f)
            curveTo(13.66f, 15f, 15f, 13.66f, 15f, 12f)
            verticalLineTo(6f)
            curveTo(15f, 4.34f, 13.66f, 3f, 12f, 3f)
            close()
            // Arco del soporte y pie.
            moveTo(5.5f, 11f)
            horizontalLineTo(7.5f)
            curveTo(7.5f, 13.49f, 9.51f, 15.5f, 12f, 15.5f)
            curveTo(14.49f, 15.5f, 16.5f, 13.49f, 16.5f, 11f)
            horizontalLineTo(18.5f)
            curveTo(18.5f, 14.25f, 16.08f, 16.95f, 13f, 17.42f)
            verticalLineTo(20f)
            horizontalLineTo(11f)
            verticalLineTo(17.42f)
            curveTo(7.92f, 16.95f, 5.5f, 14.25f, 5.5f, 11f)
            close()
        }
    }

    /** Dos barras de pausa. */
    val Pause: ImageVector by lazy {
        icon("Pause") {
            moveTo(7f, 5f); horizontalLineTo(10.5f); verticalLineTo(19f); horizontalLineTo(7f); close()
            moveTo(13.5f, 5f); horizontalLineTo(17f); verticalLineTo(19f); horizontalLineTo(13.5f); close()
        }
    }

    /** Esfera de reloj con agujas: «en espera». */
    val Schedule: ImageVector by lazy {
        icon("Schedule") {
            circle(12f, 12f, 9.5f)
            circle(12f, 12f, 7.5f)
            moveTo(11f, 7f); horizontalLineTo(13f); verticalLineTo(11.6f); lineTo(16.2f, 14.8f); lineTo(14.8f, 16.2f); lineTo(11f, 12.4f); close()
        }
    }

    /** Reloj de pulsera: la pestaña y los ajustes del reloj. */
    val Watch: ImageVector by lazy {
        icon("Watch") {
            moveTo(9f, 2f); horizontalLineTo(15f); lineTo(15.6f, 5.2f)
            arcToRelative(7f, 7f, 0f, false, true, 0f, 13.6f)
            lineTo(15f, 22f); horizontalLineTo(9f); lineTo(8.4f, 18.8f)
            arcToRelative(7f, 7f, 0f, false, true, 0f, -13.6f)
            close()
            circle(12f, 12f, 5f)
            moveTo(11.2f, 9f); horizontalLineTo(12.8f); verticalLineTo(11.5f); horizontalLineTo(14.5f); verticalLineTo(13f); horizontalLineTo(11.2f); close()
        }
    }

    /** Onda de voz: modelos y transcripción. */
    val Waveform: ImageVector by lazy {
        icon("Waveform") {
            fun bar(x: Float, h: Float) {
                moveTo(x, 12f - h / 2); horizontalLineTo(x + 2f); verticalLineTo(12f + h / 2); horizontalLineTo(x); close()
            }
            bar(3f, 6f); bar(7f, 12f); bar(11f, 18f); bar(15f, 10f); bar(19f, 4f)
        }
    }

    /** Tres barras de distinta altura: el panel. */
    val Chart: ImageVector by lazy {
        icon("Chart") {
            moveTo(4f, 12f); horizontalLineTo(8f); verticalLineTo(20f); horizontalLineTo(4f); close()
            moveTo(10f, 4f); horizontalLineTo(14f); verticalLineTo(20f); horizontalLineTo(10f); close()
            moveTo(16f, 9f); horizontalLineTo(20f); verticalLineTo(20f); horizontalLineTo(16f); close()
        }
    }

    /** Media luna: apariencia. */
    val Contrast: ImageVector by lazy {
        icon("Contrast") {
            circle(12f, 12f, 9.5f)
            // La mitad izquierda, hueca: queda la derecha rellena.
            moveTo(12f, 4.5f)
            arcTo(7.5f, 7.5f, 0f, false, false, 12f, 19.5f)
            close()
        }
    }

    /** Pila: optimización de batería. */
    val Battery: ImageVector by lazy {
        icon("Battery") {
            moveTo(9f, 2f); horizontalLineTo(15f); verticalLineTo(4f); horizontalLineTo(17f); verticalLineTo(22f); horizontalLineTo(7f); verticalLineTo(4f); horizontalLineTo(9f); close()
            moveTo(9f, 6f); verticalLineTo(20f); horizontalLineTo(15f); verticalLineTo(6f); close()
            moveTo(10f, 12f); horizontalLineTo(14f); verticalLineTo(19f); horizontalLineTo(10f); close()
        }
    }

    /** Líneas de texto: registro. */
    val Notes: ImageVector by lazy {
        icon("Notes") {
            moveTo(4f, 6f); horizontalLineTo(20f); verticalLineTo(8f); horizontalLineTo(4f); close()
            moveTo(4f, 11f); horizontalLineTo(20f); verticalLineTo(13f); horizontalLineTo(4f); close()
            moveTo(4f, 16f); horizontalLineTo(14f); verticalLineTo(18f); horizontalLineTo(4f); close()
        }
    }

    /** Dos personas: voces. */
    val People: ImageVector by lazy {
        icon("People") {
            circle(9f, 7.5f, 3.5f)
            moveTo(2f, 18f); curveTo(2f, 14.5f, 5.5f, 13f, 9f, 13f); curveTo(12.5f, 13f, 16f, 14.5f, 16f, 18f); verticalLineTo(20f); horizontalLineTo(2f); close()
            circle(16.5f, 8f, 3f)
            moveTo(17.5f, 13.2f); curveTo(20f, 13.7f, 22f, 15.2f, 22f, 18f); verticalLineTo(20f); horizontalLineTo(18f); verticalLineTo(18f); curveTo(18f, 16.2f, 17.9f, 14.6f, 17.5f, 13.2f); close()
        }
    }
}
