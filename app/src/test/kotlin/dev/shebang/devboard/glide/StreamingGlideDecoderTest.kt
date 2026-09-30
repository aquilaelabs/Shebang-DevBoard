package dev.shebang.devboard.glide

import dev.shebang.devboard.dict.NgramModel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingGlideDecoderTest {
    private val layout = GlideBenchmarkTest.layout
    private val dictionary get() = GlideBenchmarkTest.dictionary
    private val lm get() = GlideBenchmarkTest.lm
    private val decoder by lazy { StreamingGlideDecoder(GlideBenchmarkTest.language) }

    private fun idx(w: String) = dictionary.indexOfLower(w)

    /** Feeds the straight polyline through [letters]' key centres, 10 px steps, 10 ms apart. */
    private fun feedPath(letters: String, startMs: Long = 0L): Long {
        var t = startMs
        var px = Float.NaN
        var py = Float.NaN
        for (ch in letters) {
            val c = ch - 'a'
            val x = layout.centerX[c]
            val y = layout.centerY[c]
            if (px.isNaN()) {
                decoder.addPoint(x, y, t)
            } else {
                val steps = maxOf(1, (kotlin.math.hypot(x - px, y - py) / 10f).toInt())
                for (s in 1..steps) {
                    t += 10
                    decoder.addPoint(px + (x - px) * s / steps, py + (y - py) * s / steps, t)
                }
            }
            px = x
            py = y
        }
        return t
    }

    private fun decode(letters: String, ctx: GlideContext = GlideContext(NgramModel.UNKNOWN)): GlideResult {
        decoder.begin(layout, ctx, 0L)
        feedPath(letters)
        val r = decoder.finish()
        assertNotNull("no word for $letters", r)
        return r!!
    }

    private fun top(r: GlideResult) = dictionary.lower[r.words.last()]

    @Test
    fun idealPathsDecodeToTheirWords() {
        for (w in listOf("world", "keyboard", "terminal", "quick", "you", "the")) {
            assertEquals(w, top(decode(w)))
        }
        // Matching is loose enough for real fingers (tuned on real swipes) that, with no context, the more
        // common "help" edges out a perfect "hello"; it stays among the first alternatives.
        assertTrue(decode("hello").alternatives.take(3).map { dictionary.lower[it] }.contains("hello"))
    }

    @Test
    fun apostrophesComeFromTheDictionary() {
        assertEquals("don't", top(decode("dont")))
    }

    /** A stroke from halfway between i and o to f: "if" and "of" fit it equally. */
    private fun ambiguousIfOf(ctx: GlideContext): String {
        val i = 'i' - 'a'
        val o = 'o' - 'a'
        val f = 'f' - 'a'
        val sx = (layout.centerX[i] + layout.centerX[o]) / 2f
        val sy = layout.centerY[i]
        decoder.begin(layout, ctx, 0L)
        val steps = 30
        for (s in 0..steps) {
            decoder.addPoint(sx + (layout.centerX[f] - sx) * s / steps, sy + (layout.centerY[f] - sy) * s / steps, s * 10L)
        }
        return top(decoder.finish()!!)
    }

    @Test
    fun contextChoosesBetweenLookalikePaths() {
        assertEquals("of", ambiguousIfOf(GlideContext(lm.contextOf(idx("out")))))
        assertEquals("if", ambiguousIfOf(GlideContext(lm.contextOf(idx("what")))))
    }

    @Test
    fun aLaterGlideRevisesAnEarlierOne() {
        // "if" was committed, barely ahead of "of"; gliding "course" makes "of course" far more likely.
        val prev = GlideWord(idx("if"), intArrayOf(idx("if"), idx("of")), floatArrayOf(5.0f, 5.3f))
        val r = decode("course", GlideContext(NgramModel.SENTENCE_START, listOf(prev)))
        assertEquals("course", top(r))
        assertEquals(0, r.firstRevised)
        assertEquals("of", dictionary.lower[r.history[0]])
    }

    @Test
    fun lockedWordsAreNeverRevised() {
        val prev = GlideWord(idx("if"), intArrayOf(idx("if"), idx("of")), floatArrayOf(5.0f, 5.3f)).lockedAs(idx("if"))
        val r = decode("course", GlideContext(NgramModel.SENTENCE_START, listOf(prev)))
        assertEquals(-1, r.firstRevised)
        assertArrayEquals(intArrayOf(idx("if")), r.history)
    }

    @Test
    fun phraseStrokesDecodeEveryWord() {
        decoder.begin(layout, GlideContext(NgramModel.SENTENCE_START), 0L)
        val t = feedPath("hello")
        decoder.boundary()
        feedPath("world", t + 150)
        val r = decoder.finish()!!
        assertEquals(listOf("hello", "world"), r.words.map { dictionary.lower[it] })
        assertEquals(2, r.entries.size)
        assertTrue(r.entries.all { it.candidates.contains(it.word) })
    }

    @Test
    fun previewAppearsWhileTheFingerMoves() {
        decoder.begin(layout, GlideContext(NgramModel.UNKNOWN), 0L)
        feedPath("keyboard")
        val p = decoder.preview()
        assertNotNull(p)
        decoder.finish()
    }
}
