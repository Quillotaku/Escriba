package dev.escriba.mobile.ui.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.escriba.mobile.R
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.escriba.mobile.EscribaApp
import dev.escriba.mobile.processing.ProcessingStatus
import dev.escriba.mobile.ui.components.EmptyState
import dev.escriba.mobile.ui.components.EsCard
import dev.escriba.mobile.ui.components.MetricPart
import dev.escriba.mobile.ui.components.MetricTile
import dev.escriba.mobile.ui.components.Overline
import dev.escriba.mobile.ui.components.ProfileDot
import dev.escriba.mobile.ui.components.StatusChip
import dev.escriba.mobile.ui.components.Tone
import dev.escriba.mobile.ui.components.profileColor
import dev.escriba.mobile.ui.theme.Escriba
import dev.escriba.mobile.ui.theme.Space
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Locale for dates, matching the app's texts (English only for now). If the app becomes
 * multilingual, this should follow the resources' locale.
 */
val APP_LOCALE: Locale = Locale.ENGLISH
val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val WEEKDAY = DateTimeFormatter.ofPattern("EEEE d", APP_LOCALE)
private val MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", APP_LOCALE)

fun Long.local(zone: ZoneId = ZoneId.systemDefault()) = Instant.ofEpochMilli(this).atZone(zone)

/** Conversations since [fromMs], recomputed whenever any data they depend on changes. */
@Composable
fun rememberConversations(app: EscribaApp, fromMs: Long): List<ConversationItem> {
    val dao = app.db.sessions()
    val sessions by dao.observeSessions().collectAsStateWithLifecycle(emptyList())
    val fragments by remember(fromMs) { dao.observeFragmentsSince(fromMs) }.collectAsStateWithLifecycle(emptyList())
    val notes by dao.observeNotes().collectAsStateWithLifecycle(emptyList())
    val insights by dao.observeInsights().collectAsStateWithLifecycle(emptyList())
    val set by app.profiles.set.collectAsStateWithLifecycle()
    val processing by ProcessingStatus.current.collectAsStateWithLifecycle()
    val watch by app.inbox.watchStatus.collectAsStateWithLifecycle()
    val recording = watch?.phase == "CAPTURING"
    return remember(sessions, fragments, notes, insights, set, processing?.sessionId, recording) {
        conversationsOf(
            sessions.filter { (it.endMs ?: it.lastSpeechEndMs) >= fromMs },
            fragments, notes, insights, set?.profiles.orEmpty(), set?.defaultProfile, processing?.sessionId, recording,
        )
    }
}

@Composable
fun ConversationState.chip() = when (this) {
    ConversationState.RECORDING -> StatusChip(stringResource(R.string.state_recording), Tone.RECORDING)
    ConversationState.PROCESSING -> StatusChip(stringResource(R.string.state_processing), Tone.BUSY)
    ConversationState.QUEUED -> StatusChip(stringResource(R.string.state_queued), Tone.WAIT)
    ConversationState.WRITTEN -> StatusChip(stringResource(R.string.state_written), Tone.DONE)
    ConversationState.FAILED -> StatusChip(stringResource(R.string.state_failed), Tone.ERROR)
    ConversationState.AUDIO_GONE -> StatusChip(stringResource(R.string.state_audio_gone), Tone.WAIT)
}

@Composable
fun TodayScreen(app: EscribaApp, padding: PaddingValues, openSession: (String, Int) -> Unit, openWatch: () -> Unit, openPanel: () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000)
            now = System.currentTimeMillis()
        }
    }
    val zone = ZoneId.systemDefault()
    val fromWeek = remember(now / 3_600_000) { weekStart(now, zone) }
    val conversations = rememberConversations(app, fromWeek)
    val insights by app.db.sessions().observeInsights().collectAsStateWithLifecycle(emptyList())
    val overview = remember(conversations, insights, now / 60_000) { buildOverview(now, zone, conversations, insights) }
    val processingNow by ProcessingStatus.current.collectAsStateWithLifecycle()
    val c = Escriba.colors

    LazyColumn(
        contentPadding = PaddingValues(start = Space.s4, end = Space.s4, top = padding.calculateTopPadding() + Space.s4, bottom = padding.calculateBottomPadding() + Space.s6),
        verticalArrangement = Arrangement.spacedBy(Space.s4),
    ) {
        item {
            Column {
                Text(now.local(zone).format(WEEKDAY).replaceFirstChar { it.titlecase(APP_LOCALE) }, style = MaterialTheme.typography.displaySmall, color = c.ink)
                Text(now.local(zone).format(MONTH), style = MaterialTheme.typography.bodyLarge, color = c.inkMuted)
            }
        }
        item { WatchCard(app, openWatch) }
        if (processingNow != null || overview.queued > 0 || overview.failed > 0) item { ProcessingCard(overview) }

        item { Overline(stringResource(R.string.tab_today), Modifier.padding(top = Space.s2)) }
        if (overview.today.isEmpty()) {
            item { Text(stringResource(R.string.today_empty), style = MaterialTheme.typography.bodyLarge, color = c.inkMuted) }
        } else {
            item {
                EsCard {
                    overview.today.forEachIndexed { i, conv ->
                        if (i > 0) HorizontalDivider(color = c.rule)
                        ConversationRow(conv) { openSession(conv.sessionId, conv.index) }
                    }
                }
            }
        }

        item {
            TextButton(onClick = openPanel, modifier = Modifier.padding(top = Space.s1)) {
                Text(stringResource(R.string.today_open_dashboard))
            }
        }
    }
}

@Composable
private fun WatchCard(app: EscribaApp, openWatch: () -> Unit) {
    val status by app.inbox.watchStatus.collectAsStateWithLifecycle()
    val peer by app.link.keys.peer.collectAsStateWithLifecycle()
    val c = Escriba.colors
    EsCard(onClick = openWatch) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(peer?.name ?: stringResource(R.string.watch), style = MaterialTheme.typography.titleSmall, color = c.ink, modifier = Modifier.weight(1f))
            val st = status
            when {
                peer == null -> StatusChip(stringResource(R.string.watch_not_paired), Tone.WAIT)
                st == null -> StatusChip(stringResource(R.string.watch_no_news), Tone.WAIT)
                st.phase == "CAPTURING" -> StatusChip(stringResource(R.string.state_recording), Tone.RECORDING)
                st.phase == "PAUSED" -> StatusChip(stringResource(R.string.watch_paused), Tone.WAIT)
                else -> StatusChip(stringResource(R.string.watch_stopped), Tone.WAIT)
            }
        }
        val st = status
        if (peer == null) {
            Text(stringResource(R.string.watch_pair_hint), style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
        } else if (st != null) {
            val parts = listOfNotNull(
                st.batteryPct?.let { stringResource(if (st.charging) R.string.watch_battery_charging else R.string.watch_battery, it.toInt()) },
                if (st.pendingFragments > 0) pluralStringResource(R.plurals.watch_pending, st.pendingFragments, st.pendingFragments)
                else stringResource(R.string.watch_all_sent),
                stringResource(R.string.watch_last_seen, st.atMs.local().format(HM)),
            )
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
        }
    }
}

@Composable
private fun ProcessingCard(overview: Overview) {
    val current by ProcessingStatus.current.collectAsStateWithLifecycle()
    val c = Escriba.colors
    val now = current
    if (now == null && overview.queued == 0 && overview.failed == 0) return
    EsCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s2)) {
            when {
                now != null -> StatusChip(stringResource(R.string.state_processing), Tone.BUSY)
                overview.queued > 0 -> StatusChip(stringResource(R.string.state_queued), Tone.WAIT)
            }
            if (overview.failed > 0) StatusChip(pluralStringResource(R.plurals.failed_count, overview.failed, overview.failed), Tone.ERROR)
        }
        if (now != null) {
            Text(now.step, style = MaterialTheme.typography.bodyLarge, color = c.ink)
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.lapis, trackColor = c.lapisSoft)
        }
        val waiting = overview.queued - if (now != null) 1 else 0
        if (waiting > 0) {
            Text(
                pluralStringResource(R.plurals.conversations_waiting, waiting, waiting),
                style = MaterialTheme.typography.bodyMedium, color = c.inkMuted,
            )
        }
    }
}

/** Compact row of a conversation: time, title, profile and state. */
@Composable
fun ConversationRow(conv: ConversationItem, onClick: () -> Unit) {
    val c = Escriba.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = Space.s7).clickable(onClick = onClick).padding(vertical = Space.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                conv.title ?: stringResource(R.string.conversation_at, conv.startMs.local().format(HM)),
                style = MaterialTheme.typography.titleSmall, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${conv.startMs.local().format(HM)}–${conv.endMs.local().format(HM)}", style = Escriba.type.time, color = c.inkMuted)
                Spacer(Modifier.width(Space.s3))
                ProfileDot(c.profileColor(conv.profileColor))
                Spacer(Modifier.width(Space.s1))
                Text("${conv.profileName} · ${formatMinutes(conv.roundedMin)}", style = MaterialTheme.typography.bodyMedium, color = c.inkMuted, maxLines = 1)
            }
        }
        Spacer(Modifier.width(Space.s2))
        conv.state.chip()
    }
}

