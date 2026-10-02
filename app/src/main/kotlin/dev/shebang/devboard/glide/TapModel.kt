package dev.shebang.devboard.glide

import dev.shebang.devboard.dict.LetterPrior
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

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
    private val spaceLeanY = SPACE_LEAN_Y_DP * density
    private val spaceSigmaY = SPACE_SIGMA_Y_DP * density
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
     * The letter a tap at x,y most likely meant, among [letters]: the one whose aim (its centre, nudged by the
     * lean and this user's offsets) is nearest, weighed with [prior], how likely each letter is next
     * ([dev.shebang.devboard.dict.LetterPrior]), at [priorWeight]; null when none has a key.
     */
    fun nearestLetter(x: Float, y: Float, letters: Iterable<Char> = 'a'..'z', prior: FloatArray? = null, priorWeight: Double = PRIOR_WEIGHT): Char? {
        var best: Char? = null
        var bestCost = Double.MAX_VALUE
        for (c in letters) {
            val n = (nats(x, y, c) ?: continue) + priorWeight * dev.shebang.devboard.dict.LetterPrior.cost(prior, c)
            if (n < bestCost) {
                bestCost = n
                best = c
            }
        }
        return best
    }

    /** The space bar's edges and middle, in the same pixels as the letter keys. */
    class Bar(val left: Float, val right: Float, val centerY: Float)

    /**
     * Whether a tap at x,y that landed on letter [c] was more likely meant for the space bar [bar] below it.
     * Thumbs reaching for space land high (TSI: 10.7% of space taps hit the letters above it, while letters
     * almost never land on it), so the bar is modelled as where its taps fall: across, anywhere on it; down,
     * a spread around a point above its middle. [prior] is [LetterPrior.next] for the word so far: its share
     * for the word ending is the space's odds, and the rest goes to the letters; without one the space gets
     * [SPACE_BASE]. A tap above the letter's middle stays the letter, however finished the word looks (TSI:
     * 99% of space taps land below it).
     */
    fun meansSpace(x: Float, y: Float, c: Char, bar: Bar, prior: FloatArray?): Boolean {
        val i = c - 'a'
        if (i !in 0..25 || !layout.hasLetter(c) || y <= layout.centerY[i]) return false
        val n = nats(x, y, c) ?: return false
        val end = prior?.getOrNull(LetterPrior.END)?.takeIf { !it.isNaN() }
        val pSpace = (end?.toDouble() ?: SPACE_BASE).coerceIn(SPACE_FLOOR, 1 - SPACE_FLOOR)
        // -ln of each density, with its constants, so the bar and a key compare.
        val letter = n + ln(2 * Math.PI * sigma * sigma) - ln(1 - pSpace) +
            if (prior != null) PRIOR_WEIGHT * LetterPrior.cost(prior, c) else ln(26.0)
        val dy = (y - bar.centerY - spaceLeanY) / spaceSigmaY
        val off = (if (x < bar.left) bar.left - x else if (x > bar.right) x - bar.right else 0f) / sigma
        val space = 0.5 * (dy * dy + off * off) + ln(sqrt(2 * Math.PI) * spaceSigmaY) + ln(maxOf(1f, bar.right - bar.left).toDouble()) - ln(pSpace)
        return space < letter
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
        /** How much the next letter's likelihood counts against where the tap landed. */
        var PRIOR_WEIGHT = 1.0
        /** Where taps meant for space land relative to the bar's middle, and their spread down, in dp (TSI: -43 and 48 px at 3.5 px per dp). */
        var SPACE_LEAN_Y_DP = -12.3f
        var SPACE_SIGMA_Y_DP = 13.8f
        /** The space's odds against a letter when nothing is known about the word (TSI: 18% of characters typed). */
        var SPACE_BASE = 0.18
        /** The space's odds are kept between this and 1 minus it, whatever the word says. */
        var SPACE_FLOOR = 0.02
    }
}
