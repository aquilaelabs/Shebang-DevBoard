package dev.shebang.devboard.ime

import android.os.Handler
import android.os.Looper
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
    private var shown: List<String> = emptyList()
    /** Whether the strip shows what was typed behind a check mark (space would change it). */
    private var checkMark = false
    private val suggester by lazy {
        Suggester(dictionary, null, FloatArray(dictionary.size) { kotlin.math.exp(-lm.unigramCost(it).toDouble()).toFloat() })
    }

    private val controller = TextInputController(
        { ic },
        object : TextInputController.Ui {
            override fun showCandidates(words: List<String>) {
                shown = words
                checkMark = false
            }
            override fun showCorrection(typed: String, fix: String, other: String?) {
                shown = listOfNotNull(typed, fix, other)
                checkMark = true
            }
            override fun setComposing(composing: Boolean) = Unit
        },
        Executor { it.run() },
        Handler(Looper.getMainLooper()),
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
    fun aNameOrAcronymTypedInCapitalsIsLeftAlone() {
        // "Thw" mid-sentence with a capital the keyboard did not give: a name meant as typed.
        type("we met Thw ")
        assertEquals("we met Thw ", ic.toString())
        type("at TEH ")
        assertEquals("we met Thw at TEH ", ic.toString())
        // At the start of a sentence a capital says nothing: still a slip.
        type(". Teh ")
        assertEquals("we met Thw at TEH . The ", ic.toString())
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
    fun aLateCorrectionAlsoRemembersTheStrip() {
        controller.suggester = null
        type("wiht")
        controller.suggester = suggester
        type(" cat ")
        assertEquals("with cat ", ic.toString())
        repeat(5) { controller.backspace() }
        assertEquals("with", ic.toString())
        assertEquals(listOf("wiht", "with", "wit"), shown)
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
    fun backspacingBackToACorrectedWordOffersWhatWasTyped() {
        type("wiht")
        // Before space: what was typed, the correction space will write, and one more suggestion.
        val before = shown
        assertEquals(true, checkMark)
        assertEquals(3, before.size)
        assertEquals(listOf("wiht", "with"), before.take(2))
        type(" cat ")
        assertEquals("with cat ", ic.toString())
        repeat(5) { controller.backspace() }
        // The word stays as corrected, and the strip offers the same words, without the check mark: space
        // would leave the word as it is now.
        assertEquals("with", ic.toString())
        assertEquals(before, shown)
        assertEquals(false, checkMark)
        controller.pickCandidate("wiht")
        assertEquals("wiht ", ic.toString())
        // Picked back, it stays as typed.
        type("wiht ")
        assertEquals("wiht wiht ", ic.toString())
    }

    @Test
    fun onlyTheCorrectedWordItselfOffersWhatWasTyped() {
        type("with wiht cat ")
        assertEquals("with with cat ", ic.toString())
        repeat(5) { controller.backspace() }
        assertEquals(listOf("wiht", "with"), shown.take(2))
        // The "with" typed right before it was never corrected.
        repeat(5) { controller.backspace() }
        assertEquals("with", ic.toString())
        assertEquals(false, "wiht" in shown)
    }

    @Test
    fun webAddressesAreTypedExactly() {
        controller.startInput(FieldInfo.from(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, 0))
        // No autocorrect, and no capital for a lone i.
        type("wiht i ")
        assertEquals("wiht i ", ic.toString())
        // A strip pick adds no space; a glide adds none before or after.
        ic.text.setLength(0)
        ic.cursor = 0
        type("githu")
        controller.pickCandidate("github")
        assertEquals("github", ic.toString())
        controller.typeText(".")
        glide("net")
        assertEquals("github.net", ic.toString())
    }

    @Test
    fun anAddressBarFoundByItsHintIsAWebAddressField() {
        val bar = FieldInfo.from(InputType.TYPE_CLASS_TEXT, 0, "Search or enter address")
        assertEquals(dev.shebang.devboard.layout.FieldVariant.URL, bar.variant)
        assertEquals(true, bar.isAddress)
        assertEquals(dev.shebang.devboard.layout.FieldVariant.URL, FieldInfo.from(InputType.TYPE_CLASS_TEXT, 0, "Search or type URL").variant)
        assertEquals(dev.shebang.devboard.layout.FieldVariant.PLAIN, FieldInfo.from(InputType.TYPE_CLASS_TEXT, 0, "Search").variant)
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
