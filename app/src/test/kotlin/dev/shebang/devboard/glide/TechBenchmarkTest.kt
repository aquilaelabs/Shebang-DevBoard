package dev.shebang.devboard.glide

import dev.shebang.devboard.dict.NgramModel
import dev.shebang.devboard.dict.WordPacks
import dev.shebang.devboard.dict.WordPredictions
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Random

/**
 * Technical text: sentences from the documentation tools/build_ngrams.py --tech counts, held out of the model
 * (heldout_tech.tsv). Each word is glided (simulated) with the word before as context, and the strip's three
 * next-word predictions are checked before it; both are reported for every word and for the packs' words
 * (development and computer terms, names) alone. DEVBOARD_NGRAMS names another model to compare.
 */
class TechBenchmarkTest {
    private val dictionary get() = GlideBenchmarkTest.dictionary
    private val lm get() = GlideBenchmarkTest.lm
    private val sim = GestureSimulator(GlideBenchmarkTest.layout, GlideBenchmarkTest.DENSITY)

    private val sentences: List<List<String>> by lazy {
        val f = File("src/test/resources/glide/heldout_tech.tsv")
        if (!f.isFile) emptyList() else f.readLines().filter { !it.startsWith("#") && it.isNotBlank() }.map { it.substringAfter('\t').split(' ') }
    }

    private fun ctx(w: String?): Int {
        if (w == null) return NgramModel.SENTENCE_START
        val i = dictionary.indexOfLower(w)
        return if (i < 0) NgramModel.UNKNOWN else lm.contextOf(i)
    }

    private fun isPackWord(w: String): Boolean {
        val i = dictionary.indexOfLower(w)
        return i >= 0 && dictionary.packs[i].toInt() != WordPacks.REGULAR
    }

    private class Score {
        var n = 0
        var top1 = 0
        var top3 = 0
        override fun toString() = "top-1 ${GlideBenchmarkTest.pct(top1, n)}  top-3 ${GlideBenchmarkTest.pct(top3, n)}  ($n)"
    }

    @Test
    fun technicalSentences() {
        assumeTrue("no heldout_tech.tsv", sentences.isNotEmpty())
        val rnd = Random(7)
        val decoder = StreamingGlideDecoder(GlideBenchmarkTest.language)
        val glideAll = Score()
        val glidePack = Score()
        val predictAll = Score()
        val predictPack = Score()
        val misses = HashMap<String, Int>()
        for (sentence in sentences.take(800)) {
            var prev: String? = null
            var prev2: String? = null
            for (w in sentence) {
                val idx = dictionary.indexOfLower(w)
                if (idx >= 0) {
                    val pack = isPackWord(w)
                    // The strip's three predictions before the word.
                    val rep = lm.contextOf(idx)
                    val guesses = WordPredictions.predict(dictionary, lm, GlideBenchmarkTest.nextWord, null, if (prev == null) NgramModel.UNKNOWN else ctx(prev2), ctx(prev), 3)
                        .map { lm.contextOf(it) }
                    for (s in listOfNotNull(predictAll, if (pack) predictPack else null)) {
                        s.n++
                        if (guesses.firstOrNull() == rep) s.top1++
                        if (rep in guesses) s.top3++
                    }
                    // The word glided, with the word before as context.
                    if (sim.keys(w) != null && w.length >= 2) {
                        val g = sim.generate(w, rnd)
                        if (g != null) {
                            decoder.begin(GlideBenchmarkTest.layout, GlideContext(ctx(prev)), g.t[0])
                            for (i in 0 until g.count) decoder.addPoint(g.x[i], g.y[i], g.t[i])
                            val got = decoder.finish()?.alternatives?.map { dictionary.lower[it] } ?: emptyList()
                            for (s in listOfNotNull(glideAll, if (pack) glidePack else null)) {
                                s.n++
                                if (got.firstOrNull() == w) s.top1++
                                if (w in got.take(3)) s.top3++
                            }
                            if (pack && got.firstOrNull() != w) misses.merge("$w->${got.firstOrNull()}", 1, Int::plus)
                        }
                    }
                }
                prev2 = prev
                prev = w
            }
        }
        println("TECH glide, every word:     $glideAll")
        println("TECH glide, pack words:     $glidePack")
        println("TECH predict, every word:   $predictAll")
        println("TECH predict, pack words:   $predictPack")
        println("TECH pack-word glide misses: " + misses.entries.sortedByDescending { it.value }.take(25).joinToString(", ") { "${it.key} x${it.value}" })
    }
}
