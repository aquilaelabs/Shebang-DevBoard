package dev.shebang.devboard.dict

import kotlin.math.abs
import kotlin.math.min

data class Suggestion(val word: String, val score: Double, val isCorrection: Boolean)

/**
 * Ranks suggestions for a partially typed word: prefix completions first, then edit-distance corrections.
 * Pure function of the dictionary; safe to call from a background thread.
 */
class Suggester(private val dict: Dictionary) {

    /**
     * @param typed the composing text (any case).
     * @param limit how many results to return.
     */
    fun suggest(typed: String, limit: Int = 3): List<Suggestion> {
        if (typed.isEmpty()) return emptyList()
        val lower = typed.lowercase()
        val out = ArrayList<Suggestion>(limit * 4)

        // 1. Prefix completions, weighted by frequency and mildly by closeness in length.
        val range = dict.prefixRange(lower)
        if (!range.isEmpty()) {
            val take = min(range.last - range.first + 1, 400)
            val scored = ArrayList<Suggestion>(take)
            for (i in range.first until range.first + take) {
                val extra = dict.lower[i].length - lower.length
                val exact = extra == 0
                val score = dict.weight(i) * (if (exact) 3.0 else 1.0 / (1.0 + 0.35 * extra))
                scored.add(Suggestion(matchCase(typed, dict.words[i]), score, false))
            }
            scored.sortByDescending { it.score }
            out.addAll(scored.take(limit))
        }

        // 2. Corrections: words within a small edit distance whose length is close to the typed length.
        if (lower.length >= 2 && out.none { it.word.equals(typed, ignoreCase = true) }) {
            val maxDist = if (lower.length <= 4) 1 else 2
            val corrections = ArrayList<Suggestion>()
            val words = dict.lower
            for (i in words.indices) {
                val w = words[i]
                if (abs(w.length - lower.length) > maxDist) continue
                if (w.startsWith(lower)) continue // already a completion
                val d = EditDistance.bounded(lower, w, maxDist)
                if (d < 0) continue
                // A wrong first letter is a stronger signal of a different word.
                val firstPenalty = if (w[0] != lower[0]) 0.5 else 1.0
                val score = dict.weight(i) * firstPenalty / (1.0 + 1.5 * d)
                corrections.add(Suggestion(matchCase(typed, dict.words[i]), score, true))
            }
            corrections.sortByDescending { it.score }
            for (c in corrections) {
                if (out.size >= limit * 2) break
                if (out.none { it.word.equals(c.word, ignoreCase = true) }) out.add(c)
            }
        }

        // The best prefix completion always leads: what was typed so far is trusted over a correction that
        // changes it. Corrections and the remaining completions then compete on score.
        val lead = out.firstOrNull { !it.isCorrection }
        val rest = out.filter { it !== lead }.sortedByDescending { it.score }
        return (listOfNotNull(lead) + rest).take(limit)
    }

    /** The single best correction for autocorrect-on-space, or null when the typed word is fine. */
    fun autocorrect(typed: String): String? {
        if (typed.length < 2 || dict.contains(typed)) return null
        val best = suggest(typed, 1).firstOrNull() ?: return null
        if (!best.isCorrection) return null
        // Only correct confidently: a common word one edit away.
        val idx = dict.indexOf(best.word)
        if (idx < 0 || dict.tiers[idx] > 35) return null
        val d = EditDistance.bounded(typed.lowercase(), dict.lower[idx], 2)
        return if (d in 0..1) best.word else null
    }

    companion object {
        /** Copies the user's casing onto a suggestion: "Hel" -> "Hello", "HEL" -> "HELLO", "hel" -> dictionary casing. */
        fun matchCase(typed: String, word: String): String {
            if (typed.isEmpty()) return word
            val allUpper = typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() }
            return when {
                allUpper -> word.uppercase()
                typed[0].isUpperCase() -> word.replaceFirstChar { it.uppercaseChar() }
                else -> word
            }
        }
    }
}

/** Damerau-Levenshtein (optimal string alignment) with an early-out bound. */
object EditDistance {
    /** Distance between [a] and [b], or -1 when it exceeds [max]. */
    fun bounded(a: String, b: String, max: Int): Int {
        if (abs(a.length - b.length) > max) return -1
        if (a == b) return 0
        val n = a.length
        val m = b.length
        var prevPrev: IntArray? = null
        var prev = IntArray(m + 1) { it }
        var cur = IntArray(m + 1)
        for (i in 1..n) {
            cur[0] = i
            var rowMin = cur[0]
            val ai = a[i - 1]
            for (j in 1..m) {
                val cost = if (ai == b[j - 1]) 0 else 1
                var v = min(min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost)
                if (i > 1 && j > 1 && ai == b[j - 2] && a[i - 2] == b[j - 1]) {
                    v = min(v, prevPrev!![j - 2] + 1)
                }
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > max) return -1
            val t = prevPrev ?: IntArray(m + 1)
            prevPrev = prev
            prev = cur
            cur = t
        }
        val d = prev[m]
        return if (d > max) -1 else d
    }
}
