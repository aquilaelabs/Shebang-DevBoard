package dev.shebang.devboard.glide

import dev.shebang.devboard.dict.NgramModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.Random
import kotlin.math.sqrt

/**
 * Per-user glide adaptation: offsets learned from kept glides and corrections, and whether they help a
 * simulated user whose swipes all land off the key centres.
 */
class GlideAdaptationTest {
    private val layout get() = GlideBenchmarkTest.layout
    private val dictionary get() = GlideBenchmarkTest.dictionary
    private val language get() = GlideBenchmarkTest.language

    private fun observations(letter: Int, du: Float, dv: Float, count: Int) =
        FloatArray(3 * count) { i -> when (i % 3) { 0 -> letter.toFloat(); 1 -> du; else -> dv } }

    @Test
    fun learnsWhereALetterIsTouched() {
        val a = GlideAdaptation(null)
        // The user always touches 'e' at (0.2, 0.1); each observation is the gap from the centre in use then.
        repeat(30) {
            val cur = a.offsets()
            a.learn(observations(4, 0.2f - cur[4], 0.1f - cur[26 + 4], 1))
        }
        val off = a.offsets()
        assertEquals(0.2f, off[4], 0.03f)
        assertEquals(0.1f, off[26 + 4], 0.03f)
        // A letter never seen leans the way the user's glides lean overall, through the shared offset.
        assertEquals(0.2f, off[0], 0.03f)
    }

    @Test
    fun offsetsAreCappedAndOutliersIgnored() {
        val a = GlideAdaptation(null)
        a.learn(observations(1, 0.7f, 0.7f, 100))
        val off = a.offsets()
        assertTrue(sqrt(off[1] * off[1] + off[27] * off[27]) <= GlideAdaptation.MAX_OFFSET + 1e-4f)
        val b = GlideAdaptation(null)
        b.learn(observations(1, 2f, 0f, 10))
        assertEquals(0f, b.offsets()[1], 1e-6f)
    }

    @Test
    fun aCorrectionCountsDouble() {
        val once = GlideAdaptation(null).apply { learn(observations(2, 0.3f, 0f, 1)) }
        val twice = GlideAdaptation(null).apply { learn(observations(2, 0.3f, 0f, 1), weight = 2) }
        assertTrue(twice.offsets()[2] > once.offsets()[2])
    }

    @Test
    fun anUnrelatedReplacementTeachesNothing() {
        val a = GlideAdaptation(null)
        // "again" swiped, "soon" typed over it: forced onto "soon" the stroke is far off every letter.
        assertTrue(!a.learnCorrection(floatArrayOf(18f, -0.75f, 0f, 14f, 0.6f, 0.3f, 13f, 0.5f, -0.4f)))
        assertTrue(a.offsets().all { it == 0f })
        assertTrue(a.learnCorrection(floatArrayOf(18f, 0.2f, 0.1f, 14f, 0.25f, 0.05f)))
    }

    @Test
    fun savesLoadsAndResets() {
        val f = Files.createTempDirectory("devboard").toFile().resolve(GlideAdaptation.FILE)
        val a = GlideAdaptation(f)
        a.learn(observations(7, 0.1f, -0.2f, 20))
        a.recordGlide()
        a.recordCorrection()
        a.save()
        val b = GlideAdaptation(f)
        b.load()
        assertTrue(a.offsets().contentEquals(b.offsets()))
        assertEquals(1, b.glides)
        assertEquals(1, b.corrections)
        b.reset()
        assertTrue(b.offsets().all { it == 0f })
        assertEquals(0, b.glides)
    }

    /**
     * A user whose every swipe lands [BIAS_U] of a key to the right and [BIAS_V] of a row low (a thumb
     * reaching up from below). Top-1 before adapting, then after learning from glides of other words that
     * decoded right (the ones the user would keep), then with corrections of the ones that did not.
     */
    @Test
    fun adaptationRecoversABiasedSwiper() {
        val sim = GestureSimulator(layout)
        val words = GlideBenchmarkTest.commonWords.shuffled(Random(17))
        val train = words.take(250)
        val test = words.drop(250).take(400)
        val decoder = StreamingGlideDecoder(language)
        val du = BIAS_U * layout.keyWidth
        val dv = BIAS_V * layout.keyHeight

        fun decode(w: String, rnd: Random, offsets: FloatArray?): GlideResult? {
            val g = sim.generate(w, rnd) ?: return null
            decoder.begin(layout, GlideContext(NgramModel.UNKNOWN), g.t[0], offsets = offsets)
            for (i in 0 until g.count) decoder.addPoint(g.x[i] + du, g.y[i] + dv, g.t[i])
            return decoder.finish()
        }

        fun top1(offsets: FloatArray?): Double {
            val rnd = Random(5)
            var n = 0
            var ok = 0
            for (w in test) {
                val r = decode(w, rnd, offsets) ?: continue
                n++
                if (dictionary.lower[r.words.last()] == w) ok++
            }
            return 100.0 * ok / n
        }

        val before = top1(null)
        val adaptation = GlideAdaptation(null)
        val rnd = Random(9)
        var corrected = 0
        for (w in train) {
            val r = decode(w, rnd, adaptation.offsets()) ?: continue
            val obs = r.observations.lastOrNull()
            if (dictionary.lower[r.words.last()] == w) {
                obs?.let { adaptation.learn(it) }
            } else {
                // The user corrects it: the stroke is re-aligned to the word meant and counts twice.
                val stroke = r.strokes.lastOrNull() ?: continue
                decoder.observeWord(layout, adaptation.offsets(), stroke, dictionary.indexOfLower(w))?.let {
                    adaptation.learn(it, weight = 2)
                    corrected++
                }
            }
        }
        val after = top1(adaptation.offsets())
        val off = adaptation.offsets()
        println("GLIDE ADAPT biased swiper (%.2f, %.2f): top-1 %.1f%% before, %.1f%% after (%d corrections); mean offset (%.2f, %.2f)".format(
            BIAS_U, BIAS_V, before, after, corrected, off.take(26).average(), off.drop(26).average()))
        assertTrue("after $after <= before $before", after > before + 3)
        assertEquals(BIAS_V.toDouble(), off.drop(26).average(), 0.12)
    }

    private companion object {
        const val BIAS_U = 0.15f
        const val BIAS_V = 0.3f
    }
}
