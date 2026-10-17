package dev.escriba.shared.voices

import dev.escriba.shared.transcription.TranscriptLine
import kotlinx.serialization.Serializable
import kotlin.math.sqrt

/** A speaker's turn according to diarization, in real time. */
@Serializable
data class SpeakerTurn(val wallStartMs: Long, val wallEndMs: Long, val cluster: Int)

/** A voice enrolled by the user ("Me", "Ana López"…): it only lives on the phone. */
data class EnrolledVoice(val name: String, val embedding: FloatArray)

/** Cleaned-up diarization result: speakers numbered 0, 1, 2… in order of appearance. */
data class Speakers(val turns: List<SpeakerTurn>, val embeddings: Map<Int, FloatArray>, val speechMs: Map<Int, Long>)

object VoiceMath {

    /** Label of a speaker that wasn't recognized: "Speaker 1", "Speaker 2"… */
    const val UNKNOWN_PREFIX = "Speaker"

    /** The usual name for the user's own voice. */
    const val ME = "Me"

    /**
     * Diarization clustering tends to split one person into several speakers (changes of tone,
     * noise, short sentences). This fixes it in two steps:
     *  1. speakers whose voiceprints are more similar than [minSimilarity] are merged;
     *  2. those who speak for less than [minSpeechMs] don't count as speakers: with so little
     *     speech their voiceprint is unreliable and they're almost always pieces of someone
     *     already there. They join the most similar main speaker or, without a voiceprint, the
     *     speaker of the nearest turn.
     */
    fun merge(
        turns: List<SpeakerTurn>,
        embeddings: Map<Int, FloatArray>,
        speechMs: Map<Int, Long>,
        minSimilarity: Float = 0.6f,
        minSpeechMs: Long = 8_000,
    ): Speakers {
        val emb = HashMap(embeddings.mapValues { normalized(it.value) })
        val speech = HashMap(speechMs)
        val target = HashMap<Int, Int>() // speaker → who it was merged into
        fun root(c: Int): Int {
            var r = c
            while (true) r = target[r] ?: return r
        }

        while (true) {
            val ids = emb.keys.sorted()
            var best: Triple<Int, Int, Float>? = null
            for (i in ids.indices) for (j in i + 1 until ids.size) {
                val sim = cosine(emb.getValue(ids[i]), emb.getValue(ids[j]))
                if (sim >= minSimilarity && (best == null || sim > best.third)) best = Triple(ids[i], ids[j], sim)
            }
            val (a, b, _) = best ?: break
            // The one who speaks more stays; its voiceprint becomes the average weighted by speech time.
            val (keep, drop) = if ((speech[a] ?: 0) >= (speech[b] ?: 0)) a to b else b to a
            val wk = (speech[keep] ?: 1).toFloat().coerceAtLeast(1f)
            val wd = (speech[drop] ?: 1).toFloat().coerceAtLeast(1f)
            val ek = emb.getValue(keep)
            val ed = emb.getValue(drop)
            emb[keep] = normalized(FloatArray(ek.size) { (ek[it] * wk + ed[it] * wd) / (wk + wd) })
            emb.remove(drop)
            speech[keep] = (speech[keep] ?: 0) + (speech.remove(drop) ?: 0)
            target[drop] = keep
        }

        var merged = turns.map { it.copy(cluster = root(it.cluster)) }
        val main = speech.filter { (c, ms) -> ms >= minSpeechMs && c in emb }.keys
        if (main.isNotEmpty()) {
            val anchors = merged.filter { it.cluster in main }
            val minorTo = (speech.keys - main).associateWith { c ->
                emb[c]?.let { e -> main.maxBy { m -> cosine(e, emb.getValue(m)) } }
            }
            merged = merged.map { t ->
                when {
                    t.cluster in main -> t
                    else -> t.copy(cluster = minorTo[t.cluster] ?: anchors.minBy { a -> gap(t, a) }.cluster)
                }
            }
        }

        // Final numbering in order of appearance.
        val order = merged.sortedBy { it.wallStartMs }.map { it.cluster }.distinct()
        val renumber = order.withIndex().associate { (i, c) -> c to i }
        val finalTurns = merged.map { it.copy(cluster = renumber.getValue(it.cluster)) }
        val finalSpeech = finalTurns.groupBy { it.cluster }.mapValues { (_, ts) -> ts.sumOf { it.wallEndMs - it.wallStartMs } }
        val finalEmb = emb.filterKeys { it in renumber }.mapKeys { renumber.getValue(it.key) }
        return Speakers(finalTurns, finalEmb, finalSpeech)
    }

    private fun gap(a: SpeakerTurn, b: SpeakerTurn): Long =
        maxOf(0, maxOf(a.wallStartMs, b.wallStartMs) - minOf(a.wallEndMs, b.wallEndMs))

    private fun normalized(e: FloatArray): FloatArray {
        val n = sqrt(e.sumOf { (it * it).toDouble() }).toFloat()
        return if (n > 0) FloatArray(e.size) { e[it] / n } else e
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Voiceprints of different sizes: ${a.size} and ${b.size}" }
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0.0 || nb == 0.0) return 0f
        return (dot / (sqrt(na) * sqrt(nb))).toFloat()
    }

    /** Normalized average of several voiceprints (e.g. when enrolling a voice from several sessions). */
    fun average(embeddings: List<FloatArray>): FloatArray {
        require(embeddings.isNotEmpty())
        val out = FloatArray(embeddings.first().size)
        for (e in embeddings) {
            val n = sqrt(e.sumOf { (it * it).toDouble() }).toFloat().takeIf { it > 0 } ?: 1f
            for (i in out.indices) out[i] += e[i] / n
        }
        for (i in out.indices) out[i] /= embeddings.size
        return out
    }

    /**
     * Gives every sentence the speaker it overlaps most in time. A sentence with no overlap (e.g.
     * in a gap between turns) takes the speaker of the nearest turn.
     */
    fun assign(lines: List<TranscriptLine>, turns: List<SpeakerTurn>, label: (Int) -> String): List<TranscriptLine> {
        if (turns.isEmpty()) return lines
        return lines.map { line ->
            val overlaps = turns.groupBy { it.cluster }.mapValues { (_, ts) ->
                ts.sumOf { t -> (minOf(line.wallEndMs, t.wallEndMs) - maxOf(line.wallStartMs, t.wallStartMs)).coerceAtLeast(0) }
            }
            val best = overlaps.maxByOrNull { it.value }
            val cluster = if (best != null && best.value > 0) best.key else turns.minBy { t ->
                val mid = (line.wallStartMs + line.wallEndMs) / 2
                minOf(kotlin.math.abs(mid - t.wallStartMs), kotlin.math.abs(mid - t.wallEndMs))
            }.cluster
            line.copy(speaker = label(cluster))
        }
    }

    /**
     * Recognizes a session's speakers by comparing their voiceprints with the enrolled voices.
     * Each enrolled voice goes to at most one speaker (the most similar), and only if the
     * similarity is over [threshold]. Whatever isn't recognized stays as "Speaker N".
     */
    fun identify(clusters: Map<Int, FloatArray>, enrolled: List<EnrolledVoice>, threshold: Float = 0.5f): Map<Int, String> {
        val pairs = clusters.flatMap { (c, e) -> enrolled.map { v -> Triple(c, v.name, cosine(e, v.embedding)) } }
            .filter { it.third >= threshold }
            .sortedByDescending { it.third }
        val result = HashMap<Int, String>()
        val usedNames = HashSet<String>()
        for ((cluster, name, _) in pairs) {
            if (cluster in result || name in usedNames) continue
            result[cluster] = name
            usedNames += name
        }
        // Unrecognized speakers are numbered in order of appearance, starting at 1.
        var n = 1
        for (c in clusters.keys.sorted()) if (c !in result) result[c] = "$UNKNOWN_PREFIX ${n++}"
        return result
    }
}
