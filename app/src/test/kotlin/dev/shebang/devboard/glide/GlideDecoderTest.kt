package dev.shebang.devboard.glide

import dev.shebang.devboard.dict.Dictionary
import dev.shebang.devboard.layout.FieldVariant
import dev.shebang.devboard.layout.KeyboardGeometry
import dev.shebang.devboard.layout.LayoutParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.math.sqrt

/** Synthetic-swipe harness and decoder behaviour tests, on the real QWERTY geometry and bundled dictionary. */
class GlideDecoderTest {
    companion object {
        private val layout = LayoutParser.parse(File("src/main/assets/layouts/text_qwerty.json").readText())
        private val geometry = KeyboardGeometry(layout, FieldVariant.PLAIN, 1080, 4 * 156, false, 12f, 20f, 1)
        val model: KeyLayoutModel = KeyLayoutModel.build(1, geometry.letterKeyWidth, geometry.rowHeightPx) { c ->
            geometry.letterKey(c)?.let { it.centerX to it.centerY }
        }
        val dictionary: Dictionary by lazy {
            Dictionary.parse(File("src/main/assets/dict/en_words.txt").bufferedReader().readLines().asSequence())
        }
    }

    /**
     * A noisy human-like swipe: the ideal polyline through key centres, each vertex jittered by a Gaussian
     * of [sigma] key widths, then walked with small per-sample wobble, a little overshoot at corners and a
     * few dropped/duplicated samples, as a touch sampler would produce.
     */
    private fun syntheticSwipe(word: String, rnd: Random, sigma: Float): Pair<FloatArray, Int> {
        val raw = FloatArray(2 * word.length)
        val n = IdealPath.points(word, model, raw)
        val kw = model.keyWidth
        // Jitter vertices.
        for (i in 0 until n) {
            raw[2 * i] += (rnd.nextGaussian() * sigma * kw).toFloat()
            raw[2 * i + 1] += (rnd.nextGaussian() * sigma * model.keyHeight * 0.9).toFloat()
        }
        // Walk the polyline with ~3 samples per key width plus wobble.
        val out = FloatArray(2 * 512)
        var count = 0
        fun push(x: Float, y: Float) {
            if (count < 512) {
                out[2 * count] = x + (rnd.nextGaussian() * 0.04 * kw).toFloat()
                out[2 * count + 1] = y + (rnd.nextGaussian() * 0.04 * kw).toFloat()
                count++
            }
        }
        push(raw[0], raw[1])
        for (i in 1 until n) {
            val x0 = raw[2 * i - 2]; val y0 = raw[2 * i - 1]
            val x1 = raw[2 * i]; val y1 = raw[2 * i + 1]
            val len = sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0))
            val steps = maxOf(2, (len / kw * 3).toInt())
            for (s in 1..steps) {
                if (rnd.nextFloat() < 0.08f) continue // dropped sample
                val t = s.toFloat() / steps
                // Slight overshoot near the end of each segment (finger momentum).
                val over = if (t > 0.85f && i < n - 1) 0.06f * kw else 0f
                push(x0 + (x1 - x0) * t + over * Math.signum(x1 - x0), y0 + (y1 - y0) * t)
            }
        }
        return out to count
    }

    @Test
    fun harnessMeetsAccuracyTargets() {
        val dict = dictionary
        val rnd = Random(20240930)
        // The 1,000 most common words: SCOWL tier 10 (the most frequent list), glide-able (2+ distinct keys),
        // sampled deterministically so the set is stable across runs.
        val tier10 = (0 until dict.size).filter { dict.tiers[it] == 10 && IdealPath.collapsedLength(dict.lower[it]) >= 2 && dict.lower[it].all { c -> c in 'a'..'z' } }
        val words = tier10.shuffled(Random(7)).take(1000).map { dict.lower[it] }
        assertEquals(1000, words.size)

        val decoder = GlideDecoder(dict)
        var top1 = 0
        var top3 = 0
        var totalMs = 0L
        var maxMs = 0L
        val misses = ArrayList<String>()
        for (w in words) {
            val (pts, count) = syntheticSwipe(w, rnd, sigma = 0.22f)
            val t0 = System.nanoTime()
            val results = decoder.decode(pts, count, model).map { it.word.lowercase() }
            val ms = (System.nanoTime() - t0) / 1_000_000
            totalMs += ms
            if (ms > maxMs) maxMs = ms
            if (results.firstOrNull() == w) top1++
            if (w in results.take(3)) top3++ else if (misses.size < 15) misses.add("$w -> ${results.take(3)}")
        }
        val acc1 = top1 / 10.0
        val acc3 = top3 / 10.0
        println("GLIDE HARNESS: top-1 $acc1%  top-3 $acc3%  mean ${totalMs / 1000.0}ms  max ${maxMs}ms (JVM, cold cache)")
        println("GLIDE HARNESS sample misses: $misses")
        assertTrue("top-1 accuracy $acc1% below 85%", acc1 >= 85.0)
        assertTrue("top-3 accuracy $acc3% below 95%", acc3 >= 95.0)
    }

    @Test
    fun exactIdealPathDecodesToTheWord() {
        val decoder = GlideDecoder(dictionary)
        for (w in listOf("hello", "world", "keyboard", "terminal", "the", "you", "quick")) {
            val raw = FloatArray(2 * w.length)
            val n = IdealPath.points(w, model, raw)
            val results = decoder.decode(raw, n, model)
            assertEquals(w, results.first().word.lowercase())
            assertTrue(results.size <= 5)
        }
    }

    @Test
    fun frequencyBreaksTiesBetweenSimilarPaths() {
        val decoder = GlideDecoder(dictionary)
        // "to" and "yo" share almost the same path; the common word should win.
        val raw = FloatArray(4)
        val n = IdealPath.points("to", model, raw)
        assertEquals("to", decoder.decode(raw, n, model).first().word.lowercase())
    }

    @Test
    fun tapIsNotAGlide() {
        assertTrue(!GlideDecoder.isGlide(0f, 0f, 3f, 0f, 3f, 100f, startKeyEndKeySame = true))
        assertTrue(GlideDecoder.isGlide(0f, 0f, 120f, 0f, 120f, 100f, startKeyEndKeySame = false))
        assertTrue(!GlideDecoder.isGlide(0f, 0f, 10f, 0f, 10f, 100f, startKeyEndKeySame = false))
    }
}
