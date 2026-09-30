package dev.shebang.devboard.glide

import dev.shebang.devboard.dict.Dictionary
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

data class GlideCandidate(val word: String, val score: Double)

/**
 * SHARK2-style shape-writing decoder (Kristensson & Zhai, UIST 2004), written from the paper:
 *
 *  1. Prune by start/end key proximity (words indexed by first and last letter) and rough path length.
 *  2. Compare the gesture against each candidate's ideal path through its letters' key centres,
 *     both resampled to [PathResampler.N] equidistant points.
 *  3. Shape channel: translation- and scale-normalised mean point distance.
 *     Location channel: absolute mean point distance on the keyboard, in key widths.
 *     Each becomes a Gaussian likelihood; their product is weighted by word frequency.
 *  4. Return the top [maxResults].
 */
class GlideDecoder(
    private val dictionary: Dictionary,
    private val cache: IdealPathCache = IdealPathCache(),
    /** Shape-channel sigma in normalised units (bounding box = 1). */
    private val sigmaShape: Double = 0.16,
    /** Location-channel sigma in key widths. */
    private val sigmaLocation: Double = 0.9,
    /** How far (in key widths) the start/end point may be from a word's first/last key to be a candidate. */
    private val endpointRadius: Float = 1.6f,
    private val maxResults: Int = 5,
) {
    // Scratch buffers reused across decodes; a decode runs on one background thread at a time.
    private val gesture = FloatArray(2 * PathResampler.N)
    private val gestureShape = FloatArray(2 * PathResampler.N)
    private val candShape = FloatArray(2 * PathResampler.N)

    /**
     * @param pts interleaved x,y of the raw touch path
     * @param count number of points in [pts]
     */
    @Synchronized
    fun decode(pts: FloatArray, count: Int, model: KeyLayoutModel): List<GlideCandidate> {
        if (count < 2) return emptyList()
        val n = PathResampler.N
        PathResampler.resample(pts, count, n, gesture)
        PathResampler.normalizeShape(gesture, n, gestureShape)
        val gestureLen = PathResampler.length(pts, count)
        val kw = model.keyWidth

        val sx = pts[0]
        val sy = pts[1]
        val ex = pts[2 * count - 2]
        val ey = pts[2 * count - 1]
        val r2 = (endpointRadius * kw) * (endpointRadius * kw)

        val results = ArrayList<GlideCandidate>(64)
        // Endpoint pruning: every (first,last) letter pair whose keys are near the gesture ends.
        for (f in 0..25) {
            val fx = model.centerX[f]
            if (fx.isNaN()) continue
            val dfx = fx - sx
            val dfy = model.centerY[f] - sy
            if (dfx * dfx + dfy * dfy > r2) continue
            for (l in 0..25) {
                val lx = model.centerX[l]
                if (lx.isNaN()) continue
                val dlx = lx - ex
                val dly = model.centerY[l] - ey
                if (dlx * dlx + dly * dly > r2) continue
                val indices = dictionary.indicesByEnds('a' + f, 'a' + l)
                for (idx in indices) {
                    val word = dictionary.lower[idx]
                    if (IdealPath.collapsedLength(word) < 2) continue
                    val ideal = cache.get(word, model, n) ?: continue
                    // Rough length pruning: the ideal path's length should be within a factor of the gesture's.
                    val idealLen = PathResampler.length(ideal, n)
                    if (idealLen > 0f && gestureLen > 0f) {
                        val ratio = idealLen / gestureLen
                        if (ratio < 0.45f || ratio > 2.2f) continue
                    }
                    val locDist = PathResampler.meanDistance(gesture, ideal, n) / kw
                    if (locDist > 3.0f) continue
                    PathResampler.normalizeShape(ideal, n, candShape)
                    val shapeDist = PathResampler.meanDistance(gestureShape, candShape, n)
                    val pShape = gaussian(shapeDist.toDouble(), sigmaShape)
                    val pLoc = gaussian(locDist.toDouble(), sigmaLocation)
                    val score = ln(pShape) + ln(pLoc) + ln(dictionary.weight(idx))
                    results.add(GlideCandidate(dictionary.words[idx], score))
                }
            }
        }
        results.sortByDescending { it.score }
        // Dedupe on the word (case variants like "I"/"i" both exist in SCOWL).
        val out = ArrayList<GlideCandidate>(maxResults)
        for (c in results) {
            if (out.size >= maxResults) break
            if (out.none { it.word.equals(c.word, ignoreCase = true) }) out.add(c)
        }
        return out
    }

    companion object {
        private const val FLOOR = 1e-12

        fun gaussian(x: Double, sigma: Double): Double {
            val z = x / sigma
            return maxOf(exp(-0.5 * z * z) / (sigma * sqrt(2 * Math.PI)), FLOOR)
        }

        /** Distinguishes a glide from a tap: travelled far enough and ended on another key. */
        fun isGlide(startX: Float, startY: Float, endX: Float, endY: Float, pathLength: Float, keyWidth: Float, startKeyEndKeySame: Boolean): Boolean {
            val disp = sqrt((endX - startX) * (endX - startX) + (endY - startY) * (endY - startY))
            return !startKeyEndKeySame && (pathLength > 0.5f * keyWidth || disp > 0.5f * keyWidth) && abs(pathLength) > 0f
        }
    }
}
