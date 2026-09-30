package dev.shebang.devboard.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class TextInputControllerTest {
    @Test
    fun stripArrangementShowsTypedWordWhenItIsNotSuggested() {
        assertEquals(listOf("helo", "hello", "help"), TextInputController.arrangeForStrip("helo", listOf("hello", "help", "held")))
        assertEquals(listOf("help", "hello", "held"), TextInputController.arrangeForStrip("hello", listOf("hello", "help", "held")))
        assertEquals(listOf("zzz"), TextInputController.arrangeForStrip("zzz", emptyList()))
    }

    @Test
    fun glideAlternativesPutTheBestInTheMiddle() {
        assertEquals(listOf("b", "a", "c"), TextInputController.arrangeBestMiddle(listOf("a", "b", "c", "d")))
        assertEquals(listOf("", "a", ""), TextInputController.arrangeBestMiddle(listOf("a")))
    }
}
