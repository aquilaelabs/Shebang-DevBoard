package dev.shebang.devboard.ime

import android.os.Handler
import android.os.Looper
import android.text.InputType
import dev.shebang.devboard.dict.NgramModelTest
import dev.shebang.devboard.dict.Suggester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.Executor

/** The strip shows what space will autocorrect to, and the typed word with a check mark to keep it. */
class CheckMarkTest {
    private val dictionary get() = NgramModelTest.dictionary
    private val lm get() = NgramModelTest.lm
    private val ic = FakeInputConnection()
    private var correction: Triple<String, String, String?>? = null

    private val controller = TextInputController(
        { ic },
        object : TextInputController.Ui {
            override fun showCandidates(words: List<String>) { correction = null }
            override fun setComposing(composing: Boolean) = Unit
            override fun showCorrection(typed: String, fix: String, other: String?) { correction = Triple(typed, fix, other) }
        },
        Executor { it.run() },
        Handler(Looper.getMainLooper()),
        postToMain = { it.run() },
    ).also {
        it.startInput(FieldInfo.from(InputType.TYPE_CLASS_TEXT, 0))
        it.suggester = Suggester(dictionary, null, FloatArray(dictionary.size) { i -> kotlin.math.exp(-lm.unigramCost(i).toDouble()).toFloat() }, lm)
        it.predictionModel = dictionary to lm
        it.settings = it.settings.copy(autocorrect = true, nextWord = false)
    }

    private fun type(s: String) {
        for (c in s) if (c == ' ') controller.space() else controller.typeText(c.toString())
    }

    @Test
    fun aWordAutocorrectWillChangeShowsTheChangeAndACheckMark() {
        type("wiht")
        assertEquals("wiht", correction?.first)
        assertEquals("with", correction?.second)
    }

    @Test
    fun theCheckMarkKeepsTheWordAsTypedFromNowOn() {
        type("wiht")
        controller.pickCandidate("wiht")
        assertEquals("wiht ", ic.toString())
        type("wiht")
        assertNull(correction)
        type(" ")
        assertEquals("wiht wiht ", ic.toString())
    }

    @Test
    fun aWordTypedRightShowsNoCheckMark() {
        type("with")
        assertNull(correction)
    }

    @Test
    fun withAutocorrectOffThereIsNothingToKeep() {
        controller.settings = controller.settings.copy(autocorrect = false)
        type("wiht")
        assertNull(correction)
    }
}
