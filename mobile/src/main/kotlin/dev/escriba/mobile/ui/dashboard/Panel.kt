package dev.escriba.mobile.ui.dashboard

import androidx.annotation.StringRes
import dev.escriba.mobile.R
import dev.escriba.mobile.data.InsightsEntity
import dev.escriba.mobile.data.UsageEntity
import dev.escriba.mobile.ui.today.ConversationItem
import dev.escriba.mobile.ui.today.ProfileTotal
import dev.escriba.mobile.ui.today.TaskItem
import dev.escriba.mobile.ui.today.weekStart
import dev.escriba.shared.llm.Insights
import java.time.Instant
import java.time.ZoneId

/** Dashboard period. */
enum class Period(@StringRes val label: Int) { WEEK(R.string.period_week), MONTH(R.string.period_month), ALL(R.string.period_all) }

/** Where each period starts: Monday at midnight, the 1st of the month or the very beginning. */
fun periodStart(nowMs: Long, zone: ZoneId, period: Period): Long = when (period) {
    Period.WEEK -> weekStart(nowMs, zone)
    Period.MONTH -> Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
    Period.ALL -> 0L
}

/** A model's usage in the period. */
data class ModelUsage(
    val model: String,
    val runs: Int,
    val audioMs: Long,
    val computeMs: Long,
    val promptTokens: Long,
    val generatedTokens: Long,
) {
    val tokens: Long get() = promptTokens + generatedTokens
}

data class Panel(
    val tasks: List<TaskItem>,
    val byProfile: List<ProfileTotal>,
    val totalMinutes: Int,
    val transcription: List<ModelUsage>,
    val summaries: List<ModelUsage>,
) {
    val audioMs: Long get() = transcription.sumOf { it.audioMs }
    val transcribeMs: Long get() = transcription.sumOf { it.computeMs }
    val promptTokens: Long get() = summaries.sumOf { it.promptTokens }
    val generatedTokens: Long get() = summaries.sumOf { it.generatedTokens }

    /** Compute time per second of audio (×0.26: an hour of speech in 16 min). */
    val realTimeFactor: Double get() = if (audioMs > 0) transcribeMs.toDouble() / audioMs else 0.0
}

/** Everything on the dashboard since [fromMs]: tasks, time per profile and model usage. */
fun buildPanel(fromMs: Long, conversations: List<ConversationItem>, insights: List<InsightsEntity>, usage: List<UsageEntity>): Panel {
    val inPeriod = conversations.filter { it.startMs >= fromMs }
    val byProfile = inPeriod.groupBy { it.profileId }
        .map { (id, list) -> ProfileTotal(id, list.first().profileName, list.first().profileColor, list.sumOf { it.roundedMin }) }
        .sortedByDescending { it.minutes }
    val titles = conversations.associate { (it.sessionId to it.index) to it.title }
    val tasks = insights.filter { it.createdAtMs >= fromMs }
        .sortedByDescending { it.createdAtMs }
        .flatMap { i ->
            runCatching { Insights.parse(i.json).tasks }.getOrDefault(emptyList())
                .map { TaskItem(it, titles[i.sessionId to i.conversation], i.sessionId, i.createdAtMs) }
        }
    fun perModel(kind: String) = usage.filter { it.kind == kind && it.atMs >= fromMs }
        .groupBy { shortModelName(it.model) }
        .map { (model, rows) ->
            ModelUsage(
                model = model,
                runs = rows.size,
                audioMs = rows.sumOf { it.audioMs },
                computeMs = rows.sumOf { it.computeMs },
                promptTokens = rows.sumOf { it.promptTokens.toLong() },
                generatedTokens = rows.sumOf { it.generatedTokens.toLong() },
            )
        }
        .sortedByDescending { if (kind == UsageEntity.ASR) it.audioMs else it.tokens }
    return Panel(tasks, byProfile, byProfile.sumOf { it.minutes }, perModel(UsageEntity.ASR), perModel(UsageEntity.LLM))
}

/** "ggml-large-v3-turbo-q8_0.bin" → "large-v3-turbo-q8_0"; "gemma-3-4b-it-Q4_K_M.gguf" → "gemma-3-4b-it-Q4_K_M". */
fun shortModelName(model: String): String =
    model.substringAfterLast('/').removePrefix("ggml-").removeSuffix(".bin").removeSuffix(".gguf")

/** "12,345", with the English thousands separator. */
fun formatCount(n: Long): String = String.format(java.util.Locale.ROOT, "%,d", n)
