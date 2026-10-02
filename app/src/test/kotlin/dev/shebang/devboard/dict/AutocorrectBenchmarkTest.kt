package dev.shebang.devboard.dict

import dev.shebang.devboard.glide.GlideBenchmarkTest
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Autocorrect on real words from the held-out sentences: each word of three letters or more is typed with one
 * slip of the finger (a neighbouring key instead, two letters swapped, a letter dropped, or one doubled), and
 * autocorrect gets the suggestions the strip would have for the slip. Also counts how often a word typed right
 * is changed. Prints the rates and the most common failures.
 */
class AutocorrectBenchmarkTest {
    private val dictionary get() = GlideBenchmarkTest.dictionary
    private val suggester by lazy {
        val lm = GlideBenchmarkTest.lm
        Suggester(dictionary, null, FloatArray(dictionary.size) { kotlin.math.exp(-lm.unigramCost(it).toDouble()).toFloat() }, lm)
    }

    /** Each held-out word with the two words before it. */
    private class InContext(val word: String, val context: Suggester.Context)

    private fun ctxId(w: String?): Int = when (w) {
        null -> NgramModel.SENTENCE_START
        else -> dictionary.indexOfLower(w).let { if (it < 0) NgramModel.UNKNOWN else GlideBenchmarkTest.lm.contextOf(it) }
    }

    private val inContext: List<InContext> by lazy {
        GlideBenchmarkTest.heldOut.take(1500).flatMap { s ->
            s.mapIndexedNotNull { i, w ->
                if (w.length >= 3 && w.all { it.isLetter() } && dictionary.contains(w)) {
                    InContext(w, Suggester.Context(if (i == 0) NgramModel.UNKNOWN else ctxId(s.getOrNull(i - 2)), ctxId(s.getOrNull(i - 1))))
                } else null
            }
        }
    }

    /** Autocorrect on the same slips with the words before them, at several context weights. */
    @Test
    fun contextWeightSweep() {
        org.junit.Assume.assumeTrue(System.getenv("AUTOCORRECT_SWEEP") != null)
        for (k in listOf(0f, 0.5f, 0.75f, 1f)) {
            Suggester.CONTEXT_WEIGHT = k
            val rnd = Random(4)
            var n = 0
            var fixed = 0
            var wrong = 0
            for (w in inContext) {
                val t = slip(w.word, rnd) ?: continue
                n++
                val fix = suggester.autocorrectFrom(t, suggester.suggest(t, Suggester.AUTOCORRECT_CANDIDATES, context = w.context), context = w.context)
                if (fix.equals(w.word, ignoreCase = true)) fixed++ else if (fix != null) wrong++
            }
            println("AUTOCORRECT SWEEP context weight $k: fixed ${GlideBenchmarkTest.pct(fixed, n)}, another word ${GlideBenchmarkTest.pct(wrong, n)}")
        }
        Suggester.CONTEXT_WEIGHT = 0.75f
    }

    private val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    private fun neighbours(c: Char): List<Char> {
        val r = rows.indexOfFirst { c in it }
        if (r < 0) return emptyList()
        val i = rows[r].indexOf(c)
        return listOfNotNull(rows[r].getOrNull(i - 1), rows[r].getOrNull(i + 1))
    }

    private fun slip(w: String, rnd: Random): String? {
        repeat(10) {
            val i = rnd.nextInt(w.length)
            val t = when (rnd.nextInt(4)) {
                0 -> neighbours(w[i]).takeIf { it.isNotEmpty() }?.let { w.substring(0, i) + it[rnd.nextInt(it.size)] + w.substring(i + 1) }
                1 -> if (i + 1 < w.length && w[i] != w[i + 1]) w.substring(0, i) + w[i + 1] + w[i] + w.substring(i + 2) else null
                2 -> w.removeRange(i, i + 1)
                else -> w.substring(0, i + 1) + w[i] + w.substring(i + 1)
            }
            // A slip that happens to be another word is not a typo autocorrect can know about.
            if (t != null && t != w && t.length >= 2 && !dictionary.contains(t)) return t
        }
        return null
    }

    @Test
    fun classicMisspellings() {
        val list = listOf("helo", "teh", "wiht", "becuase", "thier", "recieve", "adress", "tommorow", "definately", "seperate",
            "dont", "cant", "youre", "wouldnt", "im", "freind", "goign", "taht", "jsut", "knwo", "whta", "abotu", "becasue", "realy", "ot")
        println("AUTOCORRECT classic: " + list.joinToString("  ") { t -> "$t->${suggester.autocorrectFrom(t, suggester.suggest(t, Suggester.AUTOCORRECT_CANDIDATES)) ?: "(left)"}" })
    }

    @Test
    fun slipWeightSweep() {
        org.junit.Assume.assumeTrue(System.getenv("AUTOCORRECT_SWEEP") != null)
        val savedSlipWeight = Suggester.SLIP_WEIGHT
        for (k in listOf(3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 10.0)) {
            Suggester.SLIP_WEIGHT = k
            val rnd = Random(4)
            var n = 0
            var fixed = 0
            var wrong = 0
            for (w in inContext) {
                val t = slip(w.word, rnd) ?: continue
                n++
                val fix = suggester.autocorrectFrom(t, suggester.suggest(t, Suggester.AUTOCORRECT_CANDIDATES, context = w.context), context = w.context)
                if (fix.equals(w.word, ignoreCase = true)) fixed++ else if (fix != null) wrong++
            }
            var changed = 0
            val right = inContext.distinctBy { it.word }
            for (w in right) if (suggester.autocorrectFrom(w.word, suggester.suggest(w.word, Suggester.AUTOCORRECT_CANDIDATES, context = w.context), context = w.context) != null) changed++
            println("AUTOCORRECT SWEEP slip weight $k: fixed ${GlideBenchmarkTest.pct(fixed, n)}, another word ${GlideBenchmarkTest.pct(wrong, n)}, right words changed ${GlideBenchmarkTest.pct(changed, right.size)}")
        }
        Suggester.SLIP_WEIGHT = savedSlipWeight
    }

    @Test
    fun autocorrectOnSlips() {
        // Each word with the two words before it, as the keyboard sees them.
        val words = inContext
        val rnd = Random(4)
        var n = 0
        var fixed = 0
        var wrong = 0
        var untouched = 0
        val misses = HashMap<String, Int>()
        for (wc in words) {
            val w = wc.word
            val t = slip(w, rnd) ?: continue
            n++
            val fix = suggester.autocorrectFrom(t, suggester.suggest(t, Suggester.AUTOCORRECT_CANDIDATES, context = wc.context), context = wc.context)
            when {
                fix == null -> {
                    untouched++
                    misses["$t (for $w) left alone"] = (misses["$t (for $w) left alone"] ?: 0) + 1
                }
                fix.equals(w, ignoreCase = true) -> fixed++
                else -> {
                    wrong++
                    misses["$t -> $fix (for $w)"] = (misses["$t -> $fix (for $w)"] ?: 0) + 1
                }
            }
        }
        var changedRight = 0
        val right = words.distinctBy { it.word }
        for (wc in right) {
            val fix = suggester.autocorrectFrom(wc.word, suggester.suggest(wc.word, Suggester.AUTOCORRECT_CANDIDATES, context = wc.context), context = wc.context)
            // A name given its capitals ("paris" -> "Paris") is not a change of word.
            if (fix != null && !fix.equals(wc.word, ignoreCase = true)) changedRight++
        }
        val pct = GlideBenchmarkTest::pct
        println("AUTOCORRECT $n slips: fixed ${pct(fixed, n)}, changed to another word ${pct(wrong, n)}, left alone ${pct(untouched, n)}")
        println("AUTOCORRECT ${right.size} words typed right: changed ${pct(changedRight, right.size)}")
        for ((k, v) in misses.entries.sortedByDescending { it.value }.take(25)) println("AUTOCORRECT   $v  $k")
        assertTrue("words typed right must essentially never change", changedRight * 1000 <= right.size * 5)
    }
}
