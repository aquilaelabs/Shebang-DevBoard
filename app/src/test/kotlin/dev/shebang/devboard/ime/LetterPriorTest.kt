package dev.shebang.devboard.ime

import android.os.Handler
import android.text.InputType
import dev.shebang.devboard.dict.LetterPrior
import dev.shebang.devboard.dict.NgramModelTest
import dev.shebang.devboard.dict.Suggester
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/** The odds of the next letter, handed to the keyboard for weighing taps between keys. */
class LetterPriorTest {
    private val dictionary get() = NgramModelTest.dictionary
    private val lm get() = NgramModelTest.lm
    private val ic = FakeInputConnection()
    private var prior: FloatArray? = null
    private val priors = ArrayList<FloatArray?>()

    private fun controller(inputType: Int = InputType.TYPE_CLASS_TEXT) = TextInputController(
        { ic },
        object : TextInputController.Ui {
            override fun showCandidates(words: List<String>) = Unit
            override fun setComposing(composing: Boolean) = Unit
            override fun setLetterPrior(prior: FloatArray?) {
                this@LetterPriorTest.prior = prior
                priors += prior
            }
        },
        Executor { it.run() },
        Handler(),
        postToMain = { it.run() },
    ).also {
        it.startInput(FieldInfo.from(inputType, 0))
        it.suggester = Suggester(dictionary, null, FloatArray(dictionary.size) { i -> kotlin.math.exp(-lm.unigramCost(i).toDouble()).toFloat() }, lm)
        it.predictionModel = dictionary to lm
    }

    private fun TextInputController.type(s: String) {
        for (c in s) {
            if (c == ' ') space() else typeText(c.toString())
            refreshLetterPrior()
        }
    }

    private fun p(c: Char) = prior!![c - 'a']

    @Test
    fun afterThTheLetterEIsFarLikelierThanW() {
        controller().type("th")
        assertTrue("e ${p('e')} w ${p('w')}", p('e') > 20 * p('w'))
    }

    @Test
    fun theWordsBeforeShapeTheFirstLetter() {
        controller().type("thank ")
        assertNotNull(prior)
        // "you" follows "thank": y leads.
        assertTrue("y ${p('y')}", ('a'..'z').maxBy { p(it) } == 'y')
    }

    @Test
    fun eachEditClearsTheOddsBeforeNewOnesArrive() {
        val c = controller()
        c.type("t")
        priors.clear()
        c.type("h")
        // The last two hand-overs for "h": cleared, then the new odds.
        assertNull(priors[priors.size - 2])
        assertNotNull(priors.last())
    }

    @Test
    fun noOddsInAPasswordField() {
        controller(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD).type("th")
        assertNull(prior)
    }

    @Test
    fun noOddsInAnEmailField() {
        controller(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS).type("th")
        assertNull(prior)
    }

    @Test
    fun aPrefixNoWordContinuesHasNoOdds() {
        controller().type("qzx")
        assertNull(prior)
    }

    @Test
    fun theFloorKeepsEveryLetterPossible() {
        // Even a letter no word would have next costs a bounded amount.
        assertTrue(LetterPrior.cost(FloatArray(26), 'q') < 7.0)
    }
}
