package dev.shebang.devboard.ime

import android.os.Handler
import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.Executor

/** Swiping left from backspace deletes whole words, previewed as a selection. */
class DeleteWordsTest {
    private val ic = FakeInputConnection()
    private val controller = TextInputController(
        { ic },
        object : TextInputController.Ui {
            override fun showCandidates(words: List<String>) = Unit
            override fun setComposing(composing: Boolean) = Unit
        },
        Executor { it.run() },
        Handler(),
        postToMain = { it.run() },
    ).also { it.startInput(FieldInfo.from(InputType.TYPE_CLASS_TEXT, 0)) }

    private fun at(text: String) {
        ic.commitText(text, 1)
        controller.onSelectionChanged(0, 0, ic.cursor, ic.cursor, -1, -1)
    }

    @Test
    fun swipingPreviewsThenDeletesWords() {
        at("hello big world ")
        controller.previewDeleteWords(1)
        assertEquals("world ", ic.getSelectedText(0))
        controller.previewDeleteWords(2)
        assertEquals("big world ", ic.getSelectedText(0))
        controller.previewDeleteWords(1)
        controller.deleteWords(1)
        assertEquals("hello big ", ic.toString())
    }

    @Test
    fun swipingBackCancels() {
        at("hello world")
        controller.previewDeleteWords(1)
        controller.previewDeleteWords(0)
        controller.deleteWords(0)
        assertEquals("hello world", ic.toString())
        assertEquals(11, ic.cursor)
    }

    @Test
    fun punctuationAndLineBreaksAreStopsOfTheirOwn() {
        at("one, two\nthree")
        controller.previewDeleteWords(1)
        assertEquals("three", ic.getSelectedText(0))
        controller.previewDeleteWords(2)
        assertEquals("\nthree", ic.getSelectedText(0))
        controller.previewDeleteWords(4)
        assertEquals(", two\nthree", ic.getSelectedText(0))
        controller.deleteWords(4)
        assertEquals("one", ic.toString())
    }

    @Test
    fun withoutAKnownCursorTheWordsStillGo() {
        ic.commitText("keep these words", 1)
        controller.deleteWords(2)
        assertEquals("keep ", ic.toString())
    }
}
