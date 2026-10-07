package dev.shebang.devboard.ime

import android.os.Handler
import android.os.Looper
import android.text.InputType
import dev.shebang.devboard.dict.NgramModelTest
import dev.shebang.devboard.dict.Suggester
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.Executor

/** A word picked from the strip goes in with a space; punctuation typed next takes that space's place. */
class PickThenPunctuationTest {
    companion object {
        /** The word lists with every pack, as the keyboard has them by default, and the word model over them. */
        val packs by lazy { dev.shebang.devboard.dict.BuiltInWords.all() }
        val packsLm by lazy { java.io.File("src/main/assets/dict/en_ngrams.bin").inputStream().use { dev.shebang.devboard.dict.NgramModel.load(it, packs) } }
    }
    private val dictionary = packs
    private val lm = packsLm
    /** Names the user kept joined at a full stop. */
    private val kept = mutableSetOf<String>()
    private val ic = FakeInputConnection()
    private var strip: List<String> = emptyList()
    private val controller = TextInputController(
        { ic },
        object : TextInputController.Ui {
            override fun showCandidates(words: List<String>) { strip = words }
            override fun setComposing(composing: Boolean) = Unit
        },
        Executor { it.run() },
        Handler(Looper.getMainLooper()),
        object : TextInputController.Learner {
            override fun learnWord(word: String, previous: String?, sentenceStart: Boolean) = Unit
            override fun learnGlide(observations: FloatArray) = Unit
            override fun correction(stroke: FloatArray?, word: Int, dictionary: dev.shebang.devboard.dict.Dictionary) = Unit
            override fun keepJoined(word: String) { kept += word.lowercase() }
        },
        postToMain = { it.run() },
    ).also {
        it.knowsPersonalWord = { w -> w in kept }
        it.startInput(FieldInfo.from(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT, 0))
        it.suggester = Suggester(dictionary, null, FloatArray(dictionary.size) { i -> kotlin.math.exp(-lm.unigramCost(i).toDouble()).toFloat() }, lm)
        it.predictionModel = dictionary to lm
        it.settings = it.settings.copy(autocorrect = true, nextWord = true)
    }

    private fun type(s: String) {
        for (c in s) if (c == ' ') controller.space() else controller.typeText(c.toString())
    }

    /** Reports the cursor to the controller, as the app does after each edit. */
    private fun report() = controller.onSelectionChanged(ic.cursor, ic.cursor, ic.cursor, ic.cursor, -1, -1)

    @Test
    fun aTypedWordPickedThenAFullStop() {
        type("hel")
        controller.pickCandidate("hello")
        report()
        assertEquals("hello ", ic.toString())
        type(".")
        assertEquals("hello.", ic.toString().trimEnd())
    }

    @Test
    fun aPickWhoseCursorReportArrivesLateStillGivesUpItsSpace() {
        var now = 1_000L
        controller.clock = { now }
        type("hel")
        val before = ic.cursor
        controller.pickCandidate("hello")
        // A slow app reports the cursor move from the pick after the keyboard stopped expecting it.
        now += 2_000
        controller.onSelectionChanged(before, before, ic.cursor, ic.cursor, -1, -1)
        type(".")
        assertEquals("hello.", ic.toString().trimEnd())
    }

    @Test
    fun theSpaceOwedAfterThePunctuationSurvivesALateReportToo() {
        var now = 1_000L
        controller.clock = { now }
        type("hel")
        controller.pickCandidate("hello")
        type(".")
        // The full stop's own cursor report, late.
        now += 2_000
        controller.onSelectionChanged(ic.cursor - 1, ic.cursor - 1, ic.cursor, ic.cursor, -1, -1)
        type("w")
        assertEquals("hello. w", ic.toString())
    }

    @Test
    fun aRealCursorMoveStillKeepsTheSpace() {
        var now = 1_000L
        controller.clock = { now }
        type("hel")
        controller.pickCandidate("hello")
        report()
        // The user puts the cursor at the start of the field: a full stop there does not take the space.
        now += 2_000
        val end = ic.cursor
        ic.cursor = 0
        controller.onSelectionChanged(end, end, 0, 0, -1, -1)
        type(".")
        assertEquals(".hello ", ic.toString())
    }

    private fun idx(w: String) = dictionary.indexOfLower(w)

    private fun glide(w: String) {
        val r = dev.shebang.devboard.glide.GlideResult(
            intArrayOf(idx(w)), listOf(null), listOf(null),
            listOf(dev.shebang.devboard.glide.GlideWord(idx(w), intArrayOf(idx(w)), floatArrayOf(0f))),
            intArrayOf(idx(w)), IntArray(0), -1, 5,
        )
        controller.commitGlide(r, dictionary, false, false)
    }

    @Test
    fun spaceAfterThePunctuationGivesOneSpace() {
        type("hel")
        controller.pickCandidate("hello")
        type(". w")
        assertEquals("hello. w", ic.toString())
    }

    @Test
    fun aGlideAfterThePunctuationGetsOneSpace() {
        type("hel")
        controller.pickCandidate("hello")
        type(".")
        glide("world")
        assertEquals("hello. world", ic.toString().trimEnd())
    }

    private fun reset(inputType: Int = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT) {
        ic.finishComposingText(); ic.text.setLength(0); ic.cursor = 0
        controller.startInput(FieldInfo.from(inputType, 0))
    }

    @Test
    fun punctuationTypedStraightOntoAWordOwesTheSpaceToTheNextLetter() {
        assertEquals(listOf("hi, t", "yes! s", "fine? n", "note: t", "so; i"), listOf("hi,t", "yes!s", "fine?n", "note:t", "so;i").map {
            reset()
            type(it)
            ic.toString()
        })
    }

    @Test
    fun aFullStopTypedOntoAWordIsASentenceEndWhenTheNextWordIsAnEverydayOne() {
        type("hello.world ")
        assertEquals("hello. world ", ic.toString())
        // Where sentences begin with a capital, it gets one.
        reset(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)
        type("hello.world ")
        assertEquals("hello. World ", ic.toString())
    }

    @Test
    fun namesWithAFullStopStayAsTyped() {
        for (name in listOf("node.js", "Socket.io", "config.json", "example.com", "e.g.", "a.m.", "v1.2", "3.5")) {
            reset()
            type("$name ")
            assertEquals("$name ", ic.toString())
        }
    }

    @Test
    fun backspaceRightAfterTakesTheFullStopsSpaceBack() {
        type("hello.world ")
        controller.backspace()
        assertEquals("hello.world", ic.toString())
    }

    @Test
    fun abbreviationsWithFullStopsAreNeverAutocorrectedTo() {
        type("pm ")
        assertEquals("pm ", ic.toString())
        reset()
        type("ie ")
        assertEquals("ie ", ic.toString())
    }

    @Test
    fun anAcronymNoListKnowsIsSplitUntilKeptJoinedOnce() {
        type("n.m ")
        assertEquals("n. m ", ic.toString())
        // Backspace right after takes the space back, and the name is the user's from now on.
        controller.backspace()
        assertEquals("n.m", ic.toString())
        assertEquals(setOf("n.m"), kept)
        reset()
        type("n.m.i ")
        assertEquals("n.m.i ", ic.toString())
    }

    @Test
    fun aSpaceTypedAfterThePunctuationIsTheOnlyOne() {
        type("hello, w")
        assertEquals("hello, w", ic.toString())
        reset()
        type("hello. w")
        assertEquals("hello. w", ic.toString())
    }

    @Test
    fun aGlideAfterPunctuationTypedOntoAWordGetsOneSpace() {
        type("hello.")
        glide("world")
        assertEquals("hello. world", ic.toString().trimEnd())
    }

    @Test
    fun noSpaceOwedInCodeModeOrAnEmailField() {
        controller.codeMode = true
        type("a,b")
        assertEquals("a,b", ic.toString())
        controller.codeMode = false
        reset(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        type("sean.bowman ")
        assertEquals("sean.bowman", ic.toString().trimEnd())
    }

    @Test
    fun aSentenceEndWithItsSpaceOwedStartsASentence() {
        type("hello!")
        assertEquals(true, controller.sentenceStartOwed)
        type("w")
        assertEquals(false, controller.sentenceStartOwed)
        reset()
        type("well,")
        assertEquals(false, controller.sentenceStartOwed)
        // After a picked word, the full stop takes its space and owes it: a sentence starts next.
        reset()
        type("hel")
        controller.pickCandidate("hello")
        type(".")
        assertEquals(true, controller.sentenceStartOwed)
    }

    @Test
    fun aPredictedWordPickedThenAComma() {
        type("thank ")
        report()
        val next = strip.firstOrNull { it.isNotBlank() } ?: "you"
        controller.pickCandidate(next)
        report()
        type(",")
        assertEquals("thank $next,", ic.toString().trimEnd())
    }
}
