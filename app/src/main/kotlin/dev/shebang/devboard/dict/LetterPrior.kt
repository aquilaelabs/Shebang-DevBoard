package dev.shebang.devboard.dict

/**
 * How likely each letter is to come next in the word being typed: the dictionary's words that start with
 * what has been typed so far, each weighted by how likely it is (with the words before, when given), summed
 * by their next letter. Lets a tap between two keys go to the letter that makes a word.
 */
class LetterPrior(
    private val dict: Dictionary,
    /** How likely dictionary word i is here (any scale; only ratios matter). */
    private val weight: (Int) -> Double,
) {
    /** P(next letter) for a..z after [prefix] (lowercase letters), or null when no word continues it. */
    fun next(prefix: String): FloatArray? {
        val range = dict.prefixRange(prefix)
        if (range.isEmpty()) return null
        val sums = DoubleArray(26)
        var total = 0.0
        val n = prefix.length
        for (i in range) {
            val w = dict.lower[i]
            if (w.length <= n) continue
            val c = w[n] - 'a'
            if (c !in 0..25) continue
            val p = weight(i)
            sums[c] += p
            total += p
        }
        if (total <= 0.0) return null
        return FloatArray(26) { (sums[it] / total).toFloat() }
    }

    companion object {
        /** A share of the odds spread evenly, so names and words the dictionary lacks can still be typed. */
        var FLOOR = 0.1f

        /** -ln of how likely letter [c] is next under [prior] (null: no opinion, 0). */
        fun cost(prior: FloatArray?, c: Char): Double {
            if (prior == null) return 0.0
            val i = c - 'a'
            if (i !in 0..25) return 0.0
            return -kotlin.math.ln(((1 - FLOOR) * prior[i] + FLOOR / 26).toDouble())
        }
    }
}
