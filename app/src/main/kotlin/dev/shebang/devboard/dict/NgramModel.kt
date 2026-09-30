package dev.shebang.devboard.dict

import java.io.DataInputStream
import java.io.InputStream
import kotlin.math.ln

/**
 * Word-pair statistics for glide context, built by `tools/build_ngrams.py` from Tatoeba's English sentences.
 *
 * Probabilities are over the dictionary's words (indexed by [Dictionary] position). Unigrams mix real counts
 * with small SCOWL-tier pseudo-counts so every dictionary word has a prior; bigrams use Witten-Bell smoothing
 * back to that unigram. Immutable once built; safe to share across threads.
 */
class NgramModel private constructor(
    private val dictionary: Dictionary,
    /** -ln P(w) per dictionary index. */
    private val uniCost: FloatArray,
    /** LM id per dictionary index; 0 when the word has no LM entry. Ids start at 1; 0 is also the sentence start context. */
    private val lmIdOfWord: IntArray,
    private val ctxTotal: IntArray,
    private val ctxTypes: IntArray,
    private val offsets: IntArray,
    private val followers: IntArray,
    private val counts: IntArray,
    /** Dictionary index per LM id (index 0 unused). */
    private val wordOfLmId: IntArray,
) {
    /** -ln P(word) for a dictionary index. */
    fun unigramCost(word: Int): Float = uniCost[word]

    /** Context id for a word typed before the cursor (dictionary index), or [UNKNOWN] when it has none. */
    fun contextOf(word: Int): Int = if (word < 0) UNKNOWN else lmIdOfWord[word].let { if (it == 0) UNKNOWN else it }

    /** The dictionary index behind a context id, or -1 for the sentence start and unknown words. */
    fun wordOfContext(context: Int): Int = if (context <= 0) -1 else wordOfLmId[context]

    /**
     * -ln P(word | context). [context] is [SENTENCE_START], [UNKNOWN] or a value from [contextOf].
     * Allocation-free.
     */
    fun cost(word: Int, context: Int): Float {
        if (context == UNKNOWN) return uniCost[word]
        val total = ctxTotal[context]
        val types = ctxTypes[context]
        if (total == 0 || types == 0) return uniCost[word]
        val w = lmIdOfWord[word]
        var pairCount = 0
        if (w != 0) {
            var lo = offsets[context]
            var hi = offsets[context + 1] - 1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                val f = followers[mid]
                when {
                    f < w -> lo = mid + 1
                    f > w -> hi = mid - 1
                    else -> {
                        pairCount = counts[mid]
                        break
                    }
                }
            }
        }
        val pUni = kotlin.math.exp(-uniCost[word].toDouble())
        val p = (pairCount + types * pUni) / (total + types).toDouble()
        return (-ln(p)).toFloat()
    }

    val size: Int get() = dictionary.size

    companion object {
        const val SENTENCE_START = 0
        const val UNKNOWN = -1
        const val ASSET = "dict/en_ngrams.bin"

        /** Pseudo-count given to a word per unit of SCOWL tier weight, so unseen words still rank by tier. */
        private const val TIER_PSEUDO_COUNT = 2.0

        fun load(input: InputStream, dictionary: Dictionary): NgramModel {
            val data = DataInputStream(input.buffered(1 shl 16))
            val magic = ByteArray(4)
            data.readFully(magic)
            require(String(magic, Charsets.US_ASCII) == "SDNG") { "not an n-gram model" }
            require(data.readInt() == 1) { "unsupported n-gram model version" }
            val v = data.readInt()
            val lmIdOfWord = IntArray(dictionary.size)
            val wordOfLmId = IntArray(v + 1) { -1 }
            val lmCount = IntArray(v + 1)
            for (id in 1..v) {
                val word = data.readUTF()
                lmCount[id] = readVarint(data)
                val idx = dictionary.indexOfLower(word)
                if (idx >= 0) {
                    wordOfLmId[id] = idx
                    // Every dictionary spelling of this lowercase form shares the LM entry.
                    var i = idx
                    while (i >= 0 && dictionary.lower[i] == word) i--
                    i++
                    while (i < dictionary.size && dictionary.lower[i] == word) {
                        lmIdOfWord[i] = id
                        i++
                    }
                }
            }
            val totalUnigrams = readVarintLong(data).toDouble()

            // Unigram: real count plus a tier pseudo-count, shared by case variants of one lowercase form.
            var pseudoTotal = 0.0
            val pseudo = DoubleArray(dictionary.size)
            for (i in 0 until dictionary.size) {
                pseudo[i] = TIER_PSEUDO_COUNT * Dictionary.tierWeight(dictionary.tiers[i])
                pseudoTotal += pseudo[i]
            }
            val norm = totalUnigrams + pseudoTotal
            val uniCost = FloatArray(dictionary.size) { i ->
                val id = lmIdOfWord[i]
                val c = (if (id != 0) lmCount[id].toDouble() else 0.0) + pseudo[i]
                (-ln(c / norm)).toFloat()
            }

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
            return NgramModel(
                dictionary, uniCost, lmIdOfWord, ctxTotal, ctxTypes, offsets,
                followers.copyOf(n), counts.copyOf(n), wordOfLmId,
            )
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
