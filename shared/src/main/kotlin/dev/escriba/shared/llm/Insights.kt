package dev.escriba.shared.llm

import dev.escriba.shared.EscribaJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.text.Normalizer
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** Tarea extraída por el LLM. */
@Serializable
data class Task(
    @SerialName("tarea") val text: String,
    @SerialName("responsable") val owner: String? = null,
    /** AAAA-MM-DD o null. */
    @SerialName("fecha") val due: String? = null,
)

/** Lo que el LLM saca de una conversación. Los nombres JSON van en español porque los ve el modelo. */
@Serializable
data class Insights(
    @SerialName("titulo") val title: String,
    @SerialName("resumen") val summary: String,
    @SerialName("decisiones") val decisions: List<String> = emptyList(),
    @SerialName("tareas") val tasks: List<Task> = emptyList(),
    @SerialName("personas") val people: List<String> = emptyList(),
    @SerialName("proyectos") val projects: List<String> = emptyList(),
    @SerialName("clientes") val clients: List<String> = emptyList(),
    @SerialName("etiquetas") val tags: List<String> = emptyList(),
    @SerialName("trivial") val trivial: Boolean = false,
) {
    companion object {
        fun parse(json: String): Insights = EscribaJson.decodeFromString(json.trim())
    }
}

/** Respuesta del paso de fusión, cuando la conversación se procesó por trozos. */
@Serializable
data class MergedSummary(
    @SerialName("titulo") val title: String,
    @SerialName("resumen") val summary: String,
)

/**
 * Gramáticas GBNF para llama.cpp: el modelo solo puede generar JSON con exactamente estos campos
 * y en este orden. Así nunca hay que «arreglar» una respuesta mal formada.
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
root ::= "{" ws "\"titulo\":" ws string "," ws "\"resumen\":" ws string "," ws "\"decisiones\":" ws strarr "," ws "\"tareas\":" ws taskarr "," ws "\"personas\":" ws strarr "," ws "\"proyectos\":" ws strarr "," ws "\"clientes\":" ws strarr "," ws "\"etiquetas\":" ws strarr "," ws "\"trivial\":" ws bool ws "}"
taskarr ::= "[" ws ( task ( "," ws task )* )? ws "]"
task ::= "{" ws "\"tarea\":" ws string "," ws "\"responsable\":" ws nstring "," ws "\"fecha\":" ws ndate ws "}"
nstring ::= string | "null"
ndate ::= "\"" [0-9] [0-9] [0-9] [0-9] "-" [0-9] [0-9] "-" [0-9] [0-9] "\"" | "null"
""".trimStart() + COMMON

    val MERGED = """
root ::= "{" ws "\"titulo\":" ws string "," ws "\"resumen\":" ws string ws "}"
""".trimStart() + COMMON
}

/** Texto del contexto que recibe el modelo: la fecha en palabras para que resuelva «el viernes». */
object Dates {
    private val ES = Locale.forLanguageTag("es-ES")

    fun spoken(date: LocalDate): String =
        "${date.dayOfWeek.getDisplayName(TextStyle.FULL, ES)}, ${date.dayOfMonth} de ${date.month.getDisplayName(TextStyle.FULL, ES)} de ${date.year}"

    /**
     * Comprueba la fecha que devolvió el modelo: tiene que ser una fecha real y razonable (de ayer
     * a tres años vista). Si no, se descarta en lugar de poner una fecha inventada en la tarea.
     */
    fun validate(iso: String?, today: LocalDate): String? {
        val d = iso?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
        return if (d.isBefore(today.minusDays(1)) || d.isAfter(today.plusYears(3))) null else d.toString()
    }
}

/**
 * Enlaza los nombres que menciona el LLM con notas que YA existen en la bóveda (Personas,
 * Proyectos, Clientes). Nunca crea enlaces nuevos: así no aparecen «[[Ana]]» y «[[Ana López]]»
 * como dos personas distintas.
 */
class EntityLinker(known: Collection<String>) {
    private val byKey = known.associateBy(::key)
    private val all = known.toList()

    /** El nombre de nota que corresponde a [name], o null si no hay ninguno claro. */
    fun match(name: String): String? {
        val k = key(name)
        if (k.isEmpty()) return null
        byKey[k]?.let { return it }
        // «Ana» → «Ana López», solo si es la única que empieza así.
        val partial = all.filter { key(it).startsWith("$k ") || key(it).split(' ').contains(k) }
        return partial.singleOrNull()
    }

    /** `[[Nota]]` si existe; si no, el nombre tal cual. */
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
 * Une los resultados parciales de una conversación procesada por trozos. Las listas se juntan sin
 * repetidos; el título y el resumen finales los escribe el modelo en un paso de fusión aparte.
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
        // Trivial solo si todos los trozos lo son.
        trivial = parts.isNotEmpty() && parts.all { it.trivial },
    )

    private fun distinct(items: List<String>) = items.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy(EntityLinker::key)
}
