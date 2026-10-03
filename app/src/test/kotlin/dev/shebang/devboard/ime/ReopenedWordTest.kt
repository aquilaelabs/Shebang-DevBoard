package dev.shebang.devboard.ime

import android.os.Handler
import android.os.Looper
import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.Executor

/**
 * Backspacing into a word and typing on (B2): the whole word is the composing word, so the underline covers
 * all of it and a strip pick replaces all of it, however the field reports the cursor.
 */
class ReopenedWordTest {
    private val ic = FakeInputConnection()
    private var now = 0L

    private val controller = TextInputController(
        { ic },
        object : TextInputController.Ui {
            override fun showCandidates(words: List<String>) = Unit
            override fun setComposing(composing: Boolean) = Unit
        },
        Executor { it.run() },
        Handler(Looper.getMainLooper()),
        postToMain = { it.run() },
    ).also {
        it.startInput(FieldInfo.from(InputType.TYPE_CLASS_TEXT, 0))
        it.clock = { now }
    }

    /** How the field reports the cursor after each edit: with the composing span, or without it (-1). */
    private var reportsComposing = true
    private var last = 0

    private fun report() {
        val c = ic.cursor
        val (cs, ce) = if (reportsComposing) ic.composingStart to ic.composingEnd else -1 to -1
        controller.onSelectionChanged(last, last, c, c, cs, ce)
        last = c
    }

    private fun act(pauseMs: Long, action: () -> Unit) {
        now += pauseMs
        action()
        report()
    }

    private fun type(s: String, pauseMs: Long = 150) {
        for (c in s) act(pauseMs) { if (c == ' ') controller.space() else controller.typeText(c.toString()) }
    }

    private fun backspace(times: Int, pauseMs: Long = 150) = repeat(times) { act(pauseMs) { controller.backspace() } }

    private fun composing() = if (ic.composingStart < 0) "" else ic.text.substring(ic.composingStart, ic.composingEnd)

    private fun check() {
        type("hello ")
        backspace(3)
        assertEquals("hel", ic.toString())
        type("p")
        assertEquals("help", ic.toString())
        assertEquals("help", composing())
        act(150) { controller.pickCandidate("helps") }
        assertEquals("helps ", ic.toString())
    }

    @Test
    fun theWholeWordIsComposed() = check()

    @Test
    fun theWholeWordIsComposedWhenTheFieldDoesNotReportComposing() {
        reportsComposing = false
        check()
    }

    @Test
    fun theWholeWordIsComposedAfterAPause() {
        type("hello ")
        backspace(3, pauseMs = 1500)
        type("p", pauseMs = 1500)
        assertEquals("help", composing())
    }

    @Test
    fun typingAtTheEndOfAWordTheCursorWasPutAtComposesTheWholeWord() {
        type("hello ")
        // The user taps at the end of "hello": the field reports the cursor there.
        now += 1000
        ic.finishComposingText()
        ic.cursor = 5
        report()
        type("s")
        assertEquals("hellos ", ic.toString())
        assertEquals("hellos", composing())
    }
}
