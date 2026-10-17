package dev.escriba.wear.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Watch icons, drawn on a 24-unit grid (without the icon library: it's heavy). */
object WatchIcons {

    private fun icon(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd, pathBuilder = block)
        }.build()

    val Mic: ImageVector by lazy {
        icon("Mic") {
            moveTo(12f, 3f)
            curveTo(10.34f, 3f, 9f, 4.34f, 9f, 6f)
            verticalLineTo(12f)
            curveTo(9f, 13.66f, 10.34f, 15f, 12f, 15f)
            curveTo(13.66f, 15f, 15f, 13.66f, 15f, 12f)
            verticalLineTo(6f)
            curveTo(15f, 4.34f, 13.66f, 3f, 12f, 3f)
            close()
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

    /** Rounded square: stop. */
    val Stop: ImageVector by lazy {
        icon("Stop") {
            moveTo(8f, 6f); horizontalLineTo(16f)
            arcTo(2f, 2f, 0f, false, true, 18f, 8f); verticalLineTo(16f)
            arcTo(2f, 2f, 0f, false, true, 16f, 18f); horizontalLineTo(8f)
            arcTo(2f, 2f, 0f, false, true, 6f, 16f); verticalLineTo(8f)
            arcTo(2f, 2f, 0f, false, true, 8f, 6f)
            close()
        }
    }

    /** Phone. */
    val Phone: ImageVector by lazy {
        icon("Phone") {
            moveTo(8f, 2f); horizontalLineTo(16f)
            arcTo(2f, 2f, 0f, false, true, 18f, 4f); verticalLineTo(20f)
            arcTo(2f, 2f, 0f, false, true, 16f, 22f); horizontalLineTo(8f)
            arcTo(2f, 2f, 0f, false, true, 6f, 20f); verticalLineTo(4f)
            arcTo(2f, 2f, 0f, false, true, 8f, 2f)
            close()
            moveTo(8f, 5f); verticalLineTo(18f); horizontalLineTo(16f); verticalLineTo(5f); close()
        }
    }

    /** Three sliders: settings. */
    val Tune: ImageVector by lazy {
        icon("Tune") {
            fun bar(y: Float, knob: Float) {
                moveTo(4f, y - 1f); horizontalLineTo(20f); verticalLineTo(y + 1f); horizontalLineTo(4f); close()
                moveTo(knob - 2f, y - 3f); horizontalLineTo(knob + 2f); verticalLineTo(y + 3f); horizontalLineTo(knob - 2f); close()
            }
            bar(6f, 15f); bar(12f, 8f); bar(18f, 13f)
        }
    }

    /** Lines of text: diagnostics and log. */
    val Lines: ImageVector by lazy {
        icon("Lines") {
            moveTo(4f, 6f); horizontalLineTo(20f); verticalLineTo(8f); horizontalLineTo(4f); close()
            moveTo(4f, 11f); horizontalLineTo(20f); verticalLineTo(13f); horizontalLineTo(4f); close()
            moveTo(4f, 16f); horizontalLineTo(14f); verticalLineTo(18f); horizontalLineTo(4f); close()
        }
    }
}
