package dev.shebang.devboard.ime

import android.os.Handler
import android.os.Looper
import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.Executor

/** Code mode's bracket and quote pairs. */
class CodePairingTest {
    private val ic = FakeInputConnection()
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
        it.codeMode = true
    }

    private fun type(s: String) {
        for (c in s) controller.typeText(c.toString())
    }

    /** The field with "|" where the cursor is. */
    private fun field() = ic.toString().substring(0, ic.cursor) + "|" + ic.toString().substring(ic.cursor)

    @Test
    fun anOpeningBracketBringsItsPartner() {
        type("f(")
        assertEquals("f(|)", field())
        type("x")
        assertEquals("f(x|)", field())
        type(")")
        assertEquals("f(x)|", field())
    }

    @Test
    fun nestedBrackets() {
        type("{[(")
        assertEquals("{[(|)]}", field())
        type(")]}")
        assertEquals("{[()]}|", field())
    }

    @Test
    fun quotesPairWhenTheyOpenSomething() {
        type("s = \"")
        assertEquals("s = \"|\"", field())
        type("a\"")
        assertEquals("s = \"a\"|", field())
    }

    @Test
    fun anApostropheAfterALetterIsNotPaired() {
        type("don'")
        assertEquals("don'|", field())
    }

    @Test
    fun backspaceBetweenAnEmptyPairTakesBoth() {
        type("x = [")
        controller.backspace()
        assertEquals("x = |", field())
    }

    @Test
    fun noPairsOutsideCodeModeOrWhenOff() {
        controller.codeMode = false
        type("(")
        assertEquals("(|", field())
        controller.codeMode = true
        controller.settings = controller.settings.copy(pairBrackets = false)
        type("[")
        assertEquals("([|", field())
    }
}
