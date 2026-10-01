package dev.shebang.devboard.ime

import android.os.Handler
import android.text.InputType
import dev.shebang.devboard.dict.Dictionary
import dev.shebang.devboard.dict.NgramModelTest
import dev.shebang.devboard.dict.Suggester
import dev.shebang.devboard.glide.GlideBenchmarkTest
import dev.shebang.devboard.glide.TapModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/** Typing with tap positions: kept in step with the word, and only words typed right teach the tap offsets. */
class TapTypingTest {
    private val dictionary get() = NgramModelTest.dictionary
    private val lm get() = NgramModelTest.lm
    private val layout get() = GlideBenchmarkTest.layout
    private val ic = FakeInputConnection()
    private val taught = ArrayList<FloatArray>()

    private val controller = TextInputController(
        { ic },
        object : TextInputController.Ui {
            override fun showCandidates(words: List<String>) = Unit
            override fun setComposing(composing: Boolean) = Unit
        },
        Executor { it.run() },
        Handler(),
        object : TextInputController.Learner {
            override fun learnWord(word: String, previous: String?, sentenceStart: Boolean) = Unit
            override fun learnGlide(observations: FloatArray) = Unit
            override fun correction(stroke: FloatArray?, word: Int, dictionary: Dictionary) = Unit
            override fun learnTaps(observations: FloatArray) { taught += observations }
        },
        postToMain = { it.run() },
    ).also {
        it.startInput(FieldInfo.from(InputType.TYPE_CLASS_TEXT, 0))
        it.suggester = Suggester(dictionary, null, FloatArray(dictionary.size) { i -> kotlin.math.exp(-lm.unigramCost(i).toDouble()).toFloat() })
        it.settings = it.settings.copy(autocorrect = true)
        it.tapModel = TapModel(layout, GlideBenchmarkTest.DENSITY)
    }

    /** Taps each letter at its key's centre, nudged by [dx] pixels for the letter at [at]. */
    private fun tap(word: String, at: Int = -1, dx: Float = 0f) {
        for ((i, c) in word.withIndex()) {
            val x = layout.centerX[c - 'a'] + if (i == at) dx else 0f
            controller.typeText(c.toString(), x, layout.centerY[c - 'a'])
        }
    }

    @Test
    fun aWordTypedRightTeachesWhereItsTapsLanded() {
        tap("hello")
        controller.space()
        assertEquals("hello ", ic.toString())
        assertEquals(1, taught.size)
        assertEquals(15, taught[0].size)
    }

    @Test
    fun aCorrectedWordTeachesNothing() {
        tap("wiht")
        controller.space()
        assertEquals("with ", ic.toString())
        assertTrue(taught.isEmpty())
    }

    @Test
    fun tapsStayInStepThroughBackspace() {
        tap("helll")
        controller.backspace()
        tap("o")
        controller.space()
        assertEquals("hello ", ic.toString())
        assertEquals(15, taught.single().size)
    }

    @Test
    fun aReopenedWordHasNoTapsToTeach() {
        tap("hello")
        controller.space()
        taught.clear()
        // Backspace into the word: its letters' taps are not known any more, only the new one's.
        controller.backspace()
        tap("s")
        controller.space()
        assertTrue(taught.all { it.size <= 3 })
    }
}
