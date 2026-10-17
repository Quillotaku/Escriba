package dev.escriba.shared.llm

import dev.escriba.shared.EscribaJson
import kotlinx.serialization.Serializable
import java.text.Normalizer
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** A task extracted by the LLM. */
@Serializable
data class Task(
    val task: String,
    val owner: String? = null,
    /** YYYY-MM-DD or null. */
    val due: String? = null,
) {
    /** The task's text (the JSON key is `task`, which reads better for the model). */
    val text: String get() = task
}

/** What the LLM extracts from a conversation. The JSON keys are what the model sees. */
@Serializable
data class Insights(
    val title: String,
    val summary: String,
    val decisions: List<String> = emptyList(),
    val tasks: List<Task> = emptyList(),
    val people: List<String> = emptyList(),
    val projects: List<String> = emptyList(),
    val clients: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val trivial: Boolean = false,
) {
    companion object {
        fun parse(json: String): Insights = EscribaJson.decodeFromString(json.trim())
    }
}

/** Answer of the merge step, when the conversation was processed in chunks. */
@Serializable
data class MergedSummary(
    val title: String,
    val summary: String,
)

/**
 * GBNF grammars for llama.cpp: the model can only produce JSON with exactly these fields, in this
 * order. A malformed answer never needs "fixing".
 */
object Grammars {
    private const val COMMON = """
string ::= "\"" char* "\""
char ::= [^"\\\x7F\x00-\x1F] | "\\" (["\\/bfnrt] | "u" [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F])
strarr ::= "[" ws ( string ( "," ws string )* )? ws "]"
bool ::= "true" | "false"
ws ::= [ \t\n]? [ \t\n]? [ \t\n]? [ \t\n]?
"""

    val INSIGHTS = """
root ::= "{" ws "\"title\":" ws string "," ws "\"summary\":" ws string "," ws "\"decisions\":" ws strarr "," ws "\"tasks\":" ws taskarr "," ws "\"people\":" ws strarr "," ws "\"projects\":" ws strarr "," ws "\"clients\":" ws strarr "," ws "\"tags\":" ws strarr "," ws "\"trivial\":" ws bool ws "}"
taskarr ::= "[" ws ( task ( "," ws task )* )? ws "]"
task ::= "{" ws "\"task\":" ws string "," ws "\"owner\":" ws nstring "," ws "\"due\":" ws ndate ws "}"
nstring ::= string | "null"
ndate ::= "\"" [0-9] [0-9] [0-9] [0-9] "-" [0-9] [0-9] "-" [0-9] [0-9] "\"" | "null"
""".trimStart() + COMMON

    val MERGED = """
root ::= "{" ws "\"title\":" ws string "," ws "\"summary\":" ws string ws "}"
""".trimStart() + COMMON
}

/** Context text for the model: the date in words so it can resolve "on Friday". */
object Dates {
    fun spoken(date: LocalDate): String =
        "${date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)}, ${date.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${date.dayOfMonth}, ${date.year}"

    /**
     * Checks the date the model returned: it must be a real, reasonable date (from yesterday up
     * to three years ahead). Otherwise it's dropped instead of putting a made-up date on the task.
     */
    fun validate(iso: String?, today: LocalDate): String? {
        val d = iso?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        return if (d.isBefore(today.minusDays(1)) || d.isAfter(today.plusYears(3))) null else d.toString()
    }
}

/**
 * Links the names the LLM mentions to notes that ALREADY exist in the vault (People, Projects,
 * Clients). It never creates new links, so "[[Ana]]" and "[[Ana López]]" don't show up as two
 * different people.
 */
class EntityLinker(known: Collection<String>) {
    private val byKey = known.associateBy(::key)
    private val all = known.toList()

    /** The note name matching [name], or null if there's no clear one. */
    fun match(name: String): String? {
        val k = key(name)
        if (k.isEmpty()) return null
        byKey[k]?.let { return it }
        // "Ana" → "Ana López", only if she's the only one starting like that.
        val partial = all.filter { key(it).startsWith("$k ") || key(it).split(' ').contains(k) }
        return partial.singleOrNull()
    }

    /** `[[Note]]` if it exists; otherwise the name as is. */
    fun linkOrPlain(name: String): String = match(name)?.let { "[[$it]]" } ?: name.trim()

    companion object {
        fun key(s: String): String =
            Normalizer.normalize(s.trim().lowercase(), Normalizer.Form.NFD)
                .replace(Regex("\\p{M}+"), "")
                .replace(Regex("[^a-z0-9ñ ]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
    }
}

/**
 * Merges the partial results of a conversation processed in chunks. Lists are joined without
 * duplicates; the final title and summary are written by the model in a separate merge step.
 */
object InsightsMerger {
    fun merge(parts: List<Insights>, merged: MergedSummary): Insights = Insights(
        title = merged.title,
        summary = merged.summary,
        decisions = distinct(parts.flatMap { it.decisions }),
        tasks = parts.flatMap { it.tasks }.distinctBy { EntityLinker.key(it.text) },
        people = distinct(parts.flatMap { it.people }),
        projects = distinct(parts.flatMap { it.projects }),
        clients = distinct(parts.flatMap { it.clients }),
        tags = distinct(parts.flatMap { it.tags }).take(6),
        // Trivial only if every chunk is.
        trivial = parts.isNotEmpty() && parts.all { it.trivial },
    )

    private fun distinct(items: List<String>) = items.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy(EntityLinker::key)
}
