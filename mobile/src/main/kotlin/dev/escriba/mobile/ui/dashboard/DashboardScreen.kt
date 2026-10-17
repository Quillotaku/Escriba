package dev.escriba.mobile.ui.dashboard

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.escriba.mobile.EscribaApp
import dev.escriba.mobile.ui.components.EsCard
import dev.escriba.mobile.ui.components.MetricPart
import dev.escriba.mobile.ui.components.MetricTile
import dev.escriba.mobile.ui.components.Overline
import dev.escriba.mobile.ui.components.profileColor
import dev.escriba.mobile.ui.theme.Escriba
import dev.escriba.mobile.ui.theme.Space
import dev.escriba.mobile.ui.today.formatMinutes
import dev.escriba.mobile.ui.today.rememberConversations
import java.time.ZoneId
import kotlin.math.roundToInt

/** Panel: tareas, tiempo por perfil y lo que han trabajado los modelos, por semana, mes o total. */
@Composable
fun DashboardScreen(app: EscribaApp, padding: PaddingValues, openSession: (String, Int) -> Unit) {
    var period by rememberSaveable { mutableStateOf(Period.WEEK) }
    val zone = ZoneId.systemDefault()
    val from = remember(period) { periodStart(System.currentTimeMillis(), zone, period) }
    val conversations = rememberConversations(app, from)
    val insights by app.db.sessions().observeInsights().collectAsStateWithLifecycle(emptyList())
    val usage by remember(from) { app.db.sessions().observeUsageSince(from) }.collectAsStateWithLifecycle(emptyList())
    val panel = remember(from, conversations, insights, usage) { buildPanel(from, conversations, insights, usage) }
    val c = Escriba.colors

    LazyColumn(
        contentPadding = PaddingValues(start = Space.s4, end = Space.s4, top = padding.calculateTopPadding() + Space.s2, bottom = padding.calculateBottomPadding() + Space.s6),
        verticalArrangement = Arrangement.spacedBy(Space.s4),
    ) {
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Period.entries.forEachIndexed { i, p ->
                    SegmentedButton(selected = period == p, onClick = { period = p }, shape = SegmentedButtonDefaults.itemShape(i, Period.entries.size)) { Text(p.label) }
                }
            }
        }

        item { Overline("Tareas") }
        if (panel.tasks.isEmpty()) {
            item { Text("Las tareas que salgan en tus conversaciones aparecerán aquí.", style = MaterialTheme.typography.bodyLarge, color = c.inkMuted) }
        } else {
            items(panel.tasks.take(12), key = { "${it.sessionId}-${it.atMs}-${it.task.text}" }) { t ->
                EsCard(onClick = { openSession(t.sessionId, 1) }) {
                    Text(t.task.text, style = MaterialTheme.typography.bodyLarge, color = c.ink)
                    val meta = listOfNotNull(t.task.owner, t.task.due?.let { "para el $it" }, t.conversationTitle).joinToString(" · ")
                    if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
                }
            }
        }

        item {
            MetricTile(
                "Tiempo grabado",
                formatMinutes(panel.totalMinutes),
                panel.byProfile.map { MetricPart(it.name, c.profileColor(it.color), it.minutes.toLong(), formatMinutes(it.minutes)) },
                Modifier.padding(top = Space.s2).animateContentSize(),
            )
        }

        item {
            val minutes = (panel.audioMs / 60_000.0).roundToInt()
            MetricTile(
                "Transcripción",
                "${formatMinutes(minutes)} de voz",
                panel.transcription.mapIndexed { i, m ->
                    MetricPart(m.model, modelColor(i), m.audioMs, formatMinutes((m.audioMs / 60_000.0).roundToInt()))
                },
                Modifier.animateContentSize(),
            )
        }
        if (panel.audioMs > 0) {
            item {
                Text(
                    "Cálculo: ${formatMinutes((panel.transcribeMs / 60_000.0).roundToInt())} · ×%.2f del tiempo real (una hora de voz en unos %d min)"
                        .format(panel.realTimeFactor, (panel.realTimeFactor * 60).roundToInt()),
                    style = MaterialTheme.typography.bodyMedium, color = c.inkMuted,
                )
            }
        }

        item {
            MetricTile(
                "Resúmenes",
                "${formatCount(panel.promptTokens + panel.generatedTokens)} tokens",
                panel.summaries.mapIndexed { i, m -> MetricPart(m.model, modelColor(i), m.tokens, formatCount(m.tokens)) },
                Modifier.animateContentSize(),
            )
        }
        item {
            Text(
                if (panel.summaries.isEmpty()) "Los tokens se cuentan desde la versión 1.0: aparecerán con los próximos resúmenes."
                else "Leídos ${formatCount(panel.promptTokens)} · escritos ${formatCount(panel.generatedTokens)} · ${panel.summaries.sumOf { it.runs }} resúmenes",
                style = MaterialTheme.typography.bodyMedium, color = c.inkMuted,
            )
        }
    }
}

/** Colores para los modelos: los de la paleta de perfiles, en orden. */
@Composable
private fun modelColor(i: Int) = Escriba.colors.profiles[(listOf(1, 0, 5, 3, 2, 4))[i % 6]]
