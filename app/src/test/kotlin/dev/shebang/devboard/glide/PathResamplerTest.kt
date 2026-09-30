package dev.shebang.devboard.glide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class PathResamplerTest {
    @Test
    fun resamplesToEquidistantPoints() {
        val pts = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f) // L shape, length 20
        val out = FloatArray(2 * 5)
        PathResampler.resample(pts, 3, 5, out)
        assertEquals(0f, out[0], 1e-4f); assertEquals(0f, out[1], 1e-4f)
        assertEquals(5f, out[2], 1e-4f); assertEquals(0f, out[3], 1e-4f)
        assertEquals(10f, out[4], 1e-4f); assertEquals(0f, out[5], 1e-4f)
        assertEquals(10f, out[6], 1e-4f); assertEquals(5f, out[7], 1e-4f)
        assertEquals(10f, out[8], 1e-4f); assertEquals(10f, out[9], 1e-4f)
        for (i in 1 until 5) {
            val d = hypot(out[2 * i] - out[2 * i - 2], out[2 * i + 1] - out[2 * i - 1])
            assertEquals(5f, d, 1e-3f)
        }
    }

    @Test
    fun degenerateInputsDoNotCrash() {
        val out = FloatArray(2 * PathResampler.N)
        PathResampler.resample(floatArrayOf(3f, 4f), 1, PathResampler.N, out)
        assertEquals(3f, out[2 * 63], 0f)
        PathResampler.resample(floatArrayOf(3f, 4f, 3f, 4f), 2, PathResampler.N, out)
        assertEquals(4f, out[2 * 63 + 1], 0f)
    }

    @Test
    fun shapeNormalisationIsTranslationAndScaleInvariant() {
        val a = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f)
        val b = floatArrayOf(100f, 100f, 130f, 100f, 130f, 130f)
        val ra = FloatArray(2 * 16); val rb = FloatArray(2 * 16)
        PathResampler.resample(a, 3, 16, ra)
        PathResampler.resample(b, 3, 16, rb)
        val na = FloatArray(2 * 16); val nb = FloatArray(2 * 16)
        PathResampler.normalizeShape(ra, 16, na)
        PathResampler.normalizeShape(rb, 16, nb)
        assertTrue(PathResampler.meanDistance(na, nb, 16) < 1e-4f)
    }

    @Test
    fun idealPathCollapsesRepeatedLetters() {
        val model = KeyLayoutModel.build(1, 10f, 10f) { c -> (c - 'a') * 10f to 0f }
        val out = FloatArray(20)
        assertEquals(4, IdealPath.points("hello", model, out))
        assertEquals(1, IdealPath.collapsedLength("aaa"))
        assertEquals(3, IdealPath.collapsedLength("bookkeeper".take(4))) // b,o,k
    }

    @Test
    fun idealPathCacheInvalidatesOnGeometryVersion() {
        val cache = IdealPathCache(8)
        val m1 = KeyLayoutModel.build(1, 10f, 10f) { c -> (c - 'a') * 10f to 0f }
        val m2 = KeyLayoutModel.build(2, 10f, 10f) { c -> (c - 'a') * 20f to 0f }
        val p1 = cache.get("ab", m1)!!
        assertTrue(cache.get("ab", m1) === p1)
        assertEquals(1, cache.hits)
        val p2 = cache.get("ab", m2)!!
        assertTrue(p1 !== p2)
        assertEquals(20f, p2[2 * 63], 1e-3f)
        assertEquals(1, cache.size)
    }
}
