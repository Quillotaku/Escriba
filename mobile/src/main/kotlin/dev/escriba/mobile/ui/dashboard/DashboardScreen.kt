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
import androidx.compose.ui.res.stringResource
import dev.escriba.mobile.R
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

/** Dashboard: tasks, time per profile and what the models have done, by week, month or overall. */
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
                    SegmentedButton(selected = period == p, onClick = { period = p }, shape = SegmentedButtonDefaults.itemShape(i, Period.entries.size)) { Text(stringResource(p.label)) }
                }
            }
        }

        item { Overline(stringResource(R.string.dash_tasks)) }
        if (panel.tasks.isEmpty()) {
            item { Text(stringResource(R.string.dash_tasks_empty), style = MaterialTheme.typography.bodyLarge, color = c.inkMuted) }
        } else {
            items(panel.tasks.take(12), key = { "${it.sessionId}-${it.atMs}-${it.task.text}" }) { t ->
                EsCard(onClick = { openSession(t.sessionId, 1) }) {
                    Text(t.task.text, style = MaterialTheme.typography.bodyLarge, color = c.ink)
                    val meta = listOfNotNull(t.task.owner, t.task.due?.let { stringResource(R.string.dash_task_due, it) }, t.conversationTitle).joinToString(" · ")
                    if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
                }
            }
        }

        item {
            MetricTile(
                stringResource(R.string.dash_recorded_time),
                formatMinutes(panel.totalMinutes),
                panel.byProfile.map { MetricPart(it.name, c.profileColor(it.color), it.minutes.toLong(), formatMinutes(it.minutes)) },
                Modifier.padding(top = Space.s2).animateContentSize(),
            )
        }

        item {
            val minutes = (panel.audioMs / 60_000.0).roundToInt()
            MetricTile(
                stringResource(R.string.dash_transcription),
                stringResource(R.string.dash_speech_minutes, formatMinutes(minutes)),
                panel.transcription.mapIndexed { i, m ->
                    MetricPart(m.model, modelColor(i), m.audioMs, formatMinutes((m.audioMs / 60_000.0).roundToInt()))
                },
                Modifier.animateContentSize(),
            )
        }
        if (panel.audioMs > 0) {
            item {
                Text(
                    stringResource(
                        R.string.dash_compute,
                        formatMinutes((panel.transcribeMs / 60_000.0).roundToInt()),
                        panel.realTimeFactor,
                        (panel.realTimeFactor * 60).roundToInt(),
                    ),
                    style = MaterialTheme.typography.bodyMedium, color = c.inkMuted,
                )
            }
        }

        item {
            MetricTile(
                stringResource(R.string.dash_summaries),
                stringResource(R.string.dash_tokens, formatCount(panel.promptTokens + panel.generatedTokens)),
                panel.summaries.mapIndexed { i, m -> MetricPart(m.model, modelColor(i), m.tokens, formatCount(m.tokens)) },
                Modifier.animateContentSize(),
            )
        }
        item {
            Text(
                if (panel.summaries.isEmpty()) stringResource(R.string.dash_tokens_empty)
                else stringResource(R.string.dash_tokens_detail, formatCount(panel.promptTokens), formatCount(panel.generatedTokens), panel.summaries.sumOf { it.runs }),
                style = MaterialTheme.typography.bodyMedium, color = c.inkMuted,
            )
        }
    }
}

/** Colours for the models: the profile palette's, in order. */
@Composable
private fun modelColor(i: Int) = Escriba.colors.profiles[(listOf(1, 0, 5, 3, 2, 4))[i % 6]]
