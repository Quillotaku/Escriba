package dev.escriba.mobile.ui.today

import dev.escriba.mobile.data.FragmentEntity
import dev.escriba.mobile.data.InsightsEntity
import dev.escriba.mobile.data.NoteEntity
import dev.escriba.mobile.data.SessionEntity
import dev.escriba.shared.EscribaJson
import dev.escriba.shared.Segment
import dev.escriba.shared.llm.Insights
import dev.escriba.shared.llm.Task
import dev.escriba.shared.vault.Conversations
import dev.escriba.shared.vault.FragmentInfo
import dev.escriba.shared.vault.Profile
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Estado de una conversación tal como lo enseña la app. */
enum class ConversationState { RECORDING, PROCESSING, QUEUED, WRITTEN, FAILED, AUDIO_GONE }

data class ConversationItem(
    val sessionId: String,
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val speechMs: Long,
    val roundedMin: Int,
    val profileId: String,
    val profileName: String,
    val profileColor: String?,
    val title: String?,
    val state: ConversationState,
)

data class ProfileTotal(val profileId: String, val name: String, val color: String?, val minutes: Int)

data class TaskItem(val task: Task, val conversationTitle: String?, val sessionId: String, val atMs: Long)

data class Overview(
    val today: List<ConversationItem>,
    val week: List<ProfileTotal>,
    val weekMinutes: Int,
    val tasks: List<TaskItem>,
    val queued: Int,
    val failed: Int,
)

/** Lunes a las 00:00 de la semana de [nowMs]. */
fun weekStart(nowMs: Long, zone: ZoneId): Long =
    Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        .atStartOfDay(zone).toInstant().toEpochMilli()

/**
 * Conversaciones de una lista de sesiones, con su estado y título. Las conversaciones se parten
 * igual que al escribir las notas (por silencios del perfil), así que cuadran con la bóveda.
 */
fun conversationsOf(
    sessions: List<SessionEntity>,
    fragments: List<FragmentEntity>,
    notes: List<NoteEntity>,
    insights: List<InsightsEntity>,
    profiles: Map<String, Profile>,
    fallback: Profile?,
    processingSessionId: String?,
    watchRecording: Boolean,
): List<ConversationItem> {
    val bySession = fragments.groupBy { it.sessionId }
    val notesByKey = notes.associateBy { it.sessionId to it.conversation }
    val insightsByKey = insights.associateBy { it.sessionId to it.conversation }
    return sessions.flatMap { s ->
        val profile = profiles[s.processedProfileId ?: s.profileId] ?: fallback
        val infos = bySession[s.id].orEmpty().map { f ->
            FragmentInfo(f.seq, f.startMs, f.endMs, runCatching { EscribaJson.decodeFromString<List<Segment>>(f.segmentsJson) }.getOrDefault(emptyList()))
        }
        val conversations = Conversations.split(infos, profile?.splitAfterSilenceMin ?: 15)
        conversations.map { c ->
            val key = s.id to c.index
            val title = insightsByKey[key]?.let { runCatching { Insights.parse(it.json).title }.getOrNull() }?.takeIf { it.isNotBlank() }
            val state = when {
                s.endMs == null && c == conversations.last() && watchRecording -> ConversationState.RECORDING
                s.id == processingSessionId -> ConversationState.PROCESSING
                key in notesByKey && s.processError == null -> ConversationState.WRITTEN
                s.processError != null -> ConversationState.FAILED
                s.audioDeletedAtMs != null -> ConversationState.AUDIO_GONE
                else -> ConversationState.QUEUED
            }
            ConversationItem(
                sessionId = s.id,
                index = c.index,
                startMs = c.startMs,
                endMs = c.endMs,
                speechMs = c.speechMs,
                roundedMin = Conversations.roundedMinutes(c.endMs - c.startMs, profile?.roundingMin ?: 5),
                profileId = profile?.id ?: s.profileId,
                profileName = profile?.name ?: s.profileId,
                profileColor = profile?.color,
                title = title,
                state = state,
            )
        }
    }.sortedByDescending { it.startMs }
}

fun buildOverview(
    nowMs: Long,
    zone: ZoneId,
    conversations: List<ConversationItem>,
    insights: List<InsightsEntity>,
): Overview {
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val fromWeek = weekStart(nowMs, zone)
    val week = conversations.filter { it.startMs >= fromWeek }
    val totals = week.groupBy { it.profileId }
        .map { (id, list) -> ProfileTotal(id, list.first().profileName, list.first().profileColor, list.sumOf { it.roundedMin }) }
        .sortedByDescending { it.minutes }
    val titles = conversations.associate { (it.sessionId to it.index) to it.title }
    val tasks = insights.filter { it.createdAtMs >= fromWeek }
        .sortedByDescending { it.createdAtMs }
        .flatMap { i ->
            runCatching { Insights.parse(i.json).tasks }.getOrDefault(emptyList())
                .map { TaskItem(it, titles[i.sessionId to i.conversation], i.sessionId, i.createdAtMs) }
        }
    return Overview(
        today = conversations.filter { Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate() == today },
        week = totals,
        weekMinutes = totals.sumOf { it.minutes },
        tasks = tasks,
        queued = conversations.count { it.state == ConversationState.QUEUED || it.state == ConversationState.PROCESSING },
        failed = conversations.map { it.sessionId to it.state }.distinct().count { it.second == ConversationState.FAILED },
    )
}

/** «1 h 25 min», «40 min». */
fun formatMinutes(min: Int): String = when {
    min < 60 -> "$min min"
    min % 60 == 0 -> "${min / 60} h"
    else -> "${min / 60} h ${min % 60} min"
}

/** «35 s», «3 min 35 s», «1 h 2 min». */
fun formatSpeech(ms: Long): String {
    val s = ms / 1000
    return when {
        s < 60 -> "$s s"
        s < 3600 -> if (s % 60 == 0L) "${s / 60} min" else "${s / 60} min ${s % 60} s"
        else -> "${s / 3600} h ${(s % 3600) / 60} min"
    }
}
