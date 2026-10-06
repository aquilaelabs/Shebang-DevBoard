package dev.shebang.devboard.dict

import dev.shebang.devboard.glide.GlideBenchmarkTest
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Two words typed without the space between them ("ataco" for "a taco"): how often autocorrect splits them
 * right, and how often it splits a word typed on purpose that the dictionary lacks.
 *
 * Joined words come from the held-out Tatoeba sentences (never counted in the word model): every two neighbouring
 * words, run together where that is not itself a word, with the words before as context. The words typed on
 * purpose are the packs' names, development words and computer terms, decided against the regular words alone, so
 * they are words the dictionary lacks as a name or a new term would be. Sentences whose id has an even tens digit, and every other
 * pack word are the dev half, for tuning; the rest is the test half, looked at once.
 *
 * JOINED=1 runs it; JOINED_SWEEP=1 tries costs and thresholds on the dev half; JOINED_LIST=1 prints the misses.
 * JOINED_TAPS=1 scores as the keyboard does for typed words: through the slip costs, with tap positions unknown
 * (each letter priced by the keys beside it); without it, corrections are priced by plain edit distance.
 */
class JoinedWordsBenchmarkTest {
    private val taps: SlipCost.Taps? = if (System.getenv("JOINED_TAPS") != null) SlipCost.Taps { _, _, _ -> null } else null

    private class Case(val joined: String, val meant: String, val context: Suggester.Context)

    private fun suggesterFor(dict: Dictionary): Pair<Suggester, NgramModel> {
        val lm = File("src/main/assets/dict/en_ngrams.bin").inputStream().use { NgramModel.load(it, dict) }
        return Suggester(dict, null, FloatArray(dict.size) { kotlin.math.exp(-lm.unigramCost(it).toDouble()).toFloat() }, lm) to lm
    }

    private fun ctxId(dict: Dictionary, lm: NgramModel, w: String?): Int = when (w) {
        null -> NgramModel.SENTENCE_START
        else -> dict.indexOfLower(w).let { if (it < 0) NgramModel.UNKNOWN else lm.contextOf(it) }
    }

    private fun joinedCases(dict: Dictionary, lm: NgramModel, dev: Boolean): List<Case> {
        val out = ArrayList<Case>()
        File("src/test/resources/glide/heldout_sentences.tsv").useLines { lines ->
            for (line in lines) {
                if (line.startsWith("#")) continue
                val (id, text) = line.split('\t', limit = 2).let { it[0].toLong() to it.getOrElse(1) { "" } }
                // Every held-out id ends in 7 (how they were chosen); the tens digit halves them.
                if (((id / 10) % 2 == 0L) != dev) continue
                val w = text.split(' ').filter { it.isNotEmpty() }
                for (i in 0 until w.size - 1) {
                    val a = w[i]
                    val b = w[i + 1]
                    if (!a.all { it.isLetter() } || !b.all { it.isLetter() }) continue
                    val joined = a + b
                    if (joined.length < Suggester.SPLIT_MIN_LENGTH || dict.indexOf(joined) >= 0) continue
                    val ctx = Suggester.Context(if (i == 0) NgramModel.UNKNOWN else ctxId(dict, lm, w.getOrNull(i - 2)), if (i == 0) NgramModel.SENTENCE_START else ctxId(dict, lm, w[i - 1]))
                    out += Case(joined, "$a $b", ctx)
                }
            }
        }
        return out
    }

    /** Pack words typed on purpose, unknown to the regular words: lowercase letters, three or more. */
    private fun meantWords(regular: Dictionary, dev: Boolean): List<String> {
        val words = WordPacks.builtIn.flatMap { p ->
            File("src/main/assets/" + p.asset).readLines().map { it.substringBefore('\t').trim() }
        }.map { it.lowercase() }.filter { it.length >= Suggester.SPLIT_MIN_LENGTH && it.all { c -> c.isLetter() } && regular.indexOf(it) < 0 }
            .distinct().sorted()
        return words.filterIndexed { i, _ -> (i % 2 == 0) == dev }
    }

    private class Result(val fixed: Int, val wrong: Int, val offered: Int, val cases: Int, val splitMeant: Int, val changedMeant: Int, val meant: Int)

    private fun measure(full: Pair<Suggester, NgramModel>, regular: Pair<Suggester, NgramModel>, cases: List<Case>, meant: List<String>, list: Boolean = false): Result {
        val (s, _) = full
        var fixed = 0
        var wrong = 0
        var offered = 0
        for (c in cases) {
            val cands = s.suggest(c.joined, Suggester.AUTOCORRECT_CANDIDATES, taps, c.context)
            if (cands.take(3).any { it.word.equals(c.meant, ignoreCase = true) }) offered++
            val fix = s.autocorrectFrom(c.joined, cands, taps, c.context)
            when {
                fix == null -> if (list) println("JOINED LEFT ${c.joined} (meant ${c.meant}): ${cands.take(3).map { it.word }}")
                fix.equals(c.meant, ignoreCase = true) -> fixed++
                else -> { wrong++; if (list) println("JOINED WRONG ${c.joined} -> $fix (meant ${c.meant})") }
            }
        }
        val (r, _) = regular
        var split = 0
        var changed = 0
        for (w in meant) {
            val fix = r.autocorrectFrom(w, r.suggest(w, Suggester.AUTOCORRECT_CANDIDATES, taps), taps)
            if (fix != null && !fix.equals(w, ignoreCase = true)) {
                changed++
                if (' ' in fix) { split++; if (list) println("JOINED SPLIT MEANT $w -> $fix") }
            }
        }
        return Result(fixed, wrong, offered, cases.size, split, changed, meant.size)
    }

    private fun report(label: String, r: Result) = println(
        "JOINED $label: joined pairs ${r.cases}: split right ${pct(r.fixed, r.cases)}, made something else ${pct(r.wrong, r.cases)}, " +
            "offered in the strip ${pct(r.offered, r.cases)}; words meant as typed (${r.meant}): split ${pct(r.splitMeant, r.meant)}, changed at all ${pct(r.changedMeant, r.meant)}"
    )

    private fun pct(a: Int, b: Int) = if (b == 0) "-" else "%.1f%%".format(100.0 * a / b)

    @Test
    fun joinedWords() {
        assumeTrue("JOINED not set", System.getenv("JOINED") != null)
        val full = suggesterFor(GlideBenchmarkTest.dictionary)
        val regularDict = BuiltInWords.with()
        val regular = suggesterFor(regularDict)
        val saved = listOf(Suggester.SPLIT_ENABLED, Suggester.SPLIT_AUTOCORRECT)
        val savedCost = Suggester.SPLIT_COST
        val savedKeep = Suggester.SPLIT_KEEP
        try {
            // JOINED_HALF=dev or test runs one half: tune on dev before the test half is looked at.
            val halves = when (System.getenv("JOINED_HALF")) { "dev" -> listOf(true); "test" -> listOf(false); else -> listOf(true, false) }
            for (dev in halves) {
                val half = if (dev) "dev" else "test"
                val cases = joinedCases(GlideBenchmarkTest.dictionary, full.second, dev)
                val meant = meantWords(regularDict, dev)
                Suggester.SPLIT_ENABLED = false; Suggester.SPLIT_AUTOCORRECT = false
                report("$half, no splitting", measure(full, regular, cases, meant))
                Suggester.SPLIT_ENABLED = true; Suggester.SPLIT_AUTOCORRECT = false
                report("$half, strip only", measure(full, regular, cases, meant))
                Suggester.SPLIT_AUTOCORRECT = true
                if (dev && System.getenv("JOINED_SWEEP") != null) {
                    for (cost in (System.getenv("JOINED_COSTS") ?: "0.5,1.0,1.5,2.0").split(",").map { it.toDouble() }) for (keep in (System.getenv("JOINED_KEEPS") ?: "2e-8,1e-7,5e-7,2e-6").split(",").map { it.toDouble() }) {
                        Suggester.SPLIT_COST = cost; Suggester.SPLIT_KEEP = keep
                        report("$half, cost $cost keep $keep", measure(full, regular, cases, meant))
                    }
                    Suggester.SPLIT_COST = savedCost; Suggester.SPLIT_KEEP = savedKeep
                }
                report("$half, shipped (cost ${Suggester.SPLIT_COST}, keep ${Suggester.SPLIT_KEEP})", measure(full, regular, cases, meant, list = System.getenv("JOINED_LIST") != null))
            }
        } finally {
            Suggester.SPLIT_ENABLED = saved[0]; Suggester.SPLIT_AUTOCORRECT = saved[1]
            Suggester.SPLIT_COST = savedCost; Suggester.SPLIT_KEEP = savedKeep
        }
    }
}
