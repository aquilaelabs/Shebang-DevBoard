package dev.shebang.devboard.ime

import android.os.Handler
import android.text.InputType
import dev.shebang.devboard.dict.Dictionary
import dev.shebang.devboard.dict.NgramModelTest
import dev.shebang.devboard.glide.GlideContext
import dev.shebang.devboard.glide.GlideResult
import dev.shebang.devboard.glide.GlideWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/**
 * A glide fixing the glided word right before it: only that word, only while it stands as it went in,
 * never one picked from the strip, and learned as it ends up.
 */
class FixPreviousGlideTest {
    private val dictionary get() = NgramModelTest.dictionary
    private val lm get() = NgramModelTest.lm
    private val ic = FakeInputConnection()
    private var strip: List<String> = emptyList()
    private val learned = ArrayList<String>()
    private val glidesLearned = ArrayList<FloatArray>()
    private var now = 10_000L

    private val controller = TextInputController(
        { ic },
        object : TextInputController.Ui {
            override fun showCandidates(words: List<String>) { strip = words }
            override fun setComposing(composing: Boolean) = Unit
        },
        Executor { it.run() },
        Handler(),
        object : TextInputController.Learner {
            override fun learnWord(word: String, previous: String?, sentenceStart: Boolean) { learned += word }
            override fun learnGlide(observations: FloatArray) { glidesLearned += observations }
            override fun correction(stroke: FloatArray?, word: Int, dictionary: Dictionary) = Unit
        },
        postToMain = { it.run() },
    ).also {
        it.clock = { now }
        it.startInput(FieldInfo.from(InputType.TYPE_CLASS_TEXT, 0))
        it.predictionModel = dictionary to lm
    }

    private fun idx(w: String) = dictionary.indexOfLower(w).also { assertTrue("$w missing", it >= 0) }

    /** A glide of [word] (runners-up [others]); when the context carries history, it is read as [revised]. */
    private fun glide(word: String, others: List<String> = emptyList(), revised: String? = null, capitalize: Boolean = false): GlideContext {
        val ctx = controller.glideContext(dictionary, lm)
        val cands = (listOf(word) + others).map { idx(it) }.toIntArray()
        val entry = GlideWord(idx(word), cands, FloatArray(cands.size))
        val history = ctx.history.map { h -> if (revised != null) idx(revised) else h.word }.toIntArray()
        val first = if (revised != null && ctx.history.isNotEmpty() && history[0] != ctx.history[0].word) 0 else -1
        controller.commitGlide(
            GlideResult(intArrayOf(idx(word)), listOf(null), listOf(floatArrayOf(1f)), listOf(entry), cands, history, first, 5),
            dictionary, capitalize, false,
        )
        return ctx
    }

    @Test
    fun theNextGlideFixesTheOneBefore() {
        glide("form", listOf("from"))
        val ctx = glide("the", revised = "from")
        assertEquals(1, ctx.history.size)
        assertEquals("from the", ic.toString())
    }

    @Test
    fun aPairPeopleDoNotWriteNeverReplacesTheWordBefore() {
        // Seen on a friend's phone: "to work" became "to dirk" when "pretty" was glided next, because after a
        // word as rare as "dirk" any next word looks as likely as anywhere.
        type("it seems to ")
        glide("work", listOf("dirk", "return"))
        glide("pretty", revised = "dirk")
        assertEquals("it seems to work pretty", ic.toString())
    }

    private fun type(s: String) {
        for (c in s) if (c == ' ') controller.space() else controller.typeText(c.toString())
    }

    @Test
    fun aCapitalIsKept() {
        glide("form", listOf("from"), capitalize = true)
        glide("the", revised = "from")
        assertEquals("From the", ic.toString())
    }

    @Test
    fun theFixedWordIsLearnedAsItReadsWithoutItsStrokeOffsets() {
        glide("form", listOf("from"))
        glide("the", revised = "from")
        controller.space()
        controller.startInput(FieldInfo.from(android.text.InputType.TYPE_CLASS_TEXT, 0))
        assertEquals(listOf("from", "the"), learned)
        // Only the new glide's offsets: the fixed word's were measured against "form".
        assertEquals(1, glidesLearned.size)
    }

    @Test
    fun tappingTheFixedWordOffersTheOldOneBack() {
        glide("form", listOf("from"))
        glide("the", revised = "from")
        controller.space()
        // Later, the user puts the cursor inside "from".
        now += 2000
        ic.cursor = 2
        controller.onSelectionChanged(9, 9, 2, 2, -1, -1)
        assertTrue("strip $strip", strip.any { it.equals("form", ignoreCase = true) })
    }

    @Test
    fun aWordPickedFromTheStripIsNotOffered() {
        glide("form", listOf("from"))
        controller.pickCandidate("from")
        val ctx = glide("the")
        assertTrue(ctx.history.isEmpty())
    }

    @Test
    fun nothingIsOfferedOnceSomethingWasTypedAfter() {
        glide("form", listOf("from"))
        controller.typeText(",")
        val ctx = glide("the")
        assertTrue(ctx.history.isEmpty())
        assertEquals("form, the", ic.toString())
    }

    @Test
    fun theSettingTurnsItOff() {
        controller.settings = controller.settings.copy(fixPreviousGlide = false)
        glide("form", listOf("from"))
        val ctx = glide("the")
        assertTrue(ctx.history.isEmpty())
        assertEquals("form the", ic.toString())
    }
}
