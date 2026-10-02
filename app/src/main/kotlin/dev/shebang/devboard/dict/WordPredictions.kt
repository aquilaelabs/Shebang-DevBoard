package dev.shebang.devboard.dict

/**
 * The words most likely to come next, for the strip after a space: the next-word model's best words and the
 * n-grams' (which carry the user's own words and pairs) ranked together by
 * [NGRAM_WEIGHT] * n-gram cost + [NEXT_WORD_WEIGHT] * next-word cost. Without a next-word model, the n-grams
 * alone ([NgramModel.predict]).
 */
object WordPredictions {
    /** Weights chosen on FUTO's dev sentences (PredictionBenchmarkTest). */
    var NGRAM_WEIGHT = 0.5f
    var NEXT_WORD_WEIGHT = 0.5f
    /** Candidates taken from each model before ranking them together. */
    private const val POOL = 12
    /** A word the next-word model does not know: as likely as its unknown word, less a little. */
    private const val UNKNOWN_WORD_COST = 2f

    /**
     * The [k] best next words (dictionary indices, best first) after context2 context1 ([NgramModel] context
     * ids) in [sentence] (the sentence so far, for [nextWord]; see GlideText.sentenceWords). [nextWord] must
     * be used by one thread at a time.
     */
    fun predict(
        dictionary: Dictionary, lm: NgramModel, nextWord: NextWordModel?, sentence: List<String?>?,
        context2: Int, context1: Int, k: Int,
    ): IntArray {
        if (nextWord == null || sentence == null || NEXT_WORD_WEIGHT == 0f) return lm.predict(context2, context1, k)
        val costs = nextWord.logProbs(sentence)
        val pool = LinkedHashSet<Int>()
        for (i in lm.predict(context2, context1, POOL)) pool += i
        for (id in bestIds(costs, POOL)) {
            val i = dictionary.indexOfLower(nextWord.vocab[id])
            if (i >= 0) pool += i
        }
        val unknown = nextWord.unknownCost + UNKNOWN_WORD_COST
        fun score(i: Int): Float {
            val id = nextWord.idOf(dictionary.lower[i])
            val nw = if (id == NextWordModel.UNK) unknown else costs[id]
            return NGRAM_WEIGHT * lm.cost3(i, context2, context1) + NEXT_WORD_WEIGHT * nw
        }
        return pool.sortedBy { score(it) }.take(k).toIntArray()
    }

    /** Indices of the [n] smallest [costs], smallest first. */
    private fun bestIds(costs: FloatArray, n: Int): IntArray {
        val best = IntArray(n) { -1 }
        val bestCost = FloatArray(n) { Float.POSITIVE_INFINITY }
        for (v in costs.indices) {
            val c = costs[v]
            if (c >= bestCost[n - 1]) continue
            var j = n - 1
            while (j > 0 && bestCost[j - 1] > c) {
                bestCost[j] = bestCost[j - 1]
                best[j] = best[j - 1]
                j--
            }
            bestCost[j] = c
            best[j] = v
        }
        return best.filter { it >= 0 }.toIntArray()
    }
}
