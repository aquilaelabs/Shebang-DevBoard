package dev.shebang.devboard.glide

import kotlin.math.abs

/**
 * Where fingers land when they tap a letter key: around its centre, nudged by this user's learned offsets,
 * with a spread the same across and down. Fitted on the TSI tap dataset (CC BY 4.0; 37,022 letter taps by
 * 16 people on a Pixel 6 Pro): taps fall a little left of and above the centre (about 2 dp each way) and
 * scatter with a standard deviation of about 10 dp; 94.2% land nearest the key meant.
 *
 * [cost] says how much less likely a tap was meant for one letter than for another, which lets autocorrect
 * weigh a slip by where the finger actually came down rather than by whether the keys are neighbours.
 */
class TapModel(
    private val layout: KeyLayoutModel,
    density: Float,
    /** This user's learned offsets (26 across then 26 down, in key pitches), or null. */
    private val offsets: FloatArray? = null,
) {
    private val sigma = SIGMA_DP * density
    private val leanX = LEAN_X_DP * density
    private val leanY = LEAN_Y_DP * density
    val pitchX: Float
    val pitchY: Float

    init {
        val q = 'q' - 'a'
        val w = 'w' - 'a'
        val a = 'a' - 'a'
        var px = if (layout.hasLetter('q') && layout.hasLetter('w')) abs(layout.centerX[w] - layout.centerX[q]) else layout.keyWidth
        var py = if (layout.hasLetter('q') && layout.hasLetter('a')) abs(layout.centerY[a] - layout.centerY[q]) else layout.keyHeight
        if (px <= 0f) px = layout.keyWidth
        if (py <= 0f) py = layout.keyHeight
        pitchX = px
        pitchY = py
    }

    /** Where this user's taps on [c] centre, in pixels; null when the layout has no key for it. */
    fun aimX(c: Char): Float? {
        val i = c - 'a'
        if (i !in 0..25 || !layout.hasLetter(c)) return null
        return layout.centerX[i] + leanX + (offsets?.get(i) ?: 0f) * pitchX
    }

    fun aimY(c: Char): Float? {
        val i = c - 'a'
        if (i !in 0..25 || !layout.hasLetter(c)) return null
        return layout.centerY[i] + leanY + (offsets?.get(26 + i) ?: 0f) * pitchY
    }

    /** -ln of how likely a tap at x,y is for [c], up to a constant; null without a key for [c]. */
    fun nats(x: Float, y: Float, c: Char): Double? {
        val ax = aimX(c) ?: return null
        val ay = aimY(c) ?: return null
        val dx = (x - ax) / sigma
        val dy = (y - ay) / sigma
        return 0.5 * (dx * dx + dy * dy)
    }

    /**
     * How much less likely (in nats, at least 0) a tap at x,y was meant for [meant] than for [typed], the key
     * it landed on; null when either has no key.
     */
    fun cost(x: Float, y: Float, typed: Char, meant: Char): Double? {
        val a = nats(x, y, typed) ?: return null
        val b = nats(x, y, meant) ?: return null
        return maxOf(0.0, b - a)
    }

    /**
     * Where a tap meant for [c] landed relative to where this model aims, as an observation triple for the
     * adaptation (letter, du, dv in key pitches); null without a key.
     */
    fun observation(x: Float, y: Float, c: Char): FloatArray? {
        val ax = aimX(c) ?: return null
        val ay = aimY(c) ?: return null
        return floatArrayOf((c - 'a').toFloat(), (x - ax) / pitchX, (y - ay) / pitchY)
    }

    companion object {
        /** Spread of taps around where they aim, in dp (TSI: 0.265 of a 135 px key at 3.5 px per dp). */
        const val SIGMA_DP = 10.2f
        /** Where taps land on average relative to the key centre, in dp (TSI: -0.056 and -0.053 of a key). */
        const val LEAN_X_DP = -2.2f
        const val LEAN_Y_DP = -2.0f
    }
}
