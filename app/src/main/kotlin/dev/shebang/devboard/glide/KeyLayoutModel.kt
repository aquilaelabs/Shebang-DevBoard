package dev.shebang.devboard.glide

/**
 * The letter geometry glide decoding needs, decoupled from Android views so the decoder is unit-testable.
 * Coordinates are in pixels (or any consistent unit); [keyWidth] is the width of one letter key in that unit.
 */
class KeyLayoutModel(
    /** centreX[c - 'a'], centreY[c - 'a']; NaN when the letter has no key. */
    val centerX: FloatArray,
    val centerY: FloatArray,
    val keyWidth: Float,
    val keyHeight: Float,
    /** Changes whenever positions change; the ideal-path cache is keyed on it. */
    val version: Int,
) {
    fun hasLetter(c: Char): Boolean {
        val i = c - 'a'
        return i in 0..25 && !centerX[i].isNaN()
    }

    fun x(c: Char): Float = centerX[c - 'a']
    fun y(c: Char): Float = centerY[c - 'a']

    /** The letter whose key centre is nearest to the point (letters only). */
    fun nearestLetter(px: Float, py: Float): Char {
        var best = 'a'
        var bestD = Float.MAX_VALUE
        for (i in 0..25) {
            val x = centerX[i]
            if (x.isNaN()) continue
            val dx = x - px
            val dy = centerY[i] - py
            val d = dx * dx + dy * dy
            if (d < bestD) {
                bestD = d
                best = 'a' + i
            }
        }
        return best
    }

    companion object {
        fun build(version: Int, keyWidth: Float, keyHeight: Float, place: (Char) -> Pair<Float, Float>?): KeyLayoutModel {
            val xs = FloatArray(26) { Float.NaN }
            val ys = FloatArray(26) { Float.NaN }
            for (c in 'a'..'z') {
                val p = place(c) ?: continue
                xs[c - 'a'] = p.first
                ys[c - 'a'] = p.second
            }
            return KeyLayoutModel(xs, ys, keyWidth, keyHeight, version)
        }
    }
}
