package dev.shebang.devboard.glide

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PathMatchTest {
    private val layout get() = GlideBenchmarkTest.layout

    /** A stroke along the key centres of [letters], in key pitches, sampled every tenth of a pitch. */
    private fun ideal(letters: String): FloatArray {
        val px = layout.x('w') - layout.x('q')
        val py = layout.y('a') - layout.y('q')
        val pts = ArrayList<Float>()
        var prev: Pair<Float, Float>? = null
        for (c in letters) {
            val p = layout.x(c) / px to layout.y(c) / py
            val from = prev
            if (from == null) {
                pts += p.first
                pts += p.second
            } else {
                for (s in 1..10) {
                    pts += from.first + (p.first - from.first) * s / 10
                    pts += from.second + (p.second - from.second) * s / 10
                }
            }
            prev = p
        }
        return pts.toFloatArray()
    }

    @Test
    fun aPathFitsItsOwnLettersBest() {
        val stroke = ideal("getuser")
        val own = PathMatch.cost(layout, stroke, "getuser")
        assertNotNull(own)
        assertTrue(own!! < 0.1f)
        for (other in listOf("getter", "gets", "user", "kubectl")) assertTrue(other, PathMatch.cost(layout, stroke, other)!! > own + 0.1f)
    }
}
