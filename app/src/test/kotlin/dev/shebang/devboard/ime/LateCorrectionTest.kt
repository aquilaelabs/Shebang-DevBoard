package dev.shebang.devboard.ime

import android.os.Handler
import android.os.Looper
import android.text.InputType
import dev.shebang.devboard.dict.NgramModelTest
import dev.shebang.devboard.dict.Suggester
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.Executor

/**
 * Space pressed before a word's suggestions arrived: its correction is worked out late, in the background,
 * and goes in when it arrives, even if the next word is already being typed (B7).
 */
class LateCorrectionTest {
    private val dictionary get() = NgramModelTest.dictionary
    private val lm get() = NgramModelTest.lm
    private val ic = FakeInputConnection()
    /** Background work waits here until [runBackground]: the suggestions are slower than the fingers. */
    private val queued = ArrayDeque<Runnable>()

    private val controller = TextInputController(
        { ic },
        object : TextInputController.Ui {
            override fun showCandidates(words: List<String>) = Unit
            override fun setComposing(composing: Boolean) = Unit
        },
        Executor { queued.addLast(it) },
        Handler(Looper.getMainLooper()),
        postToMain = { it.run() },
    ).also {
        it.startInput(FieldInfo.from(InputType.TYPE_CLASS_TEXT, 0))
        it.suggester = Suggester(dictionary, null, FloatArray(dictionary.size) { i -> kotlin.math.exp(-lm.unigramCost(i).toDouble()).toFloat() })
        it.settings = it.settings.copy(autocorrect = true)
    }

    private fun type(s: String) {
        for (c in s) if (c == ' ') controller.space() else controller.typeText(c.toString())
    }

    private fun runBackground() {
        while (queued.isNotEmpty()) queued.removeFirst().run()
    }

    @Test
    fun aLateCorrectionGoesInWhenItArrives() {
        type("teh ")
        assertEquals("teh ", ic.toString())
        runBackground()
        assertEquals("the ", ic.toString())
    }

    @Test
    fun aLateCorrectionGoesInFrontOfTheNextWordBeingTyped() {
        type("teh ")
        type("ca")
        runBackground()
        assertEquals("the ca", ic.toString())
        // The next word is still the one being typed.
        type("t ")
        runBackground()
        assertEquals("the cat ", ic.toString())
    }

    @Test
    fun aLateCorrectionIsDroppedWhenTheTextChangedUnderIt() {
        type("teh ")
        ic.commitText("x", 1)
        runBackground()
        assertEquals("teh x", ic.toString())
    }
}
