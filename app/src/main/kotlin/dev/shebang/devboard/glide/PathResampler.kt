package dev.shebang.devboard.glide

import kotlin.math.sqrt

/** Resampling and normalisation of 2-D point paths stored as interleaved x,y float arrays. */
object PathResampler {
    const val N = 64

    fun length(pts: FloatArray, count: Int): Float {
        var len = 0f
        var i = 1
        while (i < count) {
            val dx = pts[2 * i] - pts[2 * i - 2]
            val dy = pts[2 * i + 1] - pts[2 * i - 1]
            len += sqrt(dx * dx + dy * dy)
            i++
        }
        return len
    }

    /**
     * Resamples the first [count] points of [pts] to [n] equidistant points into [out] (size 2n).
     * A path with zero length yields n copies of its first point.
     */
    fun resample(pts: FloatArray, count: Int, n: Int, out: FloatArray) {
        if (count <= 0) return
        if (count == 1) {
            for (i in 0 until n) {
                out[2 * i] = pts[0]
                out[2 * i + 1] = pts[1]
            }
            return
        }
        val total = length(pts, count)
        if (total <= 0f) {
            for (i in 0 until n) {
                out[2 * i] = pts[0]
                out[2 * i + 1] = pts[1]
            }
            return
        }
        val step = total / (n - 1)
        out[0] = pts[0]
        out[1] = pts[1]
        var outIdx = 1
        var acc = 0f // distance travelled since the last emitted point
        var i = 1
        var px = pts[0]
        var py = pts[1]
        while (i < count && outIdx < n - 1) {
            val cx = pts[2 * i]
            val cy = pts[2 * i + 1]
            val dx = cx - px
            val dy = cy - py
            val seg = sqrt(dx * dx + dy * dy)
            if (acc + seg >= step && seg > 0f) {
                val t = (step - acc) / seg
                val nx = px + t * dx
                val ny = py + t * dy
                out[2 * outIdx] = nx
                out[2 * outIdx + 1] = ny
                outIdx++
                // Continue from the emitted point along the same segment.
                px = nx
                py = ny
                acc = 0f
            } else {
                acc += seg
                px = cx
                py = cy
                i++
            }
        }
        // Fill the remainder (floating-point shortfall) with the last input point.
        while (outIdx < n) {
            out[2 * outIdx] = pts[2 * count - 2]
            out[2 * outIdx + 1] = pts[2 * count - 1]
            outIdx++
        }
    }

    /**
     * Shape normalisation per SHARK2: translate to the centroid and scale so the larger bounding-box side is 1.
     * Nearly straight paths keep their aspect ratio (scale by the longer side only) to avoid blowing up noise.
     */
    fun normalizeShape(path: FloatArray, n: Int, out: FloatArray) {
        var cx = 0f
        var cy = 0f
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until n) {
            val x = path[2 * i]
            val y = path[2 * i + 1]
            cx += x
            cy += y
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        cx /= n
        cy /= n
        val span = maxOf(maxX - minX, maxY - minY)
        val s = if (span > 1e-3f) 1f / span else 1f
        for (i in 0 until n) {
            out[2 * i] = (path[2 * i] - cx) * s
            out[2 * i + 1] = (path[2 * i + 1] - cy) * s
        }
    }

    /** Mean point-to-point Euclidean distance between two equally sampled paths. */
    fun meanDistance(a: FloatArray, b: FloatArray, n: Int): Float {
        var sum = 0f
        for (i in 0 until n) {
            val dx = a[2 * i] - b[2 * i]
            val dy = a[2 * i + 1] - b[2 * i + 1]
            sum += sqrt(dx * dx + dy * dy)
        }
        return sum / n
    }
}
