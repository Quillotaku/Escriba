package dev.escriba.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.escriba.mobile.ui.theme.Escriba
import dev.escriba.mobile.ui.theme.EscribaColors
import dev.escriba.mobile.ui.theme.Space
import kotlin.math.abs

// --- StatusChip --------------------------------------------------------------

enum class Tone { DONE, WAIT, BUSY, RECORDING, ERROR }

/** Estado en una o dos palabras con icono: nunca solo color. */
@Composable
fun StatusChip(text: String, tone: Tone, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val c = Escriba.colors
    val (fill, ink) = when (tone) {
        Tone.DONE -> c.verdigrisSoft to c.verdigris
        Tone.WAIT -> c.ochreSoft to c.ochre
        Tone.BUSY -> c.lapisSoft to c.lapis
        Tone.RECORDING -> c.miniumSoft to c.minium
        Tone.ERROR -> c.dangerSoft to c.danger
    }
    val glyph = icon ?: when (tone) {
        Tone.DONE -> Icons.Rounded.CheckCircle
        Tone.WAIT -> EscribaIcons.Schedule
        Tone.BUSY -> Icons.Rounded.Refresh
        Tone.RECORDING -> null
        Tone.ERROR -> Icons.Rounded.Info
    }
    Row(
        modifier.clip(RoundedCornerShape(6.dp)).background(fill).padding(horizontal = Space.s2, vertical = Space.s1),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s1),
    ) {
        if (glyph != null) Icon(glyph, null, Modifier.size(14.dp), tint = ink)
        else Box(Modifier.size(8.dp).clip(CircleShape).background(ink))
        Text(text, style = MaterialTheme.typography.labelMedium, color = c.ink)
    }
}

// --- ProfileChip -------------------------------------------------------------

/** El color de perfil más cercano de los seis del sistema a un color de la bóveda (`#2E7D32`). */
fun EscribaColors.profileColor(hex: String?): Color {
    val rgb = hex?.removePrefix("#")?.takeIf { it.length == 6 }?.toLongOrNull(16) ?: return inkMuted
    // Se compara con la paleta clara, que es la que imita los colores que escribe la gente.
    val light = dev.escriba.mobile.ui.theme.LightColors.profiles
    val index = light.indices.minBy { i ->
        val p = light[i]
        val r = ((rgb shr 16) and 0xff) / 255f
        val g = ((rgb shr 8) and 0xff) / 255f
        val b = (rgb and 0xff) / 255f
        abs(p.red - r) * 3 + abs(p.green - g) * 4 + abs(p.blue - b) * 2
    }
    return profiles[index]
}

@Composable
fun ProfileDot(color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(8.dp).clip(CircleShape).background(color))
}

/** Perfil: punto de su color y nombre en tinta. Seleccionado, borde y fondo lapislázuli. */
@Composable
fun ProfileChip(name: String, color: Color, selected: Boolean = false, onClick: (() -> Unit)? = null) {
    val c = Escriba.colors
    val shape = CircleShape
    Row(
        Modifier
            .clip(shape)
            .border(1.dp, if (selected) c.lapis else c.rule, shape)
            .background(if (selected) c.lapisSoft else c.paperRaised)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(horizontal = Space.s3, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s2),
    ) {
        ProfileDot(color)
        Text(name, style = MaterialTheme.typography.labelLarge, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// --- Superficies -------------------------------------------------------------

/** Tarjeta del sistema: papel elevado con filete, sin sombra. */
@Composable
fun EsCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = Escriba.colors
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, c.rule, shape)
            .background(c.paperRaised)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(Space.s4),
        verticalArrangement = Arrangement.spacedBy(Space.s2),
        content = content,
    )
}

/** Encabezado de sección del panel, en mayúsculas espaciadas. */
@Composable
fun Overline(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = Escriba.type.overline, color = Escriba.colors.inkMuted, modifier = modifier.semantics { contentDescription = text })
}

/** Línea de metadatos: textos secundarios separados por espacio, con las horas en mono. */
@Composable
fun MetaRow(vararg parts: String, time: String? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s3), verticalAlignment = Alignment.CenterVertically) {
        if (time != null) Text(time, style = Escriba.type.time, color = Escriba.colors.inkMuted)
        parts.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = Escriba.colors.inkMuted) }
    }
}

/** Estado vacío: qué aparecerá aquí y cómo conseguirlo. */
@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(vertical = Space.s6), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Escriba.colors.ink)
        Text(body, style = MaterialTheme.typography.bodyLarge, color = Escriba.colors.inkMuted)
        if (action != null) {
            Spacer(Modifier.height(Space.s2))
            action()
        }
    }
}

/** Fila de ajustes: icono, título, descripción y flecha. */
@Composable
fun SettingRow(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit, trailing: (@Composable RowScope.() -> Unit)? = null) {
    val c = Escriba.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = Space.s7).clickable(onClick = onClick).padding(horizontal = Space.s4, vertical = Space.s3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s4),
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = c.inkMuted)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = c.ink)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
        }
        if (trailing != null) trailing() else Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.inkMuted)
    }
}

// --- MetricTile --------------------------------------------------------------

@Immutable
data class MetricPart(val label: String, val color: Color, val value: Long, val valueText: String)

/** Cifra del panel con su desglose en barra apilada y leyenda. */
@Composable
fun MetricTile(title: String, value: String, parts: List<MetricPart>, modifier: Modifier = Modifier) {
    val c = Escriba.colors
    EsCard(modifier) {
        Overline(title)
        Text(value, style = Escriba.type.metric, color = c.ink)
        val total = parts.sumOf { it.value }
        if (total > 0) {
            Row(
                Modifier.fillMaxWidth().height(8.dp).clip(CircleShape).background(c.paperSunken),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                parts.filter { it.value > 0 }.forEach { p -> Box(Modifier.weight(p.value.toFloat()).height(8.dp).background(p.color)) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(Space.s1)) {
                parts.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProfileDot(p.color)
                        Spacer(Modifier.width(Space.s2))
                        Text(p.label, style = MaterialTheme.typography.labelLarge, color = c.ink, modifier = Modifier.weight(1f))
                        Text(p.valueText, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"), color = c.inkMuted)
                    }
                }
            }
        }
    }
}

// --- TranscriptBlock ---------------------------------------------------------

@Immutable
data class TranscriptParagraph(val time: String, val speaker: String?, val known: Boolean, val text: String)

/** Transcripción como en la nota: párrafos con su hora y quien habla. */
@Composable
fun TranscriptBlock(paragraphs: List<TranscriptParagraph>, modifier: Modifier = Modifier) {
    val c = Escriba.colors
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.paperSunken).padding(Space.s4),
        verticalArrangement = Arrangement.spacedBy(Space.s5),
    ) {
        paragraphs.forEach { p ->
            Column(verticalArrangement = Arrangement.spacedBy(Space.s1)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s1), verticalAlignment = Alignment.CenterVertically) {
                    Text(p.time, style = Escriba.type.time, color = c.inkMuted)
                    if (p.speaker != null) {
                        Text("·", style = MaterialTheme.typography.labelLarge, color = c.inkMuted)
                        Text(
                            p.speaker,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (p.known) c.ink else c.inkMuted,
                            fontWeight = if (p.known) FontWeight.SemiBold else FontWeight.Medium,
                        )
                    }
                }
                Text(p.text, style = MaterialTheme.typography.bodyLarge, color = c.ink)
            }
        }
    }
}
