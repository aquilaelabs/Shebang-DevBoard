package dev.shebang.devboard.ime

import android.os.Handler
import android.text.InputType
import dev.shebang.devboard.dict.NgramModelTest
import dev.shebang.devboard.dict.Suggester
import dev.shebang.devboard.glide.GlideResult
import dev.shebang.devboard.glide.GlideWord
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.Executor

/** Typing: the pronoun I, autocorrect on space and punctuation (also when the suggestions lag), and undoing it. */
class TypingCorrectionTest {
    private val dictionary get() = NgramModelTest.dictionary
    private val lm get() = NgramModelTest.lm
    private val ic = FakeInputConnection()
    private val learned = ArrayList<String>()
    private val suggester by lazy {
        Suggester(dictionary, null, FloatArray(dictionary.size) { kotlin.math.exp(-lm.unigramCost(it).toDouble()).toFloat() })
    }

    private val controller = TextInputController(
        { ic },
        object : TextInputController.Ui {
            override fun showCandidates(words: List<String>) = Unit
            override fun setComposing(composing: Boolean) = Unit
        },
        Executor { it.run() },
        Handler(),
        object : TextInputController.Learner {
            override fun learnWord(word: String, previous: String?, sentenceStart: Boolean) {
                learned += word
            }
            override fun learnGlide(observations: FloatArray) = Unit
            override fun correction(stroke: FloatArray?, word: Int, dictionary: dev.shebang.devboard.dict.Dictionary) = Unit
        },
        postToMain = { it.run() },
    ).also {
        it.startInput(FieldInfo.from(InputType.TYPE_CLASS_TEXT, 0))
        it.suggester = suggester
        it.settings = it.settings.copy(autocorrect = true)
    }

    private fun type(s: String) {
        for (c in s) if (c == ' ') controller.space() else controller.typeText(c.toString())
    }

    private fun idx(w: String) = dictionary.indexOfLower(w)

    private fun glide(w: String) {
        controller.glideContext(dictionary, lm)
        val r = GlideResult(intArrayOf(idx(w)), listOf(null), listOf(null), listOf(GlideWord(idx(w), intArrayOf(idx(w)), floatArrayOf(0f))), intArrayOf(idx(w)), IntArray(0), -1, 5)
        controller.commitGlide(r, dictionary, false, false)
    }

    @Test
    fun theLetterIAloneIsCapitalised() {
        type("so i ")
        assertEquals("so I ", ic.toString())
        type("think i'm ")
        assertEquals("so I think I'm ", ic.toString())
    }

    @Test
    fun theLetterIIsCapitalisedWhenAGlideFollows() {
        type("i")
        glide("went")
        assertEquals("I went", ic.toString())
    }

    @Test
    fun theLetterIIsCapitalisedEvenWithAutocorrectOff() {
        controller.settings = controller.settings.copy(autocorrect = false)
        type("i ")
        assertEquals("I ", ic.toString())
    }

    @Test
    fun aWordTypedWithAnApostropheKeepsItsLetters() {
        // The apostrophe is on purpose: it may move, but no letter changes.
        type("ca'nt ")
        assertEquals("can't ", ic.toString())
        type("y'all ")
        assertEquals("can't y'all ", ic.toString())
    }

    @Test
    fun theWordsBeforeDecideAnApostrophe() {
        // With the language model, as on the device.
        controller.suggester = Suggester(dictionary, null, FloatArray(dictionary.size) { kotlin.math.exp(-lm.unigramCost(it).toDouble()).toFloat() }, lm)
        controller.predictionModel = dictionary to lm
        type("i think its ")
        assertEquals("I think it's ", ic.toString())
        type("the dog wagged its ")
        assertEquals("I think it's the dog wagged its ", ic.toString())
    }

    @Test
    fun aWordJoinedByPunctuationIsLeftAlone() {
        type("f-droid ")
        assertEquals("f-droid ", ic.toString())
        type("node.js ")
        assertEquals("f-droid node.js ", ic.toString())
        // Even where the part alone would be a slip.
        type("x-teh ")
        assertEquals("f-droid node.js x-teh ", ic.toString())
        // After a space a dash is not a joiner.
        type("- wiht ")
        assertEquals("f-droid node.js x-teh - with ", ic.toString())
    }

    @Test
    fun aSlipIsCorrectedOnSpace() {
        type("wiht ")
        assertEquals("with ", ic.toString())
        type("teh ")
        assertEquals("with the ", ic.toString())
        type("dont ")
        assertEquals("with the don't ", ic.toString())
    }

    @Test
    fun aSlipIsCorrectedBeforePunctuation() {
        type("becuase,")
        assertEquals("because,", ic.toString())
    }

    @Test
    fun aDigitDoesNotEndAWordForAutocorrect() {
        type("abc1 ")
        assertEquals("abc1 ", ic.toString())
    }

    @Test
    fun aQuickSpaceIsStillCorrected() {
        // The suggestions for the word have not arrived when space is pressed.
        controller.suggester = null
        type("wiht")
        controller.suggester = suggester
        type(" ")
        assertEquals("with ", ic.toString())
        assertEquals("with", learned.last())
    }

    @Test
    fun backspaceRightAfterAnAutocorrectPutsBackWhatWasTyped() {
        type("wiht ")
        controller.backspace()
        assertEquals("wiht", ic.toString())
        // Taken back once, it stays as typed.
        type(" wiht ")
        assertEquals("wiht wiht ", ic.toString())
    }

    @Test
    fun wordsTypedRightAreLeftAlone() {
        type("the quick brown fox is ill ")
        assertEquals("the quick brown fox is ill ", ic.toString())
    }

    @Test
    fun codeLikeWordsAreLeftAlone() {
        type("call getUsr with max_retires and usr2 ")
        assertEquals("call getUsr with max_retires and usr2 ", ic.toString())
        // Underscores join a word: it was learned as one.
        assert("max_retires" in learned)
    }

    @Test
    fun aWordUsedElsewhereInTheTextIsLeftAlone() {
        ic.commitText("ssh into kubctl first. ", 1)
        type("then kubctl ")
        assertEquals("ssh into kubctl first. then kubctl ", ic.toString())
    }

    @Test
    fun aRareWordTypedForAContractionGetsItsApostrophe() {
        type("i cant ")
        assertEquals("I can't ", ic.toString())
    }
}
