package dev.shebang.devboard.dict

import dev.shebang.devboard.glide.GlideBenchmarkTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Next-word suggestions on the held-out sentences (never seen by the model): before each word, the strip's
 * three predictions from the two words before it. Prints how often the word was among them, and the model's
 * per-word perplexity with the trigram ([NgramModel.cost3]) and with the bigram alone ([NgramModel.cost]).
 */
class PredictionBenchmarkTest {
    private val dictionary get() = GlideBenchmarkTest.dictionary
    private val lm get() = GlideBenchmarkTest.lm

    private fun ctx(w: String?): Int = when (w) {
        null -> NgramModel.SENTENCE_START
        else -> dictionary.indexOfLower(w).let { if (it < 0) NgramModel.UNKNOWN else lm.contextOf(it) }
    }

    /** FUTO's test sentences (never counted into the model, another style than Tatoeba's), when FUTO_SWIPES names them. */
    private val futoSentences: List<List<String>> by lazy {
        val f = System.getenv("FUTO_SWIPES")?.let { java.io.File(it) } ?: return@lazy emptyList()
        if (!f.isFile) return@lazy emptyList()
        val seen = LinkedHashSet<String>()
        f.bufferedReader().useLines { lines ->
            for (l in lines) {
                val m = Regex("\"sentence\":\"((?:[^\"\\\\]|\\\\.)*)\"").find(l) ?: continue
                seen += m.groupValues[1]
                if (seen.size >= 3000) break
            }
        }
        seen.map { s -> Regex("[A-Za-z]+(?:['’][A-Za-z]+)*").findAll(s).map { it.value.replace('’', '\'') }.toList() }.filter { it.isNotEmpty() }
    }

    @Test
    fun nextWordHitRateOnFutoSentences() {
        org.junit.Assume.assumeTrue("FUTO_SWIPES not set", futoSentences.isNotEmpty())
        hitRate("FUTO", futoSentences.take(2000))
    }

    @Test
    fun nextWordHitRate() {
        val (n, top3) = hitRate("", GlideBenchmarkTest.heldOut.take(2000))
        assertTrue("predictions should find the next word often", top3 * 100 >= n * 20)
    }

    private fun hitRate(label: String, sentences: List<List<String>>): Pair<Int, Int> {
        var n = 0
        var top1 = 0
        var top3 = 0
        var bits3 = 0.0
        var bits2 = 0.0
        var scored = 0
        for (sentence in sentences) {
            val words = sentence.map { it.lowercase() }
            for (i in words.indices) {
                val w = dictionary.indexOfLower(words[i])
                if (w < 0) continue
                val c1 = ctx(words.getOrNull(i - 1))
                val c2 = if (i == 0) NgramModel.UNKNOWN else ctx(words.getOrNull(i - 2))
                val rep = lm.contextOf(w)
                val guesses = lm.predict(c2, c1, 3).map { lm.contextOf(it) }
                n++
                if (guesses.firstOrNull() == rep) top1++
                if (rep in guesses) top3++
                bits3 += lm.cost3(w, c2, c1)
                bits2 += lm.cost(w, c1)
                scored++
            }
        }
        val pct = GlideBenchmarkTest::pct
        val tag = if (label.isEmpty()) "" else "$label "
        println("PREDICT $tag$n words: next word first ${pct(top1, n)}, in the three ${pct(top3, n)}")
        println("PREDICT ${tag}perplexity trigram %.1f, bigram %.1f".format(kotlin.math.exp(bits3 / scored), kotlin.math.exp(bits2 / scored)))
        return n to top3
    }
}
