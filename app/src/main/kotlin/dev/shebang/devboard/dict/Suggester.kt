package dev.shebang.devboard.dict

import kotlin.math.abs
import kotlin.math.min

data class Suggestion(val word: String, val score: Double, val isCorrection: Boolean)

/**
 * Ranks suggestions for a partially typed word: prefix completions first, then edit-distance corrections.
 * Pure function of the dictionary; safe to call from a background thread.
 */
class Suggester(
    private val dict: Dictionary,
    /** How often the user has used each dictionary word (learned locally); null without personal data. */
    private val personalCounts: IntArray? = null,
    /**
     * How common each word is: the language model's unigram probability (sentence counts, the word list's
     * frequency tiers where counts are missing, and the user's own words). Null ranks by tiers alone.
     */
    private val frequency: FloatArray? = null,
    /** The language model, for ranking by the words before ([Context]); null ranks by [frequency] alone. */
    private val lm: NgramModel? = null,
) {
    /**
     * The two words before the word being typed, as [NgramModel] context ids ([NgramModel.SENTENCE_START],
     * [NgramModel.UNKNOWN] or [NgramModel.contextOf]).
     */
    class Context(val context2: Int, val context1: Int)

    /**
     * How likely word [i] is here, raised for words the user uses: 1 use x1.35, 10 uses x2.2. With [context],
     * the three-word model (mixed with the user's own word pairs) counts [CONTEXT_WEIGHT] against how common
     * the word is overall.
     */
    private fun weight(i: Int, context: Context? = null): Double {
        val model = lm
        val base = if (context != null && model != null && context.context1 != NgramModel.UNKNOWN) {
            val cost = CONTEXT_WEIGHT * model.cost3(i, context.context2, context.context1) + (1 - CONTEXT_WEIGHT) * model.unigramCost(i)
            kotlin.math.exp(-cost.toDouble())
        } else {
            frequency?.getOrNull(i)?.toDouble() ?: dict.weight(i)
        }
        val pc = personalCounts?.getOrNull(i) ?: 0
        return if (pc > 0) base * (1.0 + 0.5 * kotlin.math.ln(1.0 + pc)) else base
    }


    /**
     * @param typed the composing text (any case).
     * @param limit how many results to return.
     * @param taps where the typed letters were tapped, when known: corrections then rank by how likely the
     *   taps were meant for them ([SlipCost] with taps) rather than by plain edit distance.
     * @param context the two words before, when known: candidates then rank by how likely they are there.
     */
    fun suggest(typed: String, limit: Int = 3, taps: SlipCost.Taps? = null, context: Context? = null): List<Suggestion> {
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
                val score = weight(i, context) * (if (exact) 3.0 else 1.0 / (1.0 + 0.35 * extra))
                scored.add(Suggestion(matchCase(typed, dict.words[i]), score, false))
            }
            scored.sortByDescending { it.score }
            out.addAll(scored.take(limit))
        }

        // 2. Corrections: words within a small edit distance whose length is close to the typed length. Also when
        // what was typed is only a name in lowercase ("thar" for "Thar"): it is as likely a slip ("that").
        val lowercaseName = typed == lower && dict.indexOf(lower) >= 0 && !dict.hasLowercaseSpelling(lower)
        if (lower.length >= 2 && (lowercaseName || out.none { it.word.equals(typed, ignoreCase = true) })) {
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
                val score = if (taps != null) {
                    weight(i, context) * kotlin.math.exp(-SLIP_WEIGHT * SlipCost.cost(lower, w, taps))
                } else {
                    weight(i, context) * firstPenalty / (1.0 + 1.5 * d)
                }
                corrections.add(Suggestion(matchCase(typed, dict.words[i]), score, true))
            }
            corrections.sortByDescending { it.score }
            for (c in corrections) {
                if (out.size >= limit * 2) break
                if (out.none { it.word.equals(c.word, ignoreCase = true) }) out.add(c)
            }
        }

        // 3. The word with an apostrophe put in ("cant" -> "can't"): people skip it, and the typed letters may
        // themselves be a rare word, which keeps the corrections above from running.
        if (lower.length >= 2 && '\'' !in lower) {
            for (i in 1 until lower.length) {
                val idx = dict.indexOf(lower.substring(0, i) + "'" + lower.substring(i))
                if (idx < 0) continue
                val word = matchCase(typed, dict.words[idx])
                if (out.none { it.word.equals(word, ignoreCase = true) }) out.add(Suggestion(word, weight(idx, context) / 1.3, true))
            }
        }

        // The best prefix completion always leads: what was typed so far is trusted over a correction that
        // changes it. Corrections and the remaining completions then compete on score.
        val lead = out.firstOrNull { !it.isCorrection }
        val rest = out.filter { it !== lead }.sortedByDescending { it.score }
        return (listOfNotNull(lead) + rest).take(limit)
    }

    /**
     * A word typed where a far more likely contraction was meant, without its apostrophe ("cant" for "can't",
     * "its" for "it's" after "I think"), weighed with the words before when [context] is given; null for
     * everything else the dictionary knows ("its" after "the dog wagged", "well" at the start).
     */
    private fun contractionFor(typed: String, known: Int, candidates: List<Suggestion>, context: Context? = null): String? {
        if ((personalCounts?.getOrNull(known) ?: 0) > 0) return null
        val lower = typed.lowercase()
        for (c in candidates) {
            val idx = dict.indexOf(c.word)
            if (idx < 0 || !dict.lower[idx].contains('\'') || dict.lower[idx].replace("'", "") != lower) continue
            // Only when the contraction is far more common than the word typed: "its", "were", "well" and
            // "ill" are words people mean.
            if (weight(idx, context) >= CONTRACTION_RATIO * weight(known, context)) return matchCase(typed, c.word)
        }
        return null
    }

    /** The single best correction for autocorrect-on-space, or null when the typed word is fine. */
    fun autocorrect(typed: String, taps: SlipCost.Taps? = null, context: Context? = null): String? =
        autocorrectFrom(typed, suggest(typed, AUTOCORRECT_CANDIDATES, taps, context), taps, context)

    /** Whether the dictionary has [word] as it is spelled. */
    fun knows(word: String): Boolean = dict.indexOf(word) >= 0

    /**
     * Autocorrect using suggestions already computed for [typed] (the strip's candidates), so the space key
     * never scans the dictionary on the main thread. Only cheap lookups happen here.
     *
     * A word the dictionary knows is left alone. Otherwise the most common candidate within one slip of it
     * (two for words of six letters or more) wins: a neighbouring key, two letters swapped, one dropped or
     * one doubled, completions included ("te" for "the"). Only common words (tier 35 or better, or words the
     * user uses) are corrected to. A two-letter word is only corrected by adding a letter it dropped, so
     * abbreviations such as "js" or "ui" are not turned into other two-letter words.
     */
    fun autocorrectFrom(
        typed: String, candidates: List<Suggestion>, taps: SlipCost.Taps? = null, context: Context? = null,
        /** For measuring: decide as if [typed] were not in the dictionary (a word typed on purpose that it lacks). */
        asUnknown: Boolean = false,
    ): String? {
        if (typed.length < 2) return null
        val known = if (asUnknown) -1 else dict.indexOf(typed)
        val lower = typed.lowercase()
        // A name or abbreviation the dictionary only has capitalised, typed in lowercase, is not that word yet:
        // a slip ("thar" for "that") or the name itself without its capitals ("google" for "Google").
        val recase = known >= 0 && typed == lower && !dict.hasLowercaseSpelling(lower)
        if (known >= 0 && !recase) return contractionFor(typed, known, candidates, context)
        val maxD = if (lower.length >= 6 || (taps != null && lower.length >= TAP_TWO_SLIPS_FROM)) 2 else 1
        var best: String? = null
        var bestScore = 0.0
        var bestSame = false
        for (c in candidates) {
            val idx = dict.indexOf(c.word)
            if (idx < 0) continue
            val w = dict.lower[idx]
            if (asUnknown && w == lower) continue
            // The name itself, given its capitals: no slip, whatever its tier. The same letters with an apostrophe
            // ("mcdonalds" for "McDonald's") count as the word too.
            val sameWord = (recase && w == lower) || (w != lower && w.replace("'", "") == lower)
            val used = (personalCounts?.getOrNull(idx) ?: 0) > 0
            if (dict.bestTier(idx) > 35 && !used && !sameWord) continue
            if (!sameWord && lower.length == 2 && !(w.length == 3 && EditDistance.bounded(lower, w, 1) == 1)) continue
            val d = if (sameWord) 0 else EditDistance.bounded(lower, w, maxD)
            if (d < 1 && !sameWord) continue
            // How likely a finger makes this slip, against how common the word is.
            val firstPenalty = if (w[0] != lower[0]) 0.35 else 1.0
            val score = weight(idx, context) * firstPenalty * kotlin.math.exp(-SLIP_WEIGHT * SlipCost.cost(lower, w, taps)) *
                (if (sameWord) RECASE_WEIGHT else 1.0)
            if (score > bestScore) {
                bestScore = score
                best = c.word
                bestSame = sameWord
            }
        }
        // A word the dictionary lacks may be meant as typed (a name, a term, a handle): a correction must be
        // likely enough, given where the taps landed, to beat keeping it. Giving a name its capitals is not a
        // correction.
        if (best != null && !bestSame && bestScore < KEEP_SCORE) return null
        return best?.let { matchCase(typed, it) }
    }

    companion object {
        /** How many suggestions are computed while typing: the strip shows three, autocorrect weighs them all. */
        const val AUTOCORRECT_CANDIDATES = 6
        /**
         * How much likelier a contraction must be than the word typed without its apostrophe, given the words
         * before (ContractionBenchmarkTest: 20 gets 89.2% of uses as meant; 50 without the words before, 79.6%).
         */
        var CONTRACTION_RATIO = 20.0
        /** How strongly an unlikely slip counts against a common word (per unit of [SlipCost]). */
        var SLIP_WEIGHT = 8.0
        /**
         * How a name typed in lowercase counts as itself ("google" -> "Google") against the words it is a slip
         * away from: a lowercase name is itself a little unlikely, so a common word a slip away can win.
         */
        var RECASE_WEIGHT = 1.0
        /**
         * How likely a correction of a word the dictionary lacks must be (its score: how common the word is
         * after the words before, times how likely the taps made the slip) before it beats keeping the word as
         * typed. Chosen on TSI's taps (TapBenchmarkTest.keepingWordsMeantAsTyped).
         */
        var KEEP_SCORE = 2e-8
        /** How much the words before count, against how common a word is overall (0..1). */
        var CONTEXT_WEIGHT = 0.75f
        /** With tap positions, words from this length may be two slips away (else from six letters). */
        private const val TAP_TWO_SLIPS_FROM = 4

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

/**
 * How likely a typed word is a slip of the finger for a word: an edit distance where the slips people make
 * on a QWERTY keyboard cost less. A neighbouring key instead of the right one, two letters swapped, or a
 * letter doubled cost 0.5; a skipped apostrophe ("dont") 0.2; one of a double letter dropped ("adress")
 * 0.4; any other letter dropped 0.8; any other letter extra or wrong 1.0.
 *
 * With [Taps] (where each typed letter's tap came down), a wrong letter costs by how much less likely the
 * tap was meant for the word's letter than for the key it hit, in the same units: a tap on the line
 * between two keys makes either letter nearly free, a tap in the middle of its key makes the other costly.
 */
object SlipCost {
    /** Where the typed letters were tapped: [cost] for letter [i] typed as [typed] but meant as [meant], in nats; null when unknown. */
    fun interface Taps {
        fun cost(i: Int, typed: Char, meant: Char): Double?
    }

    private val rows = arrayOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    private val rowOf = IntArray(26) { -1 }
    private val colOf = FloatArray(26)

    init {
        for ((r, row) in rows.withIndex()) for ((i, c) in row.withIndex()) {
            rowOf[c - 'a'] = r
            colOf[c - 'a'] = i + r * 0.5f
        }
    }

    private fun adjacent(a: Char, b: Char): Boolean {
        val i = a - 'a'
        val j = b - 'a'
        if (i !in 0..25 || j !in 0..25) return false
        val dr = kotlin.math.abs(rowOf[i] - rowOf[j])
        val dc = kotlin.math.abs(colOf[i] - colOf[j])
        return dr <= 1 && dc <= 1.0f
    }

    fun cost(typed: String, word: String, taps: Taps? = null): Double {
        val n = typed.length
        val m = word.length
        val d = Array(n + 1) { DoubleArray(m + 1) }
        for (i in 0..n) d[i][0] = i * 1.0
        for (j in 0..m) d[0][j] = j * 0.8
        for (i in 1..n) for (j in 1..m) {
            val a = typed[i - 1]
            val b = word[j - 1]
            val sub = if (a == b) 0.0 else taps?.cost(i - 1, a, b)?.let { minOf(1.0, it / Suggester.SLIP_WEIGHT) }
                ?: if (adjacent(a, b)) 0.5 else 1.0
            // An extra letter typed: cheap when it doubles the one before or sits next to it.
            val extra = if (i >= 2 && (typed[i - 2] == a || adjacent(typed[i - 2], a))) 0.6 else 1.0
            // A letter the word has and the typing lacks: cheapest for a skipped apostrophe, cheap for one of a
            // double letter.
            val dropped = if (b == '\'') 0.2 else if (j >= 2 && word[j - 2] == b) 0.4 else 0.8
            var v = minOf(d[i - 1][j - 1] + sub, d[i - 1][j] + extra, d[i][j - 1] + dropped)
            if (i > 1 && j > 1 && a == word[j - 2] && typed[i - 2] == b) v = minOf(v, d[i - 2][j - 2] + 0.5)
            d[i][j] = v
        }
        return d[n][m]
    }
}
