package dev.shebang.devboard.dict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NgramModelTest {
    companion object {
        val dictionary: Dictionary by lazy {
            BuiltInWords.all()
        }
        val lm: NgramModel by lazy { File(System.getenv("DEVBOARD_NGRAMS") ?: "src/main/assets/dict/en_ngrams.bin").inputStream().use { NgramModel.load(it, dictionary) } }
    }

    private fun idx(w: String) = dictionary.indexOfLower(w).also { assertTrue("$w missing", it >= 0) }
    private fun ctx(w: String) = lm.contextOf(idx(w))

    @Test
    fun commonWordsAreCheap() {
        assertTrue(lm.unigramCost(idx("the")) < lm.unigramCost(idx("thee")))
        assertTrue(lm.unigramCost(idx("help")) < lm.unigramCost(idx("held")))
        assertTrue(lm.unigramCost(idx("have")) < lm.unigramCost(idx("hove")))
    }

    @Test
    fun contextPrefersNaturalPairs() {
        assertTrue(lm.cost(idx("of"), ctx("out")) < lm.cost(idx("if"), ctx("out")))
        assertTrue(lm.cost(idx("course"), ctx("of")) < lm.cost(idx("course"), ctx("if")))
        assertTrue(lm.cost(idx("to"), ctx("want")) < lm.cost(idx("too"), ctx("want")))
        assertTrue(lm.cost(idx("too"), ctx("me")) < lm.cost(idx("too"), ctx("the")))
    }

    @Test
    fun sentenceStartAndUnknownContexts() {
        val the = idx("the")
        assertEquals(lm.unigramCost(the), lm.cost(the, NgramModel.UNKNOWN), 0f)
        assertTrue(lm.cost(idx("i"), NgramModel.SENTENCE_START) < lm.unigramCost(idx("i")))
    }

    @Test
    fun contractionsAndCappedNames() {
        assertTrue(lm.unigramCost(idx("it's")) < lm.unigramCost(idx("its")) + 3f)
        // "Tom" is Tatoeba's default name; its counts were scaled to match "John".
        assertEquals(lm.unigramCost(idx("john")).toDouble(), lm.unigramCost(idx("tom")).toDouble(), 0.7)
    }

    @Test
    fun everyDictionaryWordHasAFinitePrior() {
        for (i in 0 until dictionary.size) {
            val c = lm.unigramCost(i)
            assertTrue("${dictionary.words[i]} has cost $c", c.isFinite() && c > 0f)
        }
    }
}
