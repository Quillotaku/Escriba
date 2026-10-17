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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

val ES: Locale = Locale.forLanguageTag("es-ES")
val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val WEEKDAY = DateTimeFormatter.ofPattern("EEEE d", ES)
private val MONTH = DateTimeFormatter.ofPattern("MMMM 'de' yyyy", ES)

fun Long.local(zone: ZoneId = ZoneId.systemDefault()) = Instant.ofEpochMilli(this).atZone(zone)

/** Conversaciones desde [fromMs], recalculadas cuando cambia cualquier dato del que dependen. */
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
    ConversationState.RECORDING -> StatusChip("Grabando", Tone.RECORDING)
    ConversationState.PROCESSING -> StatusChip("Procesando", Tone.BUSY)
    ConversationState.QUEUED -> StatusChip("En cola", Tone.WAIT)
    ConversationState.WRITTEN -> StatusChip("Escrita", Tone.DONE)
    ConversationState.FAILED -> StatusChip("Error", Tone.ERROR)
    ConversationState.AUDIO_GONE -> StatusChip("Sin audio", Tone.WAIT)
}

@Composable
fun TodayScreen(app: EscribaApp, padding: PaddingValues, openSession: (String, Int) -> Unit, openWatch: () -> Unit) {
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
                Text(now.local(zone).format(WEEKDAY).replaceFirstChar { it.titlecase(ES) }, style = MaterialTheme.typography.displaySmall, color = c.ink)
                Text(now.local(zone).format(MONTH), style = MaterialTheme.typography.bodyLarge, color = c.inkMuted)
            }
        }
        item { WatchCard(app, openWatch) }
        if (processingNow != null || overview.queued > 0 || overview.failed > 0) item { ProcessingCard(overview) }

        item { Overline("Hoy", Modifier.padding(top = Space.s2)) }
        if (overview.today.isEmpty()) {
            item { Text("Aún no hay conversaciones hoy. Lo que grabe el reloj aparecerá aquí.", style = MaterialTheme.typography.bodyLarge, color = c.inkMuted) }
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
            val parts = overview.week.map { MetricPart(it.name, c.profileColor(it.color), it.minutes.toLong(), formatMinutes(it.minutes)) }
            MetricTile("Esta semana", formatMinutes(overview.weekMinutes), parts, Modifier.padding(top = Space.s2))
        }

        item { Overline("Tareas de la semana", Modifier.padding(top = Space.s2)) }
        if (overview.tasks.isEmpty()) {
            item { Text("Las tareas que salgan en tus conversaciones aparecerán aquí.", style = MaterialTheme.typography.bodyLarge, color = c.inkMuted) }
        } else {
            items(overview.tasks.take(8), key = { "${it.sessionId}-${it.task.text}" }) { t ->
                EsCard(onClick = { openSession(t.sessionId, 1) }) {
                    Text(t.task.text, style = MaterialTheme.typography.bodyLarge, color = c.ink)
                    val meta = listOfNotNull(t.task.owner, t.task.due?.let { "para el $it" }, t.conversationTitle).joinToString(" · ")
                    if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
                }
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
            Text(peer?.name ?: "Reloj", style = MaterialTheme.typography.titleSmall, color = c.ink, modifier = Modifier.weight(1f))
            val st = status
            when {
                peer == null -> StatusChip("Sin emparejar", Tone.WAIT)
                st == null -> StatusChip("Sin noticias", Tone.WAIT)
                st.phase == "CAPTURING" -> StatusChip("Grabando", Tone.RECORDING)
                st.phase == "PAUSED" -> StatusChip("En pausa", Tone.WAIT)
                else -> StatusChip("Parado", Tone.WAIT)
            }
        }
        val st = status
        if (peer == null) {
            Text("Empareja el reloj para que empiece a enviar lo que graba.", style = MaterialTheme.typography.bodyMedium, color = c.inkMuted)
        } else if (st != null) {
            val parts = listOfNotNull(
                st.batteryPct?.let { "Batería ${it.toInt()} %" + if (st.charging) ", cargando" else "" },
                if (st.pendingFragments > 0) "${st.pendingFragments} fragmentos por enviar" else "Todo enviado",
                "aviso de las ${st.atMs.local().format(HM)}",
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
                now != null -> StatusChip("Procesando", Tone.BUSY)
                overview.queued > 0 -> StatusChip("En cola", Tone.WAIT)
            }
            if (overview.failed > 0) StatusChip(if (overview.failed == 1) "1 con error" else "${overview.failed} con error", Tone.ERROR)
        }
        if (now != null) {
            Text(now.step, style = MaterialTheme.typography.bodyLarge, color = c.ink)
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.lapis, trackColor = c.lapisSoft)
        }
        val waiting = overview.queued - if (now != null) 1 else 0
        if (waiting > 0) {
            Text(
                "$waiting ${if (waiting == 1) "conversación espera" else "conversaciones esperan"}. Las sesiones largas se procesan con el móvil cargando.",
                style = MaterialTheme.typography.bodyMedium, color = c.inkMuted,
            )
        }
    }
}

/** Fila compacta de una conversación: hora, título, perfil y estado. */
@Composable
fun ConversationRow(conv: ConversationItem, onClick: () -> Unit) {
    val c = Escriba.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = Space.s7).clickable(onClick = onClick).padding(vertical = Space.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                conv.title ?: "Conversación de las ${conv.startMs.local().format(HM)}",
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

