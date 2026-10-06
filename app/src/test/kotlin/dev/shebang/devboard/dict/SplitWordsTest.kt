package dev.shebang.devboard.dict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Two words typed without the space between them are offered, and autocorrected, as two words. */
class SplitWordsTest {
    private val dict = NgramModelTest.dictionary
    private val lm = NgramModelTest.lm
    private val s = Suggester(dict, null, FloatArray(dict.size) { kotlin.math.exp(-lm.unigramCost(it).toDouble()).toFloat() }, lm)

    private fun ctx(vararg before: String): Suggester.Context {
        fun id(w: String?) = if (w == null) NgramModel.SENTENCE_START else dict.indexOfLower(w).let { if (it < 0) NgramModel.UNKNOWN else lm.contextOf(it) }
        return Suggester.Context(if (before.size < 2) NgramModel.UNKNOWN else id(before[before.size - 2]), id(before.lastOrNull()))
    }

    /** As the keyboard scores a typed word: through the slip costs, with the tap positions unknown. */
    private val taps = SlipCost.Taps { _, _, _ -> null }

    private fun fix(typed: String, context: Suggester.Context? = null) =
        s.autocorrectFrom(typed, s.suggest(typed, Suggester.AUTOCORRECT_CANDIDATES, taps, context), taps, context)

    @Test
    fun twoWordsRunTogetherAreOfferedAndAutocorrectedWithTheSpace() {
        // "a taco" leads the strip; space keeps "ataco" as typed, since "taco" after "a" is too rare to beat
        // keeping a word typed on purpose (SPLIT_KEEP), and never turns it into "tacos".
        val strip = s.suggest("ataco", 3, taps, ctx("i", "want")).map { it.word }
        assertEquals("strip for 'ataco': $strip", "a taco", strip.first())
        assertTrue(fix("ataco", ctx("i", "want")) in listOf(null, "a taco"))
        // Common pairs are split on space.
        assertEquals("thank you", fix("thankyou", ctx("ok")))
        assertEquals("of the", fix("ofthe", ctx("one")))
    }

    @Test
    fun theTypedCapitalsCarryOver() {
        assertEquals("Thank you", fix("Thankyou"))
        assertEquals("OF THE", fix("OFTHE", ctx("one")))
    }

    @Test
    fun wordsTheDictionaryKnowsAreNeverSplit() {
        for (w in listOf("into", "today", "cannot", "someone", "however", "without", "kubectl", "localhost")) {
            assertNull("'$w' was changed", fix(w))
        }
    }

    @Test
    fun aSplitNeedsTwoCommonWords() {
        // "x" is not a word on its own, and a rare word does not make a split.
        assertTrue(s.suggest("xbox", 6).none { ' ' in it.word && it.word.startsWith("x ") })
        assertTrue(s.suggest("thezyx", 6).none { ' ' in it.word })
    }
}
