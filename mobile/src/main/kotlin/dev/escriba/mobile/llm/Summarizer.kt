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
import java.io.Closeable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Nombres de notas que ya existen en la bóveda, para que el modelo los escriba igual. */
data class KnownEntities(val people: List<String>, val projects: List<String>, val clients: List<String>)

/**
 * Saca título, resumen, decisiones, tareas y entidades de una conversación con un LLM local.
 *
 * Si la transcripción no cabe en el contexto, se procesa por trozos (map) y luego se fusiona
 * (reduce): las listas se juntan sin repetidos y el modelo escribe el título y el resumen finales
 * a partir de los resúmenes parciales.
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

            // Presupuesto: contexto total menos instrucciones, cabecera y espacio para la respuesta.
            val reserved = engine.countTokens(system) + engine.countTokens(context) + RESPONSE_TOKENS + MARGIN_TOKENS
            val budget = (engine.contextSize - reserved).coerceAtLeast(MIN_CHUNK_TOKENS)
            val chunks = chunk(lines.map { render(it, zone) }, budget)

            val parts = chunks.mapIndexed { i, chunk ->
                val header = if (chunks.size > 1) "Esta es la parte ${i + 1} de ${chunks.size} de la conversación.\n" else ""
                val json = engine.generate(system, noThink(context + header + "Transcripción:\n" + chunk), Grammars.INSIGHTS, RESPONSE_TOKENS)
                engine.lastStats()?.let(stats::add)
                Insights.parse(json)
            }

            val insights = if (parts.size == 1) {
                parts.single()
            } else {
                val partials = parts.mapIndexed { i, p -> "Parte ${i + 1} — ${p.title}:\n${p.summary}" }.joinToString("\n\n")
                val merged = engine.generate(
                    "Eres el asistente de Escriba. Combina los resúmenes parciales de una misma conversación en uno solo, " +
                        "siguiendo estas instrucciones del perfil:\n${profile.prompt}\n" +
                        "Devuelve solo JSON con \"titulo\" (3–8 palabras) y \"resumen\". Escribe en español y no inventes nada.",
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
            AppLog.i(TAG, "Resumen con $modelName: ${result.stats.chunks} trozos, ${result.stats.promptTokens} tokens leídos " +
                "(%.0f t/s), ${result.stats.generatedTokens} escritos (%.1f t/s), %d s".format(
                    result.stats.promptTps, result.stats.generateTps, result.stats.totalMs / 1000,
                ))
            return result
        }

        /** Qwen3 «híbrido» razona antes de contestar salvo que se le pida lo contrario. */
        private fun noThink(user: String) =
            if (modelName.startsWith("Qwen3-", ignoreCase = true) && !modelName.contains("Instruct", ignoreCase = true)) "$user\n/no_think" else user

        override fun close() {
            engine.close()
            file.close()
            slot.mutex.unlock()
        }
    }

    /** Carga el modelo [name]. Ocupa el hueco de modelo: Whisper y el LLM nunca están cargados a la vez. */
    suspend fun open(name: String): Session {
        val entry = models.find(name) ?: error("No hay ningún modelo «$name» en la carpeta de modelos")
        slot.mutex.lock()
        try {
            val start = System.currentTimeMillis()
            val file = models.open(entry)
            val engine = runCatching { LlamaEngine.load(file.fd, entry.name, CONTEXT_TOKENS) }.getOrElse {
                file.close()
                throw it
            }
            val loadMs = System.currentTimeMillis() - start
            AppLog.i(TAG, "LLM ${entry.name} cargado en $loadMs ms (contexto ${engine.contextSize} tokens)")
            // Diagnóstico de rendimiento: qué instrucciones usa ggml y si reempaqueta los pesos para i8mm.
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
        append("Eres el asistente de Escriba. Recibes la transcripción automática de una grabación ")
        append("(puede tener errores de reconocimiento de voz) y devuelves SOLO un objeto JSON.\n\n")
        append("Campos:\n")
        append("- \"titulo\": 3–8 palabras que describan el tema. Sin fecha.\n")
        append("- \"resumen\": lo que pidan las instrucciones del perfil. Puedes usar listas y negritas de Markdown, pero no encabezados.\n")
        append("- \"decisiones\": solo decisiones tomadas explícitamente. Lista vacía si no hay.\n")
        if (p.tasks) {
            append("- \"tareas\": acciones concretas que alguien se compromete a hacer. \"responsable\": quién (\"Yo\" si es quien graba), o null. ")
            append("\"fecha\": AAAA-MM-DD si se menciona un plazo (calcúlalo a partir de la fecha de hoy), o null.\n")
        } else {
            append("- \"tareas\": devuelve siempre una lista vacía.\n")
        }
        append("- \"personas\", \"proyectos\", \"clientes\": nombres propios mencionados. Si coinciden con los conocidos, escríbelos igual que en esa lista.\n")
        append("- \"etiquetas\": de 2 a 5 palabras clave, en minúscula y sin #.\n")
        append("- \"trivial\": true si la grabación no tiene contenido útil (saludos, ruido, televisión, frases sueltas).\n\n")
        append("Reglas: escribe en español; no inventes nada que no esté en la transcripción; si algo no se entiende, omítelo.\n\n")
        append("Instrucciones del perfil «${p.name}»:\n")
        append(p.prompt.ifBlank { "Resume en pocas frases de qué se ha hablado." })
    }

    private fun contextBlock(p: Profile, known: KnownEntities, lines: List<TranscriptLine>, zone: ZoneId, day: LocalDate): String = buildString {
        val time = DateTimeFormatter.ofPattern("HH:mm")
        val start = lines.firstOrNull()?.let { Instant.ofEpochMilli(it.wallStartMs).atZone(zone).format(time) } ?: "?"
        val end = lines.lastOrNull()?.let { Instant.ofEpochMilli(it.wallEndMs).atZone(zone).format(time) } ?: "?"
        append("Hoy es ${Dates.spoken(day)}. La grabación es de $start a $end.\n")
        append("Perfil: ${p.name}")
        p.client?.let { append(" · Cliente: ${it.removePrefix("[[").removeSuffix("]]")}") }
        append('\n')
        if (p.vocabulary.isNotEmpty()) append("Vocabulario propio: ${p.vocabulary.joinToString(", ")}\n")
        if (known.people.isNotEmpty()) append("Personas conocidas: ${known.people.take(MAX_KNOWN).joinToString(", ")}\n")
        if (known.projects.isNotEmpty()) append("Proyectos conocidos: ${known.projects.take(MAX_KNOWN).joinToString(", ")}\n")
        if (known.clients.isNotEmpty()) append("Clientes conocidos: ${known.clients.take(MAX_KNOWN).joinToString(", ")}\n")
        append('\n')
    }

    private fun render(l: TranscriptLine, zone: ZoneId): String {
        val t = Instant.ofEpochMilli(l.wallStartMs).atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm"))
        return "[$t] " + (l.speaker?.let { "$it: " } ?: "") + l.text
    }

    /** Trozos de líneas enteras que no pasan de [budget] tokens (estimados por caracteres). */
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
        return chunks.ifEmpty { listOf("(sin transcripción)") }
    }

    private object MergedSummaryParser {
        fun parse(json: String): MergedSummary = dev.escriba.shared.EscribaJson.decodeFromString(json.trim())
    }

    private companion object {
        const val TAG = "resumen"
        const val CONTEXT_TOKENS = 8192
        const val RESPONSE_TOKENS = 1200
        const val MARGIN_TOKENS = 200
        const val MIN_CHUNK_TOKENS = 1500
        /** Estimación prudente para español (≈3,5 caracteres por token en Qwen y Gemma). */
        const val CHARS_PER_TOKEN = 3
        const val MAX_KNOWN = 80
    }
}
