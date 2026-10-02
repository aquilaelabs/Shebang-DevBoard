package dev.shebang.devboard.glide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The Kotlin glide model reads strokes as the trained PyTorch model does: test vectors written by
 * tools/glide_model/export.py (a few FUTO dev strokes and the model's log-probabilities for them, with the
 * weights rounded to float16 as shipped).
 */
class GlideModelTest {
    private val model get() = GlideBenchmarkTest.glideModel

    @Test
    fun matchesThePyTorchModel() {
        val m = model
        val vectors = File("src/test/resources/glide/glide_model_vectors.txt")
        assumeTrue("no glide model or vectors", m != null && vectors.isFile)
        val lines = vectors.readLines()
        var i = 0
        var strokes = 0
        var worst = 0f
        while (i < lines.size) {
            val n = lines[i++].removePrefix("stroke ").trim().toInt()
            val u = FloatArray(n)
            val v = FloatArray(n)
            val t = FloatArray(n)
            for (k in 0 until n) {
                val p = lines[i++].trim().split(' ').map { it.toFloat() }
                u[k] = p[0]
                v[k] = p[1]
                t[k] = p[2]
            }
            m!!.read(u, v, t, n)
            val got = m.logProbabilities()
            for (k in 0 until n) {
                val want = lines[i++].trim().split(' ').map { it.toFloat() }
                for (c in 0 until 27) worst = maxOf(worst, kotlin.math.abs(want[c] - got[k * 27 + c]))
            }
            strokes++
        }
        assertTrue(strokes > 0)
        assertTrue("largest difference $worst", worst < 2e-3f)
    }

    @Test
    fun aWordsKeysCostLessThanAnotherWordsOnItsStroke() {
        val m = model
        assumeTrue(m != null)
        // An ideal stroke through t, h, e: "the" should read far better than "tie" or "toe".
        val path = listOf(4.5f to 0.5f, 5.5f to 1.5f, 2.5f to 0.5f)
        val u = ArrayList<Float>()
        val v = ArrayList<Float>()
        for (s in 0 until path.size - 1) {
            val (a, b) = path[s]
            val (c, d) = path[s + 1]
            val len = kotlin.math.hypot(c - a, d - b)
            val steps = (len / GlideModel.SPACING).toInt()
            for (k in 0 until steps) {
                u += a + (c - a) * k / steps
                v += b + (d - b) * k / steps
            }
        }
        u += path.last().first
        v += path.last().second
        val t = FloatArray(u.size) { it * 12f }
        m!!.read(u.toFloatArray(), v.toFloatArray(), t, u.size)
        val keys = IntArray(8)
        fun cost(w: String) = m.cost(keys, LexiconTrie.keySequence(w, keys))
        assertTrue("the ${cost("the")} tie ${cost("tie")}", cost("the") + 3 < cost("tie"))
        assertTrue("the ${cost("the")} toe ${cost("toe")}", cost("the") + 3 < cost("toe"))
        // More keys than the stroke has points: no reading at all.
        m.read(u.toFloatArray(), v.toFloatArray(), t, 3)
        assertEquals(GlideModel.IMPOSSIBLE, cost("then"), 0f)
    }
}
