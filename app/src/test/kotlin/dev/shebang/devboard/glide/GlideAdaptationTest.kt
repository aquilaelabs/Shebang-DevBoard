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
        var day = 0
        val a = GlideAdaptation(null) { day }
        // Someone who touches 'b' 0.3 right and 0.3 low, day after day: the offset stops at the cap.
        repeat(400) {
            if (it % 50 == 0) day++
            val cur = a.offsets()
            a.learn(observations(1, 0.3f - cur[1], 0.3f - cur[27], 1))
        }
        val off = a.offsets()
        assertTrue(sqrt(off[1] * off[1] + off[27] * off[27]) <= GlideAdaptation.MAX_OFFSET + 1e-4f)
        val b = GlideAdaptation(null)
        assertTrue(!b.learn(observations(1, 2f, 0f, 10)))
        assertEquals(0f, b.offsets()[1], 1e-6f)
    }

    @Test
    fun aSloppyGlideTeachesNothing() {
        val a = GlideAdaptation(null)
        // Nowhere near its letters on average: not a habit worth learning.
        assertTrue(!a.learn(floatArrayOf(4f, 0.6f, 0.3f, 7f, -0.5f, 0.4f, 11f, 0.2f, 0.7f)))
        assertTrue(a.offsets().all { it == 0f })
    }

    @Test
    fun oneDayCanOnlyTeachSoMuch() {
        val a = GlideAdaptation(null) { 100 }
        var taken = 0
        repeat(1000) { if (a.learn(observations(it % 26, 0.3f, 0.2f, 5))) taken++ }
        assertEquals(GlideAdaptation.DAILY_BUDGET / 5, taken)
    }

    @Test
    fun goingBackToTheStartOfADay() {
        var day = 10
        val f = Files.createTempDirectory("devboard").toFile().resolve(GlideAdaptation.FILE)
        val a = GlideAdaptation(f) { day }
        repeat(20) { a.learn(observations(4, 0.1f, 0.05f, 3)) }
        val endOfDay10 = a.offsets()
        day = 11
        repeat(50) { a.learn(observations(4, 0.3f, -0.2f, 3)) }
        assertTrue(!a.offsets().contentEquals(endOfDay10))
        assertEquals(listOf(11, 10), a.restoreDays())
        a.save()
        // Kept across a restart of the keyboard.
        val b = GlideAdaptation(f) { day }
        b.restore(11)
        assertTrue(b.offsets().contentEquals(endOfDay10))
        b.restore(10)
        assertTrue(b.offsets().all { it == 0f })
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
        assertTrue(a.restoreDays().isNotEmpty())
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
        assertTrue(b.restoreDays().isEmpty())
    }

    private val sim = GestureSimulator(GlideBenchmarkTest.layout)
    // The adaptation's own safeguards, on the simulator's strokes: without the learned reading of strokes, which
    // was trained on real fingers and reads the simulator's differently (FutoSwipesTest measures it on real ones).
    private val decoder = StreamingGlideDecoder(GlideBenchmarkTest.language, GlideParams(modelWeight = 0f))
    private val du get() = BIAS_U * layout.keyWidth
    private val dv get() = BIAS_V * layout.keyHeight

    /** A glide of [w] by the biased user; [sloppy] adds a drunk night's aim (a wide random miss per stroke and shaky points). */
    private fun decode(w: String, rnd: Random, offsets: FloatArray?, sloppy: Boolean = false): GlideResult? {
        val g = sim.generate(w, rnd) ?: return null
        decoder.begin(layout, GlideContext(NgramModel.UNKNOWN), g.t[0], offsets = offsets)
        val missX = if (sloppy) (rnd.nextGaussian() * 0.5 * layout.keyWidth).toFloat() else 0f
        val missY = if (sloppy) (rnd.nextGaussian() * 0.5 * layout.keyHeight).toFloat() else 0f
        for (i in 0 until g.count) {
            val shakeX = if (sloppy) (rnd.nextGaussian() * 0.3 * layout.keyWidth).toFloat() else 0f
            val shakeY = if (sloppy) (rnd.nextGaussian() * 0.3 * layout.keyHeight).toFloat() else 0f
            decoder.addPoint(g.x[i] + du + missX + shakeX, g.y[i] + dv + missY + shakeY, g.t[i])
        }
        return decoder.finish()
    }

    private fun top1(test: List<String>, offsets: FloatArray?): Double {
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

    /**
     * The user keeps the glides that came out right and corrects the rest (the stroke re-aligned to the word
     * meant counts twice), [perDay] glides a day. A careless user ([corrects] false) keeps whatever came out.
     */
    private fun use(a: GlideAdaptation, words: List<String>, rnd: Random, clock: IntArray?, perDay: Int = 60, sloppy: Boolean = false, corrects: Boolean = true) {
        for ((k, w) in words.withIndex()) {
            if (clock != null && k % perDay == 0) clock[0]++
            val r = decode(w, rnd, a.offsets(), sloppy) ?: continue
            val obs = r.observations.lastOrNull()
            if (dictionary.lower[r.words.last()] == w || !corrects) {
                obs?.let { a.learn(it) }
            } else {
                val stroke = r.strokes.lastOrNull() ?: continue
                decoder.observeWord(layout, a.offsets(), stroke, dictionary.indexOfLower(w))?.let { a.learnCorrection(it) }
            }
        }
    }

    /**
     * A user whose every swipe lands [BIAS_U] of a key to the right and [BIAS_V] of a row low (a thumb
     * reaching up from below), using the keyboard over several days: top-1 before and after adapting.
     */
    @Test
    fun adaptationRecoversABiasedSwiper() {
        val words = GlideBenchmarkTest.commonWords.shuffled(Random(17))
        val test = words.drop(600).take(400)
        val before = top1(test, null)
        val clock = intArrayOf(0)
        val a = GlideAdaptation(null) { clock[0] }
        use(a, words.take(600), Random(9), clock, perDay = 60)
        val after = top1(test, a.offsets())
        val off = a.offsets()
        println("GLIDE ADAPT biased swiper (%.2f, %.2f): top-1 %.1f%% before, %.1f%% after 10 days; mean offset (%.2f, %.2f)".format(
            BIAS_U, BIAS_V, before, after, off.take(26).average(), off.drop(26).average()))
        assertTrue("after $after < before $before", after >= before)
        assertEquals(BIAS_V.toDouble(), off.drop(26).average(), 0.12)
    }

    /**
     * The same user after ten sober days, then one drunk night: a thousand sloppy glides, all kept, none
     * corrected. The next morning's accuracy must stay close to the evening before, no key may have moved
     * far, and going back to the start of that night restores the sober state exactly.
     */
    @Test
    fun oneSloppyNightDoesNoLastingHarm() {
        val words = GlideBenchmarkTest.commonWords.shuffled(Random(23))
        val test = words.drop(600).take(400)
        val clock = intArrayOf(0)
        val a = GlideAdaptation(null) { clock[0] }
        use(a, words.take(600), Random(9), clock, perDay = 60)
        val sober = a.offsets()
        val soberTop1 = top1(test, sober)
        clock[0]++
        val night = clock[0]
        val drunkWords = (0 until 1000).map { words[it % 600] }
        // All in one night: the day does not change.
        use(a, drunkWords, Random(31), null, sloppy = true, corrects = false)
        val after = a.offsets()
        val afterTop1 = top1(test, after)
        var worst = 0f
        for (c in 0 until 26) {
            val mu = after[c] - sober[c]
            val mv = after[26 + c] - sober[26 + c]
            worst = maxOf(worst, sqrt(mu * mu + mv * mv))
        }
        println("GLIDE ADAPT one sloppy night: top-1 %.1f%% sober, %.1f%% next morning; the most any key moved %.3f of a key".format(soberTop1, afterTop1, worst))
        assertTrue("next morning $afterTop1 vs sober $soberTop1", afterTop1 >= soberTop1 - 2.0)
        assertTrue("a key moved $worst", worst <= 0.08f)
        a.restore(night)
        assertTrue(a.offsets().contentEquals(sober))
    }

    private companion object {
        const val BIAS_U = 0.15f
        const val BIAS_V = 0.3f
    }
}
