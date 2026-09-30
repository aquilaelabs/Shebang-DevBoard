package dev.shebang.devboard.ime

import android.os.Handler
import dev.shebang.devboard.dict.NgramModel
import dev.shebang.devboard.dict.NgramModelTest
import dev.shebang.devboard.glide.GlideResult
import dev.shebang.devboard.glide.GlideWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/** The text side of glide: spacing, rewriting recent glided words, backspace and strip swaps. */
class GlideCommitTest {
    private val dictionary get() = NgramModelTest.dictionary
    private val lm get() = NgramModelTest.lm
    private val ic = FakeInputConnection()
    private var strip: List<String> = emptyList()
    private val controller = TextInputController(
        { ic },
        object : TextInputController.Ui {
            override fun showCandidates(words: List<String>) {
                strip = words
            }
            override fun setComposing(composing: Boolean) = Unit
        },
        Executor { it.run() },
        Handler(),
    ).also { it.startInput(FieldInfo.from(android.text.InputType.TYPE_CLASS_TEXT, 0)) }

    private fun idx(w: String) = dictionary.indexOfLower(w)

    private fun word(w: String, vararg others: String) =
        GlideWord(idx(w), (listOf(w) + others).map { idx(it) }.toIntArray(), FloatArray(others.size + 1) { it.toFloat() })

    /** A decoded glide of [words]; [history] rewrites the recent glided words from [firstRevised]. */
    private fun result(words: List<String>, alternatives: List<String> = words.takeLast(1), history: List<String> = emptyList(), firstRevised: Int = -1) =
        GlideResult(
            words.map { idx(it) }.toIntArray(),
            words.map { word(it) },
            alternatives.map { idx(it) }.toIntArray(),
            history.map { idx(it) }.toIntArray(),
            firstRevised,
            10,
        )

    private fun glide(r: GlideResult, capitalize: Boolean = false, trailingSpace: Boolean = false) {
        val ctx = controller.glideContext(dictionary, lm, revise = true)
        assertEquals(ctx.history.size, r.history.size)
        controller.commitGlide(r, dictionary, capitalize, trailingSpace)
    }

    @Test
    fun smartSpacing() {
        glide(result(listOf("hello")))
        assertEquals("hello", ic.toString())
        glide(result(listOf("world"), history = listOf("hello")))
        assertEquals("hello world", ic.toString())
        controller.space()
        glide(result(listOf("again"), history = listOf("hello", "world")))
        assertEquals("hello world again", ic.toString())
    }

    @Test
    fun laterGlideRewritesEarlierWordsKeepingCase() {
        glide(result(listOf("if")), capitalize = true)
        assertEquals("If", ic.toString())
        glide(result(listOf("course"), history = listOf("of"), firstRevised = 0))
        assertEquals("Of course", ic.toString())
    }

    @Test
    fun rewritesSeveralWords() {
        glide(result(listOf("i")))
        glide(result(listOf("went"), history = listOf("i")))
        glide(result(listOf("too"), history = listOf("i", "went")))
        glide(result(listOf("the"), history = listOf("i", "went", "to"), firstRevised = 2))
        assertEquals("I went to the", ic.toString())
    }

    @Test
    fun noRewriteWhenTheTextChanged() {
        glide(result(listOf("if")))
        // Another app edits the field: the history no longer matches, so nothing is rewritten.
        ic.text.append("x")
        ic.cursor = ic.text.length
        val ctx = controller.glideContext(dictionary, lm, revise = true)
        assertTrue(ctx.history.isEmpty())
        controller.commitGlide(result(listOf("course"), history = emptyList()), dictionary, false, false)
        assertEquals("ifx course", ic.toString())
    }

    @Test
    fun contextReadsTheWordBeforeTheRun() {
        ic.commitText("It is out ", 1)
        glide(result(listOf("if")))
        val ctx = controller.glideContext(dictionary, lm, revise = true)
        assertEquals(1, ctx.history.size)
        assertEquals(lm.contextOf(idx("out")), ctx.context)
        val off = controller.glideContext(dictionary, lm, revise = false)
        assertTrue(off.history.isEmpty())
        assertEquals(lm.contextOf(idx("if")), off.context)
    }

    @Test
    fun backspaceRemovesTheWholePhrase() {
        ic.commitText("Say ", 1)
        glide(result(listOf("hello", "world")))
        assertEquals("Say hello world", ic.toString())
        controller.backspace()
        assertEquals("Say ", ic.toString())
    }

    @Test
    fun trailingSpaceFromADipIsPartOfTheGlide() {
        glide(result(listOf("hello")), trailingSpace = true)
        assertEquals("hello ", ic.toString())
        glide(result(listOf("world"), history = listOf("hello")))
        assertEquals("hello world", ic.toString())
    }

    @Test
    fun pickingAnAlternativeSwapsTheLastWordAndSettlesIt() {
        glide(result(listOf("hello")))
        glide(result(listOf("world"), alternatives = listOf("world", "would"), history = listOf("hello")))
        assertEquals(listOf("hello", "world"), listOf("hello", "world"))
        controller.pickCandidate("would")
        assertEquals("hello would", ic.toString())
        val ctx = controller.glideContext(dictionary, lm, revise = true)
        assertTrue(ctx.history.last().locked)
        assertEquals(idx("would"), ctx.history.last().word)
    }

    @Test
    fun typingEndsTheRun() {
        glide(result(listOf("hello")))
        controller.typeText(",")
        val ctx = controller.glideContext(dictionary, lm, revise = true)
        assertTrue(ctx.history.isEmpty())
        assertEquals(lm.contextOf(idx("hello")), ctx.context)
        assertEquals(NgramModel.SENTENCE_START, run {
            ic.commitText(". ", 1)
            controller.glideContext(dictionary, lm, revise = true).context
        })
    }
}
