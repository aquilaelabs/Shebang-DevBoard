package dev.shebang.devboard.dict

import java.io.DataInputStream
import java.io.InputStream
import kotlin.math.exp
import kotlin.math.ln

/**
 * The n-gram asset as read, independent of any dictionary: vocabulary, unigram counts, bigram counts per
 * context and (version 2) trigram counts for frequent word pairs, built by `tools/build_ngrams.py` from
 * Tatoeba's and Common Voice's English sentences. Loaded once; immutable.
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
    /** Trigram contexts: (first LM id shl 32) or second, ascending; empty for a version 1 model. */
    val triKeys: LongArray = LongArray(0),
    val triTotal: IntArray = IntArray(0),
    val triTypes: IntArray = IntArray(0),
    /** Followers of trigram context i are triFollowers[triOffsets[i] until triOffsets[i + 1]], ascending. */
    val triOffsets: IntArray = IntArray(1),
    val triFollowers: IntArray = IntArray(0),
    val triCounts: IntArray = IntArray(0),
) {
    companion object {
        fun load(input: InputStream): NgramData {
            val data = DataInputStream(input.buffered(1 shl 16))
            val magic = ByteArray(4)
            data.readFully(magic)
            require(String(magic, Charsets.US_ASCII) == "SDNG") { "not an n-gram model" }
            val version = data.readInt()
            require(version == 1 || version == 2) { "unsupported n-gram model version" }
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
            if (version == 1) return NgramData(vocab, lmCount, totalUnigrams, ctxTotal, ctxTypes, offsets, followers.copyOf(n), counts.copyOf(n))
            val pairs = readVarint(data)
            val keys = LongArray(pairs)
            val tTotal = IntArray(pairs)
            val tTypes = IntArray(pairs)
            val tOffsets = IntArray(pairs + 1)
            var tFollowers = IntArray(1 shl 18)
            var tCounts = IntArray(1 shl 18)
            var m = 0
            for (i in 0 until pairs) {
                val first = readVarint(data)
                val second = readVarint(data)
                keys[i] = (first.toLong() shl 32) or second.toLong()
                tTotal[i] = readVarint(data)
                tTypes[i] = readVarint(data)
                val kept = readVarint(data)
                tOffsets[i] = m
                if (m + kept > tFollowers.size) {
                    val cap = maxOf(tFollowers.size * 2, m + kept)
                    tFollowers = tFollowers.copyOf(cap)
                    tCounts = tCounts.copyOf(cap)
                }
                var last = 0
                repeat(kept) {
                    last += readVarint(data)
                    tFollowers[m] = last
                    tCounts[m] = readVarint(data)
                    m++
                }
            }
            tOffsets[pairs] = m
            return NgramData(
                vocab, lmCount, totalUnigrams, ctxTotal, ctxTypes, offsets, followers.copyOf(n), counts.copyOf(n),
                keys, tTotal, tTypes, tOffsets, tFollowers.copyOf(m), tCounts.copyOf(m),
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
    /**
     * Rejections of a word right after a word, keyed (previous index, word index), in hundredths: the word costs
     * ln(1 + rejections) more there ([PersonalWords.reject]). Null without any.
     */
    private val rejected: LongIntMap? = null,
) {
    val size: Int get() = dictionary.size

    /** -ln P(word) for a dictionary index. */
    fun unigramCost(word: Int): Float = uniCost[word]

    /** Context id for the word [word] (dictionary index) before the cursor, or [UNKNOWN] when there is none. */
    fun contextOf(word: Int): Int = if (word < 0) UNKNOWN else word + 1

    /** The dictionary index behind a context id, or -1 for the sentence start and unknown words. */
    fun wordOfContext(context: Int): Int = if (context <= 0) -1 else context - 1

    /** -ln P(word | context), lowered where the user rejected the word after this one. Allocation-free. */
    fun cost(word: Int, context: Int): Float = costBase(word, context) + rejectedCost(word, context)

    /** What rejections of [word] after the word behind [context] add to its cost there: ln(1 + rejections). */
    private fun rejectedCost(word: Int, context: Int): Float {
        val r = rejected ?: return 0f
        if (context <= 0) return 0f
        val hundredths = r[LongIntMap.pair(context - 1, word)]
        return if (hundredths <= 0) 0f else ln(1.0 + hundredths / 100.0).toFloat()
    }

    private fun costBase(word: Int, context: Int): Float {
        if (context == UNKNOWN) return uniCost[word]
        val lmCtx = if (context == SENTENCE_START) 0 else lmIdOfWord[context - 1]
        val pUni = exp(-uniCost[word].toDouble())
        var p = pUni
        if (context == SENTENCE_START || lmCtx != 0) {
            val total = data.ctxTotal[lmCtx]
            val types = data.ctxTypes[lmCtx]
            // What pruning dropped from the context goes to the unigram along with the Witten-Bell share.
            if (total > 0 && types > 0) p = (pairCount(lmCtx, lmIdOfWord[word]) + (total - biKept[lmCtx] + types) * pUni) / (total + types).toDouble()
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

    /**
     * -ln P(word | context2 context1): the trigram, where the corpus has the pair, interpolated Witten-Bell
     * style with [cost] (the bigram with its personal mix); [cost] otherwise. Allocation-free.
     */
    fun cost3(word: Int, context2: Int, context1: Int): Float {
        val penalty = rejectedCost(word, context1)
        val bigram = costBase(word, context1)
        val t = trigramIndex(context2, context1)
        if (t < 0) return bigram + penalty
        val total = data.triTotal[t]
        val types = data.triTypes[t]
        if (total <= 0 || types <= 0) return bigram + penalty
        val p2 = exp(-bigram.toDouble())
        val c = followerCount(data.triFollowers, data.triCounts, data.triOffsets[t], data.triOffsets[t + 1] - 1, lmIdOfWord[word])
        return (-ln((c + (total - triKept[t] + types) * p2) / (total + types).toDouble())).toFloat() + penalty
    }

    /** The counts each context kept after pruning; totals are from before it. */
    private val biKept: LongArray by lazy { keptSums(data.offsets, data.counts) }
    private val triKept: LongArray by lazy { keptSums(data.triOffsets, data.triCounts) }

    private fun keptSums(offsets: IntArray, counts: IntArray): LongArray {
        if (offsets.isEmpty()) return LongArray(0)
        val out = LongArray(offsets.size - 1)
        for (i in out.indices) {
            var sum = 0L
            for (j in offsets[i] until offsets[i + 1]) sum += counts[j]
            out[i] = sum
        }
        return out
    }

    /** The trigram context for two context ids, or -1 when either is unknown or the corpus lacks the pair. */
    private fun trigramIndex(context2: Int, context1: Int): Int {
        if (context2 == UNKNOWN || context1 <= 0 || data.triKeys.isEmpty()) return -1
        val a = if (context2 == SENTENCE_START) 0 else lmIdOfWord[context2 - 1]
        val b = lmIdOfWord[context1 - 1]
        if ((a == 0 && context2 != SENTENCE_START) || b == 0) return -1
        val key = (a.toLong() shl 32) or b.toLong()
        val i = java.util.Arrays.binarySearch(data.triKeys, key)
        return if (i >= 0) i else -1
    }

    private fun followerCount(followers: IntArray, counts: IntArray, from: Int, to: Int, w: Int): Int {
        if (w == 0) return 0
        var lo = from
        var hi = to
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val f = followers[mid]
            when {
                f < w -> lo = mid + 1
                f > w -> hi = mid - 1
                else -> return counts[mid]
            }
        }
        return 0
    }

    /**
     * The [k] words most likely to come next after context2 context1 (dictionary indices, best first): the
     * trigram's and bigram's followers and the user's own, ranked by [cost3].
     */
    fun predict(context2: Int, context1: Int, k: Int): IntArray {
        if (context1 == UNKNOWN) return IntArray(0)
        val cands = HashSet<Int>()
        val t = trigramIndex(context2, context1)
        if (t >= 0) for (i in data.triOffsets[t] until data.triOffsets[t + 1]) dictOfLm(data.triFollowers[i])?.let { cands += it }
        val lmCtx = if (context1 == SENTENCE_START) 0 else lmIdOfWord[context1 - 1]
        if (context1 == SENTENCE_START || lmCtx != 0) {
            for (i in data.offsets[lmCtx] until data.offsets[lmCtx + 1]) dictOfLm(data.followers[i])?.let { cands += it }
        }
        val pairs = personalPairs
        if (pairs != null && context1 > 0) {
            // The user's own followers of this word.
            pairs.forEachKey { key ->
                if ((key ushr 32).toInt() == context1 - 1) cands += (key and 0xFFFFFFFFL).toInt()
            }
        }
        return cands.sortedBy { cost3(it, context2, context1) }.take(k).toIntArray()
    }

    /** The dictionary index of an LM id (its first spelling), or null. */
    private fun dictOfLm(lm: Int): Int? {
        val i = firstIndexOfLm.getOrNull(lm) ?: return null
        return if (i >= 0) i else null
    }

    private val firstIndexOfLm: IntArray by lazy {
        val out = IntArray(data.vocab.size) { -1 }
        for (i in lmIdOfWord.indices) {
            val lm = lmIdOfWord[i]
            if (lm > 0 && out[lm] < 0) out[lm] = i
        }
        out
    }

    /**
     * Whether [word] has been seen right after [first] (dictionary indices), in the corpus's pairs or the
     * user's own. A pair never seen gets its odds from how common [word] is overall, which says nothing
     * about the pair.
     */
    fun seenPair(first: Int, word: Int): Boolean {
        if (first !in lmIdOfWord.indices || word !in lmIdOfWord.indices) return false
        val a = lmIdOfWord[first]
        val b = lmIdOfWord[word]
        if (a != 0 && b != 0 && pairCount(a, b) > 0) return true
        val pairs = personalPairs ?: return false
        return pairs[LongIntMap.pair(first, word)] > 0
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
            // Rejections: per pair for the context they were made in, and per word once a word has been rejected
            // after several different words (a word wrong almost wherever it comes up), a little lower everywhere.
            var rejected: LongIntMap? = null
            val rejectedContexts = IntArray(dictionary.size)
            if (personal != null && personal.rejections.isNotEmpty()) {
                val map = LongIntMap(personal.rejections.size)
                for ((key, r) in personal.rejections) {
                    val cut = key.indexOf('\u0001')
                    val w = dictionary.indexOfLower(key.substring(cut + 1))
                    if (w < 0 || r < REJECT_COUNTS_FROM) continue
                    rejectedContexts[w]++
                    val prev = key.substring(0, cut)
                    if (prev == PersonalWords.NO_PREVIOUS) continue
                    val a = dictionary.indexOfLower(prev)
                    if (a >= 0) map.add(LongIntMap.pair(a, w), (r * 100).toInt())
                }
                rejected = map
            }
            val personalNorm = maxOf(personalTokens, PERSONAL_MIN_TOKENS)
            val mix = if (personalUni != null) PERSONAL_MIX * minOf(1.0, personalTokens / PERSONAL_MIN_TOKENS).coerceAtLeast(0.05) else 0.0
            val uniCost = FloatArray(dictionary.size) { i ->
                val id = lmIdOfWord[i]
                // Every word's value comes from the model's count, whatever list it is in; a word the model has no
                // count for starts from its tier. A floor pack's words (slang) ignore the count, so they start last.
                val counted = id != 0 && !WordPacks.startsAtFloor(dictionary.packs[i].toInt())
                val base = ((if (counted) data.lmCount[id].toDouble() else 0.0) + pseudo[i]) / norm
                val p = if (personalUni != null) (1 - mix) * base + mix * personalUni[i] / personalNorm else base
                (-ln(p * wordWideRejection(rejectedContexts[i]))).toFloat()
            }
            return NgramModel(dictionary, data, uniCost, lmIdOfWord, personalPairs, personalCtxTotal, rejected)
        }

        /** Rejections count from this much (they fade): a word rejected once, a while ago, is let be. */
        private const val REJECT_COUNTS_FROM = 0.5

        /**
         * How much of its value a word keeps everywhere when it was rejected after [contexts] different words:
         * all of it for up to two (the rejections then speak of those places), a quarter less for each one more,
         * never below half.
         */
        fun wordWideRejection(contexts: Int): Double = if (contexts <= 2) 1.0 else maxOf(0.5, 1.0 / (1.0 + 0.25 * (contexts - 2)))
    }
}
