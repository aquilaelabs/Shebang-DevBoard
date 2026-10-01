package dev.shebang.devboard.ime

import android.os.Handler
import android.text.InputType
import android.view.inputmethod.EditorInfo
import dev.shebang.devboard.dict.Dictionary
import dev.shebang.devboard.dict.NgramModel
import dev.shebang.devboard.dict.NgramModelTest
import dev.shebang.devboard.glide.GlideResult
import dev.shebang.devboard.glide.GlideWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/**
 * The text side of glide, in the field: spacing, typing after a glide, redoing a word the user points at
 * (a cursor inside it, or a selection) by gliding or picking from the strip, adding a word at a word's edge
 * or after space, backspace and strip swaps right after a glide, and what is learned when.
 */
class GlideCommitTest {
    private val dictionary get() = NgramModelTest.dictionary
    private val lm get() = NgramModelTest.lm
    private val ic = FakeInputConnection()
    private var strip: List<String> = emptyList()
    private var now = 10_000L

    private val learned = ArrayList<Triple<String, String?, Boolean>>()
    private val glidesLearned = ArrayList<FloatArray>()
    private val corrections = ArrayList<Pair<FloatArray?, Int>>()

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
        object : TextInputController.Learner {
            override fun learnWord(word: String, previous: String?, sentenceStart: Boolean) {
                learned += Triple(word, previous, sentenceStart)
            }
            override fun learnGlide(observations: FloatArray) {
                glidesLearned += observations
            }
            override fun correction(stroke: FloatArray?, word: Int, dictionary: Dictionary) {
                corrections += stroke to word
            }
        },
    ).also {
        it.clock = { now }
        it.startInput(FieldInfo.from(InputType.TYPE_CLASS_TEXT, 0))
    }

    private fun idx(w: String) = dictionary.indexOfLower(w).also { assertTrue("$w missing", it >= 0) }

    private fun word(w: String, vararg others: String) =
        GlideWord(idx(w), (listOf(w) + others).map { idx(it) }.toIntArray(), FloatArray(others.size + 1) { it.toFloat() })

    /** Each glide gets its own stroke, so a correction can be traced to the word it came from. */
    private val strokes = HashMap<String, FloatArray>()
    private val obs = floatArrayOf(7f, 0.1f, -0.1f)

    private fun result(words: List<String>, runnersUp: List<String> = emptyList()) = GlideResult(
        words.map { idx(it) }.toIntArray(),
        words.map { w -> strokes.getOrPut(w) { floatArrayOf(w.length.toFloat(), 1f) } },
        words.map { obs },
        words.mapIndexed { i, w -> if (i == words.size - 1) word(w, *runnersUp.toTypedArray()) else word(w) },
        (words.takeLast(1) + runnersUp).map { idx(it) }.toIntArray(),
        IntArray(0),
        -1,
        10,
    )

    /** Glides [words] the way the service does: context first, then the result. */
    private fun glide(vararg words: String, capitalize: Boolean = false, trailingSpace: Boolean = false, runnersUp: List<String> = emptyList()) {
        // The decoder's result keeps any word before as it stands (FixPreviousGlideTest covers fixing it).
        controller.glideContext(dictionary, lm)
        controller.commitGlide(result(words.toList(), runnersUp), dictionary, capitalize, trailingSpace)
    }

    private fun type(s: String) {
        for (c in s) if (c == ' ') controller.space() else controller.typeText(c.toString())
    }

    /** The user taps at [at] a while after the keyboard's last edit. */
    private fun userMovesCursor(at: Int) {
        now += 2_000
        val old = ic.cursor
        ic.setSelection(at, at)
        controller.onSelectionChanged(old, old, at, at, -1, -1)
    }

    /** The user selects [start] to [end] (a double tap on a word). */
    private fun userSelects(start: Int, end: Int) {
        now += 2_000
        val old = ic.cursor
        ic.setSelection(start, end)
        controller.onSelectionChanged(old, old, start, end, -1, -1)
    }

    // ---- Gliding and typing ---------------------------------------------------------------------------

    @Test
    fun glidesGoStraightInWithSpacesBetween() {
        glide("hello", capitalize = true)
        assertEquals("Hello", ic.toString())
        glide("world")
        assertEquals("Hello world", ic.toString())
        controller.space()
        glide("again")
        assertEquals("Hello world again", ic.toString())
    }

    @Test
    fun aLetterRightAfterAGlideStartsANewWord() {
        glide("hello")
        type("i")
        assertEquals("hello i", ic.toString())
        type(" ")
        // The pronoun gets its capital as the word ends.
        assertEquals("hello I ", ic.toString())
        assertEquals(listOf("hello", "I"), learned.map { it.first })
    }

    @Test
    fun aGlideRightAfterTypingIsAWordOfItsOwn() {
        type("kub")
        glide("world")
        assertEquals("kub world", ic.toString())
    }

    @Test
    fun theKeyboardsOwnCursorReportsNeverTargetAnything() {
        glide("hello")
        // The field reports the glide's insert: the cursor sits at the end of "hello".
        controller.onSelectionChanged(0, 0, 5, 5, -1, -1)
        assertNull(controller.targetText)
        glide("world")
        assertEquals("hello world", ic.toString())
    }

    // ---- Redoing a word ------------------------------------------------------------------------------

    @Test
    fun tappingInsideAWordThenGlidingReplacesIt() {
        glide("hello")
        glide("world")
        glide("again")
        assertEquals("hello world again", ic.toString())
        userMovesCursor(8)
        assertEquals("world", controller.targetText)
        assertEquals(TextInputController.arrangeBestMiddle(listOf("world")), strip)
        // The replacement is decoded after the word before it.
        assertEquals(lm.contextOf(idx("hello")), controller.glideContext(dictionary, lm).context)
        controller.commitGlide(result(listOf("would")), dictionary, false, false)
        assertEquals("hello would again", ic.toString())
        assertEquals(11, ic.cursor)
        assertNull(controller.targetText)
        // A correction, with the word's own earlier stroke to re-align.
        assertEquals(listOf(idx("would")), corrections.map { it.second })
        assertTrue(corrections[0].first === strokes["world"])
    }

    @Test
    fun aQuickTapInsideAWordStillRedoesItInsteadOfSplittingIt() {
        glide("hello")
        glide("world")
        // The tap comes so soon after the glide that its cursor report looks like the keyboard's own.
        val old = ic.cursor
        ic.setSelection(8, 8)
        controller.onSelectionChanged(old, old, 8, 8, -1, -1)
        assertNull(controller.targetText)
        glide("would")
        assertEquals("hello would", ic.toString())
    }

    @Test
    fun aStaleTargetNeverCatchesAGlideAfterAQuickTapElsewhere() {
        glide("hello")
        glide("world")
        userMovesCursor(2)
        assertEquals("hello", controller.targetText)
        // A quick second tap, into "world", outruns its report: the glide goes where the cursor is.
        ic.setSelection(8, 8)
        glide("would")
        assertEquals("hello would", ic.toString())
    }

    @Test
    fun backspaceAfterAQuickTapAwayDeletesOneCharacter() {
        glide("hello")
        glide("world")
        ic.setSelection(5, 5)
        controller.backspace()
        assertEquals("hell world", ic.toString())
    }

    @Test
    fun aGlideRedoneRightAwayIsNeverLearned() {
        glide("hello")
        glide("world")
        userMovesCursor(8)
        glide("would")
        type(" ")
        assertEquals(listOf("hello", "would"), learned.map { it.first })
        // Its stroke was not kept as a good glide either; the correction teaches instead.
        assertEquals(2, glidesLearned.size)
    }

    @Test
    fun aReplacedWordKeepsItsCapital() {
        glide("hello", capitalize = true)
        userMovesCursor(2)
        glide("help")
        assertEquals("Help", ic.toString())
    }

    @Test
    fun aCapitalThatBelongsToTheOldWordIsNotCopied() {
        ic.commitText("It is within the Borough ", 1)
        glide("i'd")
        assertEquals("It is within the Borough I'd", ic.toString())
        userMovesCursor(ic.toString().length - 2)
        glide("of")
        assertEquals("It is within the Borough of", ic.toString())
    }

    @Test
    fun aCapitalTheUserGaveIsKept() {
        glide("hello", capitalize = true)
        glide("world", capitalize = true)
        // "World" is normally lowercase: the user shifted it, so its replacement is capitalised too.
        userMovesCursor(8)
        glide("would")
        assertEquals("Hello Would", ic.toString())
        // At a sentence start the capital is the sentence's.
        userMovesCursor(2)
        glide("help")
        assertEquals("Help Would", ic.toString())
    }

    @Test
    fun aDoubleTappedWordIsReplacedToo() {
        glide("hello")
        glide("world")
        userSelects(6, 11)
        assertEquals("world", controller.targetText)
        glide("would")
        assertEquals("hello would", ic.toString())
        assertEquals(listOf(idx("would")), corrections.map { it.second })
    }

    @Test
    fun aTappedGlidedWordOffersItsRunnersUp() {
        glide("world", runnersUp = listOf("would", "wood"))
        type(" ")
        userMovesCursor(2)
        assertEquals(listOf("would", "world", "wood"), strip)
        controller.pickCandidate("would")
        assertEquals("would ", ic.toString())
        assertEquals(listOf(idx("would")), corrections.map { it.second })
        assertEquals("would", learned.last().first)
    }

    @Test
    fun pickingTheTargetItselfChangesNothing() {
        glide("hello")
        userMovesCursor(2)
        controller.pickCandidate("hello")
        assertEquals("hello", ic.toString())
        assertNull(controller.targetText)
        assertTrue(corrections.isEmpty())
    }

    @Test
    fun theSameWordGlidedAgainIsNoCorrection() {
        glide("hello")
        userMovesCursor(2)
        glide("hello")
        assertEquals("hello", ic.toString())
        assertTrue(corrections.isEmpty())
    }

    // ---- Walking back with backspace -----------------------------------------------------------------

    @Test
    fun backspacingIntoAGlidedWordOffersItsRunnersUp() {
        glide("hello")
        glide("world", runnersUp = listOf("would", "wood"))
        type(" ")
        controller.backspace()
        assertEquals("hello world", ic.toString())
        assertEquals(listOf("would", "world", "wood"), strip)
        controller.pickCandidate("would")
        assertEquals("hello would ", ic.toString())
        assertEquals(listOf(idx("would")), corrections.map { it.second })
        assertTrue(corrections[0].first === strokes["world"])
    }

    @Test
    fun aGlideRedoesAWordBackspaceReopened() {
        glide("hello", capitalize = true)
        glide("world")
        type(" ")
        controller.backspace()
        glide("would")
        assertEquals("Hello would", ic.toString())
        assertEquals(listOf(idx("would")), corrections.map { it.second })
        // And the next glide goes after it.
        glide("again")
        assertEquals("Hello would again", ic.toString())
    }

    @Test
    fun backspaceWalksBackThroughWords() {
        type("the quikc brown ")
        controller.backspace()
        assertEquals("the quikc brown", ic.toString())
        repeat(5) { controller.backspace() }
        assertEquals("the quikc ", ic.toString())
        controller.backspace()
        assertEquals("the quikc", ic.toString())
        // "quikc" is the composing word again: typing corrects it in place.
        repeat(2) { controller.backspace() }
        type("ck ")
        assertEquals("the quick ", ic.toString())
    }

    @Test
    fun spaceAfterReopeningChangesNothing() {
        type("hello world ")
        val learnedBefore = learned.size
        controller.backspace()
        type(" ")
        assertEquals("hello world ", ic.toString())
        assertEquals(learnedBefore, learned.size)
    }

    // ---- Adding a word -------------------------------------------------------------------------------

    @Test
    fun aCursorAtAWordsEdgeAddsAWord() {
        glide("hello")
        glide("world")
        // A tap between the words lands at the end of "hello" or the start of "world": nothing is targeted.
        userMovesCursor(5)
        assertNull(controller.targetText)
        glide("big")
        assertEquals("hello big world", ic.toString())
        userMovesCursor(10)
        assertNull(controller.targetText)
        glide("bad")
        assertEquals("hello big bad world", ic.toString())
    }

    @Test
    fun spaceWithAWordTargetedMovesPastItToAddAWord() {
        glide("hello")
        glide("world")
        userMovesCursor(2)
        assertEquals("hello", controller.targetText)
        controller.space()
        assertNull(controller.targetText)
        assertEquals(6, ic.cursor)
        glide("big")
        assertEquals("hello big world", ic.toString())
    }

    @Test
    fun spaceAfterTheLastWordAddsTheSpace() {
        glide("hello")
        userMovesCursor(2)
        controller.space()
        assertEquals("hello ", ic.toString())
        glide("world")
        assertEquals("hello world", ic.toString())
    }

    @Test
    fun typingWithAWordTargetedIsOrdinaryTyping() {
        glide("hello")
        glide("world")
        userMovesCursor(8)
        type("x")
        assertNull(controller.targetText)
        assertEquals("hello woxrld", ic.toString())
    }

    // ---- Right after a glide -------------------------------------------------------------------------

    @Test
    fun backspaceRemovesTheWholeGlideUnlearned() {
        ic.commitText("Say ", 1)
        glide("hello", "world")
        assertEquals("Say hello world", ic.toString())
        controller.backspace()
        assertEquals("Say ", ic.toString())
        controller.typeText("x")
        assertTrue(learned.none { it.first == "hello" || it.first == "world" })
    }

    @Test
    fun trailingSpaceFromADipIsPartOfTheGlide() {
        glide("hello", trailingSpace = true)
        assertEquals("hello ", ic.toString())
        glide("world")
        assertEquals("hello world", ic.toString())
    }

    @Test
    fun pickingAStripAlternativeSwapsTheLastWordAsACorrection() {
        glide("hello")
        glide("world", runnersUp = listOf("would"))
        controller.pickCandidate("would")
        assertEquals("hello would", ic.toString())
        assertEquals(listOf(idx("would")), corrections.map { it.second })
        controller.typeText(".")
        assertEquals(listOf("hello", "would"), learned.map { it.first })
        // Only the glide kept as it was teaches the adaptation.
        assertEquals(1, glidesLearned.size)
    }

    @Test
    fun aGlideIsLearnedOnceTheNextThingHappens() {
        glide("hello")
        assertTrue(learned.isEmpty())
        glide("world")
        assertEquals(listOf("hello"), learned.map { it.first })
        controller.typeText(",")
        assertEquals(listOf(Triple("hello", null, true), Triple("world", "hello", false)), learned)
        assertEquals(NgramModel.SENTENCE_START, run {
            ic.commitText(". ", 1)
            controller.glideContext(dictionary, lm).context
        })
    }

    @Test
    fun contextIsTheWordBeforeTheCursor() {
        ic.commitText("It is out ", 1)
        assertEquals(lm.contextOf(idx("out")), controller.glideContext(dictionary, lm).context)
    }

    // ---- Fields that never teach ----------------------------------------------------------------------

    @Test
    fun noLearningWhereTheAppAsksForNone() {
        controller.startInput(FieldInfo.from(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))
        glide("hello")
        type(" there ")
        assertEquals("hello there ", ic.toString())
        assertTrue(learned.isEmpty())
        assertTrue(glidesLearned.isEmpty())
    }

    @Test
    fun noLearningInEmailFields() {
        controller.startInput(FieldInfo.from(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, 0))
        type("someone ")
        assertTrue(learned.isEmpty())
    }
}
