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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/**
 * The text side of glide: the preview row (staging, re-reading staged words, selecting a word and gliding
 * or typing it again, alternatives, backspace), direct commits when the row is off (spacing, backspace,
 * strip swaps), and what is learned when. Words in the field are never rewritten.
 */
class GlideCommitTest {
    private val dictionary get() = NgramModelTest.dictionary
    private val lm get() = NgramModelTest.lm
    private val ic = FakeInputConnection()
    private var strip: List<String> = emptyList()
    private var shownStaging: List<String> = emptyList()
    private var shownAlternatives: List<String> = emptyList()

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
            override fun showStaging(words: List<String>, selected: Int, previewStart: Int, previewCount: Int, alternatives: List<String>) {
                shownStaging = words
                shownAlternatives = alternatives
            }
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
    ).also { it.startInput(FieldInfo.from(InputType.TYPE_CLASS_TEXT, 0)) }

    private fun idx(w: String) = dictionary.indexOfLower(w).also { assertTrue("$w missing", it >= 0) }

    private fun word(w: String, vararg others: String) =
        GlideWord(idx(w), (listOf(w) + others).map { idx(it) }.toIntArray(), FloatArray(others.size + 1) { it.toFloat() })

    private val stroke = floatArrayOf(1f, 2f, 3f, 4f)
    private val obs = floatArrayOf(7f, 0.1f, -0.1f)

    /**
     * A decoded glide of [words]; [history] is how the words passed for revision should read, changing from
     * [firstRevised]; [runnersUp] are the last word's other candidates.
     */
    private fun result(
        words: List<String>,
        runnersUp: List<String> = emptyList(),
        history: List<String> = emptyList(),
        firstRevised: Int = -1,
    ) = GlideResult(
        words.map { idx(it) }.toIntArray(),
        words.map { stroke },
        words.map { obs },
        words.mapIndexed { i, w -> if (i == words.size - 1) word(w, *runnersUp.toTypedArray()) else word(w) },
        (words.takeLast(1) + runnersUp).map { idx(it) }.toIntArray(),
        history.map { idx(it) }.toIntArray(),
        firstRevised,
        10,
    )

    private fun glide(r: GlideResult, capitalize: Boolean = false, trailingSpace: Boolean = false) {
        val ctx = controller.glideContext(dictionary, lm, revise = true)
        assertEquals("words passed for revision", ctx.history.size, r.history.size)
        controller.commitGlide(r, dictionary, capitalize, trailingSpace)
    }

    private fun direct() {
        controller.settings = controller.settings.copy(glidePreview = false)
    }

    // ---- The preview row ------------------------------------------------------------------------------

    @Test
    fun glidesWaitInThePreviewRowThenGoIn() {
        glide(result(listOf("hello")), capitalize = true)
        assertEquals("", ic.toString())
        assertEquals(listOf("Hello"), controller.stagedWords)
        glide(result(listOf("world"), history = listOf("hello")))
        assertEquals(listOf("Hello", "world"), shownStaging)
        assertEquals("", ic.toString())
        controller.flushStaging()
        assertEquals("Hello world", ic.toString())
        assertTrue(shownStaging.isEmpty())
        assertEquals(listOf(Triple("Hello", null, true), Triple("world", "hello", false)), learned)
        assertEquals(2, glidesLearned.size)
    }

    @Test
    fun stagedWordsAreReReadButFieldWordsNeverAre() {
        glide(result(listOf("if")), capitalize = true)
        glide(result(listOf("course"), history = listOf("of"), firstRevised = 0))
        assertEquals(listOf("Of", "course"), controller.stagedWords)
        controller.flushStaging()
        assertEquals("Of course", ic.toString())
        // In the field now: the next glide gets the word before it as context and nothing to revise.
        val ctx = controller.glideContext(dictionary, lm, revise = true)
        assertTrue(ctx.history.isEmpty())
        assertEquals(lm.contextOf(idx("course")), ctx.context)
        controller.commitGlide(result(listOf("not")), dictionary, false, false)
        controller.flushStaging()
        assertEquals("Of course not", ic.toString())
    }

    @Test
    fun contextComesFromTheFieldBeforeTheStagedWords() {
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
    fun tappingAWordThenGlidingReplacesItAndTheNextGlideAppends() {
        glide(result(listOf("hello")))
        glide(result(listOf("world"), history = listOf("hello")))
        controller.selectStaged(1)
        // The replacement is decoded after the word before it, with nothing to revise.
        val ctx = controller.glideContext(dictionary, lm, revise = true)
        assertTrue(ctx.history.isEmpty())
        assertEquals(lm.contextOf(idx("hello")), ctx.context)
        controller.commitGlide(result(listOf("would")), dictionary, false, false)
        assertEquals(listOf("hello", "would"), controller.stagedWords)
        assertEquals(listOf(idx("would")), corrections.map { it.second })
        assertTrue(corrections[0].first === stroke)
        glide(result(listOf("again"), history = listOf("hello", "would")))
        assertEquals(listOf("hello", "would", "again"), controller.stagedWords)
    }

    @Test
    fun aReplacedWordKeepsItsCapital() {
        glide(result(listOf("hello")), capitalize = true)
        controller.selectStaged(0)
        controller.commitGlide(result(listOf("help")), dictionary, false, false)
        assertEquals(listOf("Help"), controller.stagedWords)
    }

    @Test
    fun pickingAnAlternativeInTheRow() {
        glide(result(listOf("world"), runnersUp = listOf("would", "wood")))
        controller.selectStaged(0)
        assertEquals(listOf("would", "wood"), shownAlternatives)
        controller.pickStagedAlternative(0)
        assertEquals(listOf("would"), controller.stagedWords)
        assertEquals(listOf(idx("would")), corrections.map { it.second })
        // Settled: later glides use it as context but never re-read it.
        assertTrue(controller.glideContext(dictionary, lm, revise = true).history.single().locked)
        controller.flushStaging()
        // A corrected word teaches the adaptation through the correction, not as a kept glide.
        assertTrue(glidesLearned.isEmpty())
        assertEquals(listOf(Triple("would", null, true)), learned)
    }

    @Test
    fun typingLettersReplacesTheSelectedWord() {
        glide(result(listOf("hello")))
        glide(result(listOf("world"), history = listOf("hello")))
        controller.selectStaged(1)
        for (c in "wird") controller.typeText(c.toString())
        assertEquals(listOf("hello", "wird"), shownStaging)
        repeat(3) { controller.backspace() }
        for (c in "ord") controller.typeText(c.toString())
        assertEquals(listOf("hello", "word"), shownStaging)
        assertEquals("", ic.toString())
        controller.space()
        // Space confirms the replacement; the row stays up and nothing reaches the field yet.
        assertEquals(listOf("hello", "word"), controller.stagedWords)
        assertEquals("", ic.toString())
        assertEquals(listOf(idx("word")), corrections.map { it.second })
        assertTrue(controller.glideContext(dictionary, lm, revise = true).history.last().locked)
        controller.flushStaging()
        assertEquals("hello word", ic.toString())
        assertEquals(Triple("word", "hello", false), learned.last())
    }

    @Test
    fun typedReplacementKeepsTheCapitalAndATapConfirmsIt() {
        glide(result(listOf("hello")), capitalize = true)
        controller.selectStaged(0)
        for (c in "hi") controller.typeText(c.toString())
        controller.selectStaged(0)
        assertEquals(listOf("Hi"), controller.stagedWords)
    }

    @Test
    fun aRetypedWordOutsideTheDictionaryCutsTheRevisableRun() {
        glide(result(listOf("hello")))
        controller.selectStaged(0)
        for (c in "zqxv") controller.typeText(c.toString())
        controller.space()
        assertEquals(listOf("zqxv"), controller.stagedWords)
        assertEquals(listOf(-1), corrections.map { it.second })
        val ctx = controller.glideContext(dictionary, lm, revise = true)
        assertTrue(ctx.history.isEmpty())
        assertEquals(NgramModel.UNKNOWN, ctx.context)
        glide(result(listOf("again")))
        assertEquals(listOf("zqxv", "again"), controller.stagedWords)
        assertEquals(NgramModel.UNKNOWN, controller.glideContext(dictionary, lm, revise = true).context)
    }

    @Test
    fun lettersTypedForASelectedWordCountWhenOtherInputFlushes() {
        glide(result(listOf("hello")))
        controller.selectStaged(0)
        for (c in "help") controller.typeText(c.toString())
        controller.typeText(",")
        assertEquals("help,", ic.toString())
    }

    @Test
    fun backspaceInTheRow() {
        glide(result(listOf("hello")))
        glide(result(listOf("world"), history = listOf("hello")))
        controller.backspace()
        assertEquals(listOf("hello"), controller.stagedWords)
        glide(result(listOf("there"), history = listOf("hello")))
        controller.selectStaged(0)
        controller.backspace()
        assertEquals(listOf("there"), controller.stagedWords)
        controller.flushStaging()
        assertEquals("there", ic.toString())
        // The remaining word is learned after what now stands before it.
        assertEquals(listOf(Triple("there", null, true)), learned)
    }

    @Test
    fun typingFlushesTheRowFirst() {
        glide(result(listOf("hello")))
        controller.typeText(",")
        assertEquals("hello,", ic.toString())
        assertTrue(controller.stagedWords.isEmpty())
    }

    @Test
    fun aLateCursorReportForTheRowDoesNotSplitTheNextWord() {
        glide(result(listOf("again")))
        controller.typeText("s")
        assertEquals("agains", ic.toString())
        // The field reports the row's insert only now: cursor after "again", no composing region yet.
        controller.onSelectionChanged(5, 5, -1, -1)
        for (c in "oon") controller.typeText(c.toString())
        controller.space()
        assertEquals("againsoon ", ic.toString())
        // One word composed from its first letter: nothing split off as "oon".
        assertEquals(listOf("again", "soon"), learned.map { it.first })
    }

    @Test
    fun spaceFlushesTheRow() {
        glide(result(listOf("hello")))
        controller.space()
        assertEquals("hello ", ic.toString())
    }

    // ---- Straight into the field (preview row off) ---------------------------------------------------

    @Test
    fun smartSpacing() {
        direct()
        glide(result(listOf("hello")))
        assertEquals("hello", ic.toString())
        glide(result(listOf("world")))
        assertEquals("hello world", ic.toString())
        controller.space()
        glide(result(listOf("again")))
        assertEquals("hello world again", ic.toString())
    }

    @Test
    fun directGlidesNeverRewriteTheField() {
        direct()
        glide(result(listOf("if")), capitalize = true)
        val ctx = controller.glideContext(dictionary, lm, revise = true)
        assertTrue(ctx.history.isEmpty())
        assertEquals(lm.contextOf(idx("if")), ctx.context)
        // Even a result claiming a revision leaves the field alone.
        controller.commitGlide(result(listOf("course"), history = listOf("of"), firstRevised = 0), dictionary, false, false)
        assertEquals("If course", ic.toString())
    }

    @Test
    fun backspaceRemovesTheWholePhraseUnlearned() {
        direct()
        ic.commitText("Say ", 1)
        glide(result(listOf("hello", "world")))
        assertEquals("Say hello world", ic.toString())
        controller.backspace()
        assertEquals("Say ", ic.toString())
        controller.typeText("x")
        assertTrue(learned.isEmpty())
    }

    @Test
    fun trailingSpaceFromADipIsPartOfTheGlide() {
        direct()
        glide(result(listOf("hello")), trailingSpace = true)
        assertEquals("hello ", ic.toString())
        glide(result(listOf("world")))
        assertEquals("hello world", ic.toString())
    }

    @Test
    fun pickingAStripAlternativeSwapsTheLastWordAsACorrection() {
        direct()
        glide(result(listOf("hello")))
        glide(result(listOf("world"), runnersUp = listOf("would")))
        controller.pickCandidate("would")
        assertEquals("hello would", ic.toString())
        assertEquals(listOf(idx("would")), corrections.map { it.second })
        controller.typeText(".")
        assertEquals(listOf("hello", "would"), learned.map { it.first })
        // Only the glide kept as it was teaches the adaptation.
        assertEquals(1, glidesLearned.size)
    }

    @Test
    fun aDirectGlideIsLearnedOnceTheNextThingHappens() {
        direct()
        glide(result(listOf("hello")))
        assertTrue(learned.isEmpty())
        glide(result(listOf("world")))
        assertEquals(listOf("hello"), learned.map { it.first })
        controller.typeText(",")
        assertEquals(listOf(Triple("hello", null, true), Triple("world", "hello", false)), learned)
        assertEquals(NgramModel.SENTENCE_START, run {
            ic.commitText(". ", 1)
            controller.glideContext(dictionary, lm, revise = true).context
        })
    }

    // ---- Fields that never teach ----------------------------------------------------------------------

    @Test
    fun noLearningWhereTheAppAsksForNone() {
        controller.startInput(FieldInfo.from(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING))
        glide(result(listOf("hello")))
        controller.flushStaging()
        controller.space()
        for (c in "there") controller.typeText(c.toString())
        controller.space()
        assertEquals("hello there ", ic.toString())
        assertTrue(learned.isEmpty())
        assertTrue(glidesLearned.isEmpty())
    }

    @Test
    fun noLearningInEmailFields() {
        controller.startInput(FieldInfo.from(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, 0))
        for (c in "someone") controller.typeText(c.toString())
        controller.space()
        assertTrue(learned.isEmpty())
    }
}
