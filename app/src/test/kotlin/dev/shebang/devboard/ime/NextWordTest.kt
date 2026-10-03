package dev.shebang.devboard.ime

import android.os.Handler
import android.os.Looper
import android.text.InputType
import dev.shebang.devboard.dict.NgramModelTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/** Next-word suggestions: offered after a space, picked with a tap, chained, and never in fields that take none. */
class NextWordTest {
    private val ic = FakeInputConnection()
    private var shown: List<String> = emptyList()

    private val controller = TextInputController(
        { ic },
        object : TextInputController.Ui {
            override fun showCandidates(words: List<String>) { shown = words }
            override fun setComposing(composing: Boolean) = Unit
        },
        Executor { it.run() },
        Handler(Looper.getMainLooper()),
        postToMain = { it.run() },
    ).also {
        it.startInput(FieldInfo.from(InputType.TYPE_CLASS_TEXT, 0))
        it.predictionModel = NgramModelTest.dictionary to NgramModelTest.lm
    }

    private fun type(s: String) {
        for (c in s) if (c == ' ') controller.space() else controller.typeText(c.toString())
    }

    @Test
    fun aSpaceOffersTheLikelyNextWords() {
        type("thank ")
        assertTrue("after 'thank': $shown", "you" in shown)
    }

    @Test
    fun aPickedPredictionGoesInWithASpaceAndTheNextAreOffered() {
        type("thank ")
        controller.pickCandidate("you")
        assertEquals("thank you ", ic.toString())
        assertTrue("offers again after a pick: $shown", shown.isNotEmpty() && "you" !in shown)
    }

    @Test
    fun typingALetterReplacesThePredictions() {
        type("thank ")
        type("v")
        assertTrue("you" !in shown)
        assertEquals("thank v", ic.toString())
    }

    @Test
    fun aSentenceStartIsCapitalised() {
        type("ok. ")
        assertTrue("capitalised at a sentence start: $shown", shown.isNotEmpty() && shown.all { it[0].isUpperCase() })
    }

    @Test
    fun noPredictionsInAPasswordField() {
        controller.startInput(FieldInfo.from(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, 0))
        type("thank ")
        assertTrue(shown.isEmpty())
    }

    @Test
    fun noPredictionsWhenSwitchedOff() {
        controller.settings = controller.settings.copy(nextWord = false)
        type("thank ")
        assertTrue(shown.isEmpty())
    }
}
