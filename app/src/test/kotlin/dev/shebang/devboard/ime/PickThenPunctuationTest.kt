package dev.shebang.devboard.ime

import android.os.Handler
import android.os.Looper
import android.text.InputType
import dev.shebang.devboard.dict.NgramModelTest
import dev.shebang.devboard.dict.Suggester
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.Executor

/** A word picked from the strip goes in with a space; punctuation typed next takes that space's place. */
class PickThenPunctuationTest {
    private val dictionary = NgramModelTest.dictionary
    private val lm = NgramModelTest.lm
    private val ic = FakeInputConnection()
    private var strip: List<String> = emptyList()
    private val controller = TextInputController(
        { ic },
        object : TextInputController.Ui {
            override fun showCandidates(words: List<String>) { strip = words }
            override fun setComposing(composing: Boolean) = Unit
        },
        Executor { it.run() },
        Handler(Looper.getMainLooper()),
        postToMain = { it.run() },
    ).also {
        it.startInput(FieldInfo.from(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT, 0))
        it.suggester = Suggester(dictionary, null, FloatArray(dictionary.size) { i -> kotlin.math.exp(-lm.unigramCost(i).toDouble()).toFloat() }, lm)
        it.predictionModel = dictionary to lm
        it.settings = it.settings.copy(autocorrect = true, nextWord = true)
    }

    private fun type(s: String) {
        for (c in s) if (c == ' ') controller.space() else controller.typeText(c.toString())
    }

    /** Reports the cursor to the controller, as the app does after each edit. */
    private fun report() = controller.onSelectionChanged(ic.cursor, ic.cursor, ic.cursor, ic.cursor, -1, -1)

    @Test
    fun aTypedWordPickedThenAFullStop() {
        type("hel")
        controller.pickCandidate("hello")
        report()
        assertEquals("hello ", ic.toString())
        type(".")
        assertEquals("hello.", ic.toString().trimEnd())
    }

    @Test
    fun aPickWhoseCursorReportArrivesLateStillGivesUpItsSpace() {
        var now = 1_000L
        controller.clock = { now }
        type("hel")
        val before = ic.cursor
        controller.pickCandidate("hello")
        // A slow app reports the cursor move from the pick after the keyboard stopped expecting it.
        now += 2_000
        controller.onSelectionChanged(before, before, ic.cursor, ic.cursor, -1, -1)
        type(".")
        assertEquals("hello.", ic.toString().trimEnd())
    }

    @Test
    fun theSpaceOwedAfterThePunctuationSurvivesALateReportToo() {
        var now = 1_000L
        controller.clock = { now }
        type("hel")
        controller.pickCandidate("hello")
        type(".")
        // The full stop's own cursor report, late.
        now += 2_000
        controller.onSelectionChanged(ic.cursor - 1, ic.cursor - 1, ic.cursor, ic.cursor, -1, -1)
        type("w")
        assertEquals("hello. w", ic.toString())
    }

    @Test
    fun aRealCursorMoveStillKeepsTheSpace() {
        var now = 1_000L
        controller.clock = { now }
        type("hel")
        controller.pickCandidate("hello")
        report()
        // The user puts the cursor at the start of the field: a full stop there does not take the space.
        now += 2_000
        val end = ic.cursor
        ic.cursor = 0
        controller.onSelectionChanged(end, end, 0, 0, -1, -1)
        type(".")
        assertEquals(".hello ", ic.toString())
    }

    @Test
    fun aPredictedWordPickedThenAComma() {
        type("thank ")
        report()
        val next = strip.firstOrNull { it.isNotBlank() } ?: "you"
        controller.pickCandidate(next)
        report()
        type(",")
        assertEquals("thank $next,", ic.toString().trimEnd())
    }
}
