package dev.escriba.mobile.llm

import dev.escriba.datalayer.AppLog
import dev.escriba.llm.LlamaEngine
import dev.escriba.llm.LlamaStats
import dev.escriba.mobile.transcription.ModelSlot
import dev.escriba.mobile.transcription.ModelStore
import dev.escriba.shared.llm.Dates
import dev.escriba.shared.llm.Grammars
import dev.escriba.shared.llm.Insights
import dev.escriba.shared.llm.InsightsMerger
import dev.escriba.shared.llm.MergedSummary
import dev.escriba.shared.transcription.TranscriptLine
import dev.escriba.shared.vault.Profile
import dev.escriba.shared.voices.VoiceMath
import java.io.Closeable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Names of notes that already exist in the vault, so the model writes them the same way. */
data class KnownEntities(val people: List<String>, val projects: List<String>, val clients: List<String>)

/**
 * Extracts title, summary, decisions, tasks and entities from a conversation with a local LLM.
 *
 * If the transcript doesn't fit in the context, it's processed in chunks (map) and then merged
 * (reduce): the lists are joined without duplicates and the model writes the final title and
 * summary from the partial summaries.
 *
 * The instructions are in English, but the model writes in the language of the conversation.
 */
class Summarizer(private val models: ModelStore, private val slot: ModelSlot) {

    data class Stats(
        val model: String,
        val loadMs: Long,
        val totalMs: Long,
        val chunks: Int,
        val promptTokens: Int,
        val generatedTokens: Int,
        val promptTps: Double,
        val generateTps: Double,
    )

    data class Result(val insights: Insights, val stats: Stats)

    inner class Session(private val engine: LlamaEngine, private val file: Closeable, val modelName: String, private val loadMs: Long) : Closeable {

        fun summarize(
            lines: List<TranscriptLine>,
            profile: Profile,
            known: KnownEntities,
            zone: ZoneId,
        ): Result {
            val start = System.currentTimeMillis()
            val stats = ArrayList<LlamaStats>()
            val day = Instant.ofEpochMilli(lines.firstOrNull()?.wallStartMs ?: System.currentTimeMillis()).atZone(zone).toLocalDate()
            val system = systemPrompt(profile)
            val context = contextBlock(profile, known, lines, zone, day)

            // Budget: total context minus instructions, header and room for the answer.
            val reserved = engine.countTokens(system) + engine.countTokens(context) + RESPONSE_TOKENS + MARGIN_TOKENS
            val budget = (engine.contextSize - reserved).coerceAtLeast(MIN_CHUNK_TOKENS)
            val chunks = chunk(lines.map { render(it, zone) }, budget)

            val parts = chunks.mapIndexed { i, chunk ->
                val header = if (chunks.size > 1) "This is part ${i + 1} of ${chunks.size} of the conversation.\n" else ""
                val json = engine.generate(system, noThink(context + header + "Transcript:\n" + chunk), Grammars.INSIGHTS, RESPONSE_TOKENS)
                engine.lastStats()?.let(stats::add)
                Insights.parse(json)
            }

            val insights = if (parts.size == 1) {
                parts.single()
            } else {
                val partials = parts.mapIndexed { i, p -> "Part ${i + 1} — ${p.title}:\n${p.summary}" }.joinToString("\n\n")
                val merged = engine.generate(
                    "You are Escriba's assistant. Combine the partial summaries of one conversation into a single one, " +
                        "following these profile instructions:\n${profile.prompt}\n" +
                        "Return only JSON with \"title\" (3–8 words) and \"summary\". Write in the same language as the " +
                        "partial summaries and don't make anything up.",
                    noThink(partials),
                    Grammars.MERGED,
                    RESPONSE_TOKENS,
                )
                engine.lastStats()?.let(stats::add)
                InsightsMerger.merge(parts, MergedSummaryParser.parse(merged))
            }

            val clean = insights.copy(
                tasks = if (profile.tasks) insights.tasks.map { it.copy(due = Dates.validate(it.due, day)) } else emptyList(),
            )
            val result = Result(
                clean,
                Stats(
                    model = modelName,
                    loadMs = loadMs,
                    totalMs = System.currentTimeMillis() - start,
                    chunks = chunks.size,
                    promptTokens = stats.sumOf { it.promptTokens },
                    generatedTokens = stats.sumOf { it.generatedTokens },
                    promptTps = stats.map { it.promptTokensPerSecond }.average().takeIf { !it.isNaN() } ?: 0.0,
                    generateTps = stats.map { it.generateTokensPerSecond }.average().takeIf { !it.isNaN() } ?: 0.0,
                ),
            )
            AppLog.i(TAG, "Summary with $modelName: ${result.stats.chunks} chunks, ${result.stats.promptTokens} tokens read " +
                "(%.0f t/s), ${result.stats.generatedTokens} written (%.1f t/s), %d s".format(
                    result.stats.promptTps, result.stats.generateTps, result.stats.totalMs / 1000,
                ))
            return result
        }

        /** "Hybrid" Qwen3 reasons before answering unless told otherwise. */
        private fun noThink(user: String) =
            if (modelName.startsWith("Qwen3-", ignoreCase = true) && !modelName.contains("Instruct", ignoreCase = true)) "$user\n/no_think" else user

        override fun close() {
            engine.close()
            file.close()
            slot.mutex.unlock()
        }
    }

    /** Loads the model [name]. It takes the model slot: Whisper and the LLM are never loaded at the same time. */
    suspend fun open(name: String): Session {
        val entry = models.find(name) ?: error("There's no model \"$name\" in the models folder")
        slot.mutex.lock()
        try {
            val start = System.currentTimeMillis()
            val file = models.open(entry)
            val engine = runCatching { LlamaEngine.load(file.fd, entry.name, CONTEXT_TOKENS) }.getOrElse {
                file.close()
                throw it
            }
            val loadMs = System.currentTimeMillis() - start
            AppLog.i(TAG, "LLM ${entry.name} loaded in $loadMs ms (context ${engine.contextSize} tokens)")
            // Performance diagnostics: which instructions ggml uses and whether it repacks the weights for i8mm.
            AppLog.i(TAG, "CPU: " + LlamaEngine.systemInfo())
            LlamaEngine.nativeLog().lines()
                .filter { line -> listOf("repack", "REPACK", "buffer size", "CPU_", "n_threads", "flash", "type_k").any { it in line } }
                .takeLast(12)
                .forEach { AppLog.i(TAG, "llama: " + it.trim()) }
            return Session(engine, file, entry.name, loadMs)
        } catch (e: Throwable) {
            slot.mutex.unlock()
            throw e
        }
    }

    private fun systemPrompt(p: Profile): String = buildString {
        append("You are Escriba's assistant. You receive the automatic transcript of a recording ")
        append("(it may contain speech recognition errors) and return ONLY a JSON object.\n\n")
        append("Fields:\n")
        append("- \"title\": 3–8 words describing the topic. No date.\n")
        append("- \"summary\": whatever the profile instructions ask for. You may use Markdown lists and bold, but no headings.\n")
        append("- \"decisions\": only decisions made explicitly. Empty list if there are none.\n")
        if (p.tasks) {
            append("- \"tasks\": concrete actions someone commits to doing. \"owner\": who (\"${VoiceMath.ME}\" if it's the person recording), or null. ")
            append("\"due\": YYYY-MM-DD if a deadline is mentioned (work it out from today's date), or null.\n")
        } else {
            append("- \"tasks\": always return an empty list.\n")
        }
        append("- \"people\", \"projects\", \"clients\": proper names mentioned. If they match the known ones, write them exactly as in that list.\n")
        append("- \"tags\": 2 to 5 keywords, lowercase and without #.\n")
        append("- \"trivial\": true if the recording has no useful content (greetings, noise, TV, loose sentences).\n\n")
        append("Rules: write the title, summary, decisions and tasks in the same language as the conversation; ")
        append("don't make up anything that isn't in the transcript; if something can't be understood, leave it out.\n\n")
        append("Instructions of the profile \"${p.name}\":\n")
        append(p.prompt.ifBlank { "Summarize in a few sentences what was talked about." })
    }

    private fun contextBlock(p: Profile, known: KnownEntities, lines: List<TranscriptLine>, zone: ZoneId, day: LocalDate): String = buildString {
        val time = DateTimeFormatter.ofPattern("HH:mm")
        val start = lines.firstOrNull()?.let { Instant.ofEpochMilli(it.wallStartMs).atZone(zone).format(time) } ?: "?"
        val end = lines.lastOrNull()?.let { Instant.ofEpochMilli(it.wallEndMs).atZone(zone).format(time) } ?: "?"
        append("Today is ${Dates.spoken(day)}. The recording runs from $start to $end.\n")
        append("Profile: ${p.name}")
        p.client?.let { append(" · Client: ${it.removePrefix("[[").removeSuffix("]]")}") }
        append('\n')
        if (p.vocabulary.isNotEmpty()) append("Own vocabulary: ${p.vocabulary.joinToString(", ")}\n")
        if (known.people.isNotEmpty()) append("Known people: ${known.people.take(MAX_KNOWN).joinToString(", ")}\n")
        if (known.projects.isNotEmpty()) append("Known projects: ${known.projects.take(MAX_KNOWN).joinToString(", ")}\n")
        if (known.clients.isNotEmpty()) append("Known clients: ${known.clients.take(MAX_KNOWN).joinToString(", ")}\n")
        append('\n')
    }

    private fun render(l: TranscriptLine, zone: ZoneId): String {
        val t = Instant.ofEpochMilli(l.wallStartMs).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
        return "[$t] " + (l.speaker?.let { "$it: " } ?: "") + l.text
    }

    /** Chunks of whole lines that don't exceed [budget] tokens (estimated from characters). */
    private fun chunk(lines: List<String>, budget: Int): List<String> {
        val maxChars = budget * CHARS_PER_TOKEN
        val chunks = ArrayList<String>()
        val current = StringBuilder()
        for (line in lines) {
            if (current.isNotEmpty() && current.length + line.length + 1 > maxChars) {
                chunks += current.toString()
                current.clear()
            }
            current.append(line).append('\n')
        }
        if (current.isNotEmpty()) chunks += current.toString()
        return chunks.ifEmpty { listOf("(no transcript)") }
    }

    private object MergedSummaryParser {
        fun parse(json: String): MergedSummary = dev.escriba.shared.EscribaJson.decodeFromString(json.trim())
    }

    private companion object {
        const val TAG = "summary"
        const val CONTEXT_TOKENS = 8192
        const val RESPONSE_TOKENS = 1200
        const val MARGIN_TOKENS = 200
        const val MIN_CHUNK_TOKENS = 1500
        /** Conservative estimate (≈3.5 characters per token for Spanish in Qwen and Gemma; more in English). */
        const val CHARS_PER_TOKEN = 3
        const val MAX_KNOWN = 80
    }
}
