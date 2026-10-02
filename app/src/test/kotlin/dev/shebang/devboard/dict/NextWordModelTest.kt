package dev.shebang.devboard.dict

import dev.shebang.devboard.glide.GlideBenchmarkTest
import dev.shebang.devboard.ime.GlideText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The Kotlin next-word model reads sentences as the trained PyTorch model does (test vectors from
 * tools/lm_model/export.py, with the weights as stored), and reads the sentence the cursor is in.
 */
class NextWordModelTest {
    private val model get() = GlideBenchmarkTest.nextWord

    @Test
    fun matchesThePyTorchModel() {
        val m = model
        val vectors = File("src/test/resources/dict/next_word_vectors.txt")
        assumeTrue("no next-word model or vectors", m != null && vectors.isFile)
        var checked = 0
        for (line in vectors.readLines().filter { it.isNotBlank() }) {
            val (sentence, top) = line.split('\t')
            val costs = m!!.logProbs(sentence.split(' '))
            for (entry in top.split(' ')) {
                val word = entry.substringBeforeLast(':')
                // The end of a sentence and the like are never offered as words.
                if (word.startsWith("<")) continue
                val logp = entry.substringAfterLast(':').toFloat()
                assertEquals("$sentence -> $word", -logp, costs[m.idOf(word)], 2e-3f)
                checked++
            }
        }
        assertTrue(checked > 0)
    }

    @Test
    fun aWordOutsideTheVocabularyIsUnlikelyButPossible() {
        val m = model
        assumeTrue(m != null)
        val costs = NextWordModel.copyOf(m!!)
        costs.logProbs(listOf("we", "met"))
        assertTrue("unknown ${costs.unknownCost}", costs.unknownCost.isFinite() && costs.unknownCost > 0f)
    }

    @Test
    fun aLongerSentenceContinuesFromTheShorterOne() {
        val m = model
        assumeTrue(m != null)
        val fresh = NextWordModel.copyOf(m!!)
        val a = fresh.logProbs(listOf("thank", "you", "for", "the")).copyOf()
        val step = NextWordModel.copyOf(m)
        step.logProbs(listOf("thank", "you"))
        step.logProbs(listOf("thank", "you", "for"))
        val b = step.logProbs(listOf("thank", "you", "for", "the"))
        for (i in a.indices) if (a[i].isFinite()) assertEquals(a[i], b[i], 1e-4f)
    }

    @Test
    fun theSentenceIsTheOneTheCursorIsIn() {
        assertEquals(listOf("so", "we", "met", null, "times"), GlideText.sentenceWords("Hi there. So we met 3 times"))
        assertEquals(listOf<String?>(), GlideText.sentenceWords("Done!"))
        assertEquals(listOf("don't"), GlideText.sentenceWords("Fine.\nDon’t"))
    }
}
