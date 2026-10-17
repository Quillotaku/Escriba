package dev.escriba.shared.vault

/** Value of a frontmatter property. */
sealed interface YamlValue {
    data class Text(val value: String) : YamlValue
    data class Bool(val value: Boolean) : YamlValue
    data class Number(val value: Double) : YamlValue
    data class Items(val values: List<String>) : YamlValue
    data object Empty : YamlValue
}

/** Parse error with its line (1 = first line of the file). */
data class YamlError(val line: Int, val message: String) {
    override fun toString() = "line $line: $message"
}

/**
 * Obsidian frontmatter: the `---` block at the start of a note.
 *
 * Only supports what Obsidian's Properties view produces (flat keys, text, numbers, booleans and
 * lists, inline or block). Nested objects are rejected with a clear error instead of being half
 * understood.
 */
data class Frontmatter(val properties: LinkedHashMap<String, YamlValue>, val body: String) {

    fun text(key: String): String? = when (val v = properties[key]) {
        is YamlValue.Text -> v.value
        is YamlValue.Number -> formatNumber(v.value)
        is YamlValue.Bool -> v.value.toString()
        else -> null
    }

    companion object {
        /** Splits the frontmatter from the body. A note without frontmatter returns no properties. */
        fun parse(note: String): Pair<Frontmatter, List<YamlError>> {
            val text = note.removePrefix("﻿").replace("\r\n", "\n")
            val lines = text.split('\n')
            if (lines.firstOrNull()?.trimEnd() != "---") return Frontmatter(LinkedHashMap(), text) to emptyList()
            val end = (1 until lines.size).firstOrNull { lines[it].trimEnd() == "---" }
                ?: return Frontmatter(LinkedHashMap(), text) to listOf(YamlError(1, "Missing the \"---\" that closes the frontmatter"))
            val (props, errors) = parseYaml(lines.subList(1, end), firstLine = 2)
            val body = lines.drop(end + 1).joinToString("\n").removePrefix("\n")
            return Frontmatter(props, body) to errors
        }

        fun parseYaml(lines: List<String>, firstLine: Int = 1): Pair<LinkedHashMap<String, YamlValue>, List<YamlError>> {
            val props = LinkedHashMap<String, YamlValue>()
            val errors = ArrayList<YamlError>()
            var listKey: String? = null
            var listItems: MutableList<String>? = null

            fun closeList() {
                val key = listKey ?: return
                props[key] = if (listItems.isNullOrEmpty()) YamlValue.Empty else YamlValue.Items(listItems!!.toList())
                listKey = null
                listItems = null
            }

            lines.forEachIndexed { index, raw ->
                val lineNo = firstLine + index
                val line = stripComment(raw).trimEnd()
                if (line.isBlank()) return@forEachIndexed

                val trimmed = line.trimStart()
                if (trimmed.startsWith("- ") || trimmed == "-") {
                    if (listKey == null) {
                        errors += YamlError(lineNo, "List item without a property")
                    } else {
                        listItems!!.add(unquote(trimmed.removePrefix("-").trim()))
                    }
                    return@forEachIndexed
                }
                if (line.first().isWhitespace()) {
                    errors += YamlError(lineNo, "Nested properties aren't supported")
                    return@forEachIndexed
                }

                closeList()
                val colon = findKeyColon(line)
                if (colon <= 0) {
                    errors += YamlError(lineNo, "Expected \"property: value\"")
                    return@forEachIndexed
                }
                val key = unquote(line.substring(0, colon).trim())
                val value = line.substring(colon + 1).trim()
                if (key in props) errors += YamlError(lineNo, "Property \"$key\" is repeated")
                when {
                    value.isEmpty() -> {
                        listKey = key
                        listItems = ArrayList()
                    }
                    value.startsWith("[") -> {
                        if (!value.endsWith("]")) {
                            errors += YamlError(lineNo, "Unclosed list in \"$key\"")
                        } else {
                            val inner = value.substring(1, value.length - 1)
                            props[key] = YamlValue.Items(splitInline(inner).map(::unquote).filter { it.isNotEmpty() })
                        }
                    }
                    value.startsWith("{") -> errors += YamlError(lineNo, "Objects aren't supported in \"$key\"")
                    value == "|" || value == ">" || value.startsWith("|") || value.startsWith(">") ->
                        errors += YamlError(lineNo, "Multi-line text goes in the body of the note, not in \"$key\"")
                    else -> props[key] = scalar(value)
                }
            }
            closeList()
            return props to errors
        }

        /** Writes properties in the format Obsidian produces and understands. */
        fun emit(properties: Map<String, YamlValue>): String = buildString {
            for ((key, value) in properties) {
                append(key).append(':')
                when (value) {
                    is YamlValue.Text -> append(' ').append(quoteIfNeeded(value.value))
                    is YamlValue.Bool -> append(' ').append(value.value)
                    is YamlValue.Number -> append(' ').append(formatNumber(value.value))
                    is YamlValue.Items -> value.values.forEach { append("\n  - ").append(quoteIfNeeded(it)) }
                    YamlValue.Empty -> Unit
                }
                append('\n')
            }
        }

        fun note(properties: Map<String, YamlValue>, body: String): String =
            "---\n" + emit(properties) + "---\n" + body

        private fun scalar(value: String): YamlValue {
            if (value.startsWith('"') || value.startsWith('\'')) return YamlValue.Text(unquote(value))
            return when (value.lowercase()) {
                "true", "yes" -> YamlValue.Bool(true)
                "false", "no" -> YamlValue.Bool(false)
                "null", "~" -> YamlValue.Empty
                else -> value.toDoubleOrNull()?.takeIf { NUMBER.matches(value) }?.let(YamlValue::Number) ?: YamlValue.Text(value)
            }
        }

        private val NUMBER = Regex("-?\\d+(\\.\\d+)?")

        private fun formatNumber(v: Double): String = if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()

        /** The ":" that separates key and value (not one inside quotes or in a time like 07:30). */
        private fun findKeyColon(line: String): Int {
            var quote: Char? = null
            for (i in line.indices) {
                val c = line[i]
                if (quote != null) {
                    if (c == quote) quote = null
                } else if (c == '"' || c == '\'') {
                    quote = c
                } else if (c == ':' && (i + 1 == line.length || line[i + 1] == ' ')) {
                    return i
                }
            }
            return -1
        }

        private fun stripComment(line: String): String {
            var quote: Char? = null
            for (i in line.indices) {
                val c = line[i]
                if (quote != null) {
                    if (c == quote) quote = null
                } else if (c == '"' || c == '\'') {
                    quote = c
                } else if (c == '#' && (i == 0 || line[i - 1].isWhitespace())) {
                    return line.substring(0, i)
                }
            }
            return line
        }

        private fun splitInline(inner: String): List<String> {
            val parts = ArrayList<String>()
            val current = StringBuilder()
            var quote: Char? = null
            var depth = 0
            for (c in inner) {
                when {
                    quote != null -> {
                        current.append(c)
                        if (c == quote) quote = null
                    }
                    c == '"' || c == '\'' -> {
                        quote = c
                        current.append(c)
                    }
                    c == '[' -> { depth++; current.append(c) }
                    c == ']' -> { depth--; current.append(c) }
                    c == ',' && depth == 0 -> {
                        parts += current.toString().trim()
                        current.clear()
                    }
                    else -> current.append(c)
                }
            }
            if (current.isNotBlank()) parts += current.toString().trim()
            return parts
        }

        private fun unquote(s: String): String {
            val t = s.trim()
            if (t.length >= 2 && ((t.first() == '"' && t.last() == '"') || (t.first() == '\'' && t.last() == '\''))) {
                val inner = t.substring(1, t.length - 1)
                return if (t.first() == '"') inner.replace("\\\"", "\"").replace("\\\\", "\\") else inner.replace("''", "'")
            }
            return t
        }

        /** [[…]] links, times and text with special characters are quoted. */
        private fun quoteIfNeeded(s: String): String {
            val needs = s.isEmpty() || s.startsWith('[') || s.startsWith('{') || s.startsWith('#') || s.startsWith('-') ||
                s.contains(": ") || s.contains(" #") || s.endsWith(':') || s.first().isWhitespace() || s.last().isWhitespace() ||
                scalar(s) !is YamlValue.Text || s.contains(':') || s.startsWith('"') || s.startsWith('\'')
            return if (needs) "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" else s
        }
    }
}
