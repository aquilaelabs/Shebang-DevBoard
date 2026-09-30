package dev.shebang.devboard.dict

import java.io.DataInputStream
import java.io.InputStream
import kotlin.math.exp
import kotlin.math.ln

/**
 * The n-gram asset as read, independent of any dictionary: vocabulary, unigram counts and bigram counts per
 * context, built by `tools/build_ngrams.py` from Tatoeba's English sentences. Loaded once; immutable.
 */
class NgramData private constructor(
    /** Words by LM id; index 0 is the sentence start and has no word. */
    val vocab: Array<String?>,
    val lmCount: IntArray,
    val totalUnigrams: Double,
    val ctxTotal: IntArray,
    val ctxTypes: IntArray,
    val offsets: IntArray,
    val followers: IntArray,
    val counts: IntArray,
) {
    companion object {
        fun load(input: InputStream): NgramData {
            val data = DataInputStream(input.buffered(1 shl 16))
            val magic = ByteArray(4)
            data.readFully(magic)
            require(String(magic, Charsets.US_ASCII) == "SDNG") { "not an n-gram model" }
            require(data.readInt() == 1) { "unsupported n-gram model version" }
            val v = data.readInt()
            val vocab = arrayOfNulls<String>(v + 1)
            val lmCount = IntArray(v + 1)
            for (id in 1..v) {
                vocab[id] = data.readUTF()
                lmCount[id] = readVarint(data)
            }
            val totalUnigrams = readVarintLong(data).toDouble()
            val ctxTotal = IntArray(v + 1)
            val ctxTypes = IntArray(v + 1)
            val offsets = IntArray(v + 2)
            var followers = IntArray(1 shl 19)
            var counts = IntArray(1 shl 19)
            var n = 0
            for (ctx in 0..v) {
                ctxTotal[ctx] = readVarint(data)
                ctxTypes[ctx] = readVarint(data)
                val kept = readVarint(data)
                offsets[ctx] = n
                if (n + kept > followers.size) {
                    val cap = maxOf(followers.size * 2, n + kept)
                    followers = followers.copyOf(cap)
                    counts = counts.copyOf(cap)
                }
                var last = 0
                repeat(kept) {
                    last += readVarint(data)
                    followers[n] = last
                    counts[n] = readVarint(data)
                    n++
                }
            }
            offsets[v + 1] = n
            return NgramData(vocab, lmCount, totalUnigrams, ctxTotal, ctxTypes, offsets, followers.copyOf(n), counts.copyOf(n))
        }

        private fun readVarint(data: DataInputStream): Int = readVarintLong(data).toInt()

        private fun readVarintLong(data: DataInputStream): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = data.readUnsignedByte()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }
    }
}

/**
 * Word and word-pair probabilities over one [Dictionary], for glide context and ranking.
 *
 * Unigrams mix the corpus counts with small SCOWL-tier pseudo-counts, so every dictionary word has a prior.
 * Bigrams use Witten-Bell smoothing back to that unigram. With a [PersonalSnapshot], the user's own word and
 * pair counts are mixed in: a share [PERSONAL_MIX] of the unigram comes from the user's words, and a context
 * the user has used often enough blends in which words the user put after it.
 *
 * Context ids are dictionary positions plus one ([contextOf]), so words outside the corpus (learned ones)
 * are contexts too. Immutable once built; safe to share across threads; lookups allocate nothing.
 */
class NgramModel private constructor(
    private val dictionary: Dictionary,
    private val data: NgramData,
    /** -ln P(w) per dictionary index. */
    private val uniCost: FloatArray,
    /** LM id per dictionary index; 0 when the word is not in the corpus. */
    private val lmIdOfWord: IntArray,
    /** Personal pair counts keyed (previous index, word index), and per-context totals; null without data. */
    private val personalPairs: LongIntMap?,
    private val personalCtxTotal: IntArray?,
) {
    val size: Int get() = dictionary.size

    /** -ln P(word) for a dictionary index. */
    fun unigramCost(word: Int): Float = uniCost[word]

    /** Context id for the word [word] (dictionary index) before the cursor, or [UNKNOWN] when there is none. */
    fun contextOf(word: Int): Int = if (word < 0) UNKNOWN else word + 1

    /** The dictionary index behind a context id, or -1 for the sentence start and unknown words. */
    fun wordOfContext(context: Int): Int = if (context <= 0) -1 else context - 1

    /** -ln P(word | context). Allocation-free. */
    fun cost(word: Int, context: Int): Float {
        if (context == UNKNOWN) return uniCost[word]
        val lmCtx = if (context == SENTENCE_START) 0 else lmIdOfWord[context - 1]
        val pUni = exp(-uniCost[word].toDouble())
        var p = pUni
        if (context == SENTENCE_START || lmCtx != 0) {
            val total = data.ctxTotal[lmCtx]
            val types = data.ctxTypes[lmCtx]
            if (total > 0 && types > 0) p = (pairCount(lmCtx, lmIdOfWord[word]) + types * pUni) / (total + types).toDouble()
        }
        val pairs = personalPairs
        val totals = personalCtxTotal
        if (pairs != null && totals != null && context > 0) {
            val ctxTotal = totals[context - 1]
            if (ctxTotal > 0) {
                val mix = PERSONAL_MIX * minOf(1.0, ctxTotal / PERSONAL_CONTEXT_EVIDENCE)
                val pPersonal = pairs[LongIntMap.pair(context - 1, word)].toDouble() / ctxTotal
                p = (1 - mix) * p + mix * pPersonal
            }
        }
        return (-ln(p)).toFloat()
    }

    private fun pairCount(lmCtx: Int, w: Int): Int {
        if (w == 0) return 0
        var lo = data.offsets[lmCtx]
        var hi = data.offsets[lmCtx + 1] - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val f = data.followers[mid]
            when {
                f < w -> lo = mid + 1
                f > w -> hi = mid - 1
                else -> return data.counts[mid]
            }
        }
        return 0
    }

    companion object {
        const val SENTENCE_START = 0
        const val UNKNOWN = -1
        const val ASSET = "dict/en_ngrams.bin"

        /** Pseudo-count given to a word per unit of SCOWL tier weight, so unseen words still rank by tier. */
        private const val TIER_PSEUDO_COUNT = 2.0
        /** Share of probability that comes from the user's own counts once there is enough of them. */
        const val PERSONAL_MIX = 0.3
        /** The user's counts are read as if from at least this many words, so a short history is not over-trusted. */
        private const val PERSONAL_MIN_TOKENS = 2000.0
        /** Uses of a context before its personal pairs count fully. */
        private const val PERSONAL_CONTEXT_EVIDENCE = 5.0

        /** The model without personal data, straight from the asset stream. */
        fun load(input: InputStream, dictionary: Dictionary): NgramModel = build(dictionary, NgramData.load(input), null)

        fun build(dictionary: Dictionary, data: NgramData, personal: PersonalSnapshot?): NgramModel {
            val lmIdOfWord = IntArray(dictionary.size)
            for (id in 1 until data.vocab.size) {
                val word = data.vocab[id] ?: continue
                var i = dictionary.indexOfLower(word)
                // Every dictionary spelling of this lowercase form shares the LM entry.
                while (i >= 0 && i < dictionary.size && dictionary.lower[i] == word) {
                    lmIdOfWord[i] = id
                    i++
                }
            }
            var pseudoTotal = 0.0
            val pseudo = DoubleArray(dictionary.size)
            for (i in 0 until dictionary.size) {
                pseudo[i] = TIER_PSEUDO_COUNT * Dictionary.tierWeight(dictionary.tiers[i])
                pseudoTotal += pseudo[i]
            }
            val norm = data.totalUnigrams + pseudoTotal

            // Personal counts, by dictionary index.
            var personalUni: IntArray? = null
            var personalPairs: LongIntMap? = null
            var personalCtxTotal: IntArray? = null
            var personalTokens = 0.0
            if (personal != null && personal.words.isNotEmpty()) {
                val uni = IntArray(dictionary.size)
                for (w in personal.words) {
                    val i = dictionary.indexOfLower(w.lower)
                    if (i >= 0) {
                        uni[i] += w.count
                        personalTokens += w.count
                    }
                }
                val pairs = LongIntMap(personal.pairs.size)
                val totals = IntArray(dictionary.size)
                for ((key, count) in personal.pairs) {
                    val cut = key.indexOf('\u0001')
                    val a = dictionary.indexOfLower(key.substring(0, cut))
                    val b = dictionary.indexOfLower(key.substring(cut + 1))
                    if (a < 0 || b < 0) continue
                    pairs.add(LongIntMap.pair(a, b), count)
                    totals[a] += count
                }
                personalUni = uni
                personalPairs = pairs
                personalCtxTotal = totals
            }
            val personalNorm = maxOf(personalTokens, PERSONAL_MIN_TOKENS)
            val mix = if (personalUni != null) PERSONAL_MIX * minOf(1.0, personalTokens / PERSONAL_MIN_TOKENS).coerceAtLeast(0.05) else 0.0
            val uniCost = FloatArray(dictionary.size) { i ->
                val id = lmIdOfWord[i]
                val base = ((if (id != 0) data.lmCount[id].toDouble() else 0.0) + pseudo[i]) / norm
                val p = if (personalUni != null) (1 - mix) * base + mix * personalUni[i] / personalNorm else base
                (-ln(p)).toFloat()
            }
            return NgramModel(dictionary, data, uniCost, lmIdOfWord, personalPairs, personalCtxTotal)
        }
    }
}
