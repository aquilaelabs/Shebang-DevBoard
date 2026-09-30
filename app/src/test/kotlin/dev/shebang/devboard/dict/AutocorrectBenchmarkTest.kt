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
        Suggester(dictionary, null, FloatArray(dictionary.size) { kotlin.math.exp(-lm.unigramCost(it).toDouble()).toFloat() })
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
        val words = GlideBenchmarkTest.heldOut.take(1500).flatten().filter { it.length >= 3 && it.all { c -> c.isLetter() } && dictionary.contains(it) }
        for (k in listOf(0.0, 1.0, 2.0, 3.0, 4.0, 6.0, 8.0)) {
            Suggester.SLIP_WEIGHT = k
            val rnd = Random(4)
            var n = 0
            var fixed = 0
            for (w in words) {
                val t = slip(w, rnd) ?: continue
                n++
                if (suggester.autocorrectFrom(t, suggester.suggest(t, Suggester.AUTOCORRECT_CANDIDATES)).equals(w, ignoreCase = true)) fixed++
            }
            println("AUTOCORRECT SWEEP slip weight $k: fixed ${GlideBenchmarkTest.pct(fixed, n)}")
        }
        Suggester.SLIP_WEIGHT = 6.0
    }

    @Test
    fun autocorrectOnSlips() {
        val words = GlideBenchmarkTest.heldOut.take(1500).flatten().filter { it.length >= 3 && it.all { c -> c.isLetter() } && dictionary.contains(it) }
        val rnd = Random(4)
        var n = 0
        var fixed = 0
        var wrong = 0
        var untouched = 0
        val misses = HashMap<String, Int>()
        for (w in words) {
            val t = slip(w, rnd) ?: continue
            n++
            val fix = suggester.autocorrectFrom(t, suggester.suggest(t, Suggester.AUTOCORRECT_CANDIDATES))
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
        val right = words.distinct()
        for (w in right) if (suggester.autocorrectFrom(w, suggester.suggest(w, Suggester.AUTOCORRECT_CANDIDATES)) != null) changedRight++
        val pct = GlideBenchmarkTest::pct
        println("AUTOCORRECT $n slips: fixed ${pct(fixed, n)}, changed to another word ${pct(wrong, n)}, left alone ${pct(untouched, n)}")
        println("AUTOCORRECT ${right.size} words typed right: changed ${pct(changedRight, right.size)}")
        for ((k, v) in misses.entries.sortedByDescending { it.value }.take(25)) println("AUTOCORRECT   $v  $k")
        assertTrue("words typed right must essentially never change", changedRight * 1000 <= right.size * 5)
    }
}
