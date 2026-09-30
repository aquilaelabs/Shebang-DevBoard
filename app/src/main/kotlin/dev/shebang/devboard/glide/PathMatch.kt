package dev.shebang.devboard.glide

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * How well a glide's stroke fits the path through the keys of any letters, not only dictionary words: used
 * to offer identifiers from the text around the cursor ("getUserName") as alternatives to a glide. The
 * stroke is the decoder's own ([GlideResult.strokes]: resampled x,y in key pitches); the path runs through
 * the key centres in the same units, sampled every quarter pitch. The cost is the mean distance, in key
 * pitches, along the best alignment of the two (dynamic time warping with both ends pinned).
 */
object PathMatch {
    private const val SPACING = 0.25f

    /** Mean distance between [stroke] and the path through [letters]' keys, or null when a letter has no key. */
    fun cost(layout: KeyLayoutModel, stroke: FloatArray, letters: String): Float? {
        val n = stroke.size / 2
        if (n < 2) return null
        val q = 'q' - 'a'
        val w = 'w' - 'a'
        val a = 'a' - 'a'
        var pitchX = if (layout.hasLetter('q') && layout.hasLetter('w')) abs(layout.centerX[w] - layout.centerX[q]) else layout.keyWidth
        var pitchY = if (layout.hasLetter('q') && layout.hasLetter('a')) abs(layout.centerY[a] - layout.centerY[q]) else layout.keyHeight
        if (pitchX <= 0f) pitchX = layout.keyWidth
        if (pitchY <= 0f) pitchY = layout.keyHeight
        // The key centres of the letters, a repeated key once.
        val ku = ArrayList<Float>()
        val kv = ArrayList<Float>()
        for (ch in letters.lowercase()) {
            if (ch !in 'a'..'z') continue
            if (!layout.hasLetter(ch)) return null
            val u = layout.x(ch) / pitchX
            val v = layout.y(ch) / pitchY
            if (ku.isNotEmpty() && ku.last() == u && kv.last() == v) continue
            ku += u
            kv += v
        }
        if (ku.isEmpty()) return null
        // The path, sampled along each stretch.
        val pu = ArrayList<Float>()
        val pv = ArrayList<Float>()
        pu += ku[0]
        pv += kv[0]
        for (i in 1 until ku.size) {
            val du = ku[i] - ku[i - 1]
            val dv = kv[i] - kv[i - 1]
            val steps = maxOf(1, (sqrt(du * du + dv * dv) / SPACING).toInt())
            for (s in 1..steps) {
                pu += ku[i - 1] + du * s / steps
                pv += kv[i - 1] + dv * s / steps
            }
        }
        val m = pu.size
        // DTW over n stroke points and m path points, rolling rows.
        var prev = FloatArray(m) { Float.MAX_VALUE }
        var cur = FloatArray(m)
        var prevLen = IntArray(m)
        var curLen = IntArray(m)
        for (i in 0 until n) {
            val su = stroke[2 * i]
            val sv = stroke[2 * i + 1]
            for (j in 0 until m) {
                val du = su - pu[j]
                val dv = sv - pv[j]
                val d = sqrt(du * du + dv * dv)
                var best: Float
                var len: Int
                if (i == 0 && j == 0) {
                    best = 0f
                    len = 0
                } else {
                    best = Float.MAX_VALUE
                    len = 0
                    if (i > 0 && prev[j] < best) { best = prev[j]; len = prevLen[j] }
                    if (j > 0 && cur[j - 1] < best) { best = cur[j - 1]; len = curLen[j - 1] }
                    if (i > 0 && j > 0 && prev[j - 1] < best) { best = prev[j - 1]; len = prevLen[j - 1] }
                }
                cur[j] = best + d
                curLen[j] = len + 1
            }
            val t = prev
            prev = cur
            cur = t
            val tl = prevLen
            prevLen = curLen
            curLen = tl
        }
        return prev[m - 1] / prevLen[m - 1]
    }
}
