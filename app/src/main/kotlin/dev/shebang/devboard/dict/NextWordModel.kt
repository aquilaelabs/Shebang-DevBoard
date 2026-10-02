package dev.shebang.devboard.dict

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.tanh

/**
 * How likely each word is next, from the whole sentence so far: an LSTM over words trained on the GPU
 * (tools/lm_model) on the same sentences as [NgramModel] plus English Wikinews. It reads further back than the
 * n-grams' two words, and the keyboard mixes the two: the n-grams carry the user's own words and pairs.
 *
 * [logProbs] reads a sentence's words and returns -ln P for every word of its vocabulary. A sentence that
 * extends the one read last continues from where that one stopped. Not thread-safe: one per user ([copyOf]).
 */
class NextWordModel private constructor(
    val vocab: Array<String>,
    private val emb: Int,
    private val hidden: Int,
    /** The word table, int8 per row with a scale per word: input embedding and output layer alike. */
    private val table: ByteArray,
    private val scales: FloatArray,
    private val wih: FloatArray,
    private val whh: FloatArray,
    private val bias: FloatArray,
    private val projW: FloatArray,
    private val projB: FloatArray,
    private val outB: FloatArray,
    private val ids: HashMap<String, Int>,
) {
    val size: Int get() = vocab.size
    private val h = FloatArray(hidden)
    private val c = FloatArray(hidden)
    private val x = FloatArray(emb)
    private val y = FloatArray(emb)
    private val gates = FloatArray(4 * hidden)
    private val costs = FloatArray(vocab.size)
    /** The ids last read, and whether [costs] is for exactly them. */
    private var read = IntArray(0)
    private var readCount = 0
    private var costsValid = false

    /**
     * -ln P(a word outside the vocabulary) after the sentence last read by [logProbs]: the share of its
     * unknown word, which [logProbs] itself marks impossible (it is never offered as a word).
     */
    var unknownCost = Float.POSITIVE_INFINITY
        private set

    /** The model's id for a lowercase word, or [UNK]. */
    fun idOf(lower: String): Int = ids[lower] ?: UNK

    /**
     * -ln P(next word) for each word of [vocab], after [words]: the sentence so far, lowercase, a null for a
     * number. The array is the model's own and changes with the next call.
     */
    fun logProbs(words: List<String?>): FloatArray {
        val want = IntArray(words.size + 1)
        want[0] = START
        for (i in words.indices) want[i + 1] = words[i]?.let { idOf(it) } ?: NUM
        // Continue from the sentence read last when this one extends it.
        var same = 0
        if (readCount > 0) {
            while (same < want.size && same < readCount && want[same] == read[same]) same++
            if (same < readCount) same = 0
        }
        if (same == 0) {
            h.fill(0f)
            c.fill(0f)
        }
        if (same == want.size && costsValid) return costs
        for (i in same until want.size) step(want[i])
        read = want
        readCount = want.size
        output()
        costsValid = true
        return costs
    }

    private fun step(id: Int) {
        val e = emb
        val hs = hidden
        val s = scales[id]
        val row = id * e
        for (k in 0 until e) x[k] = table[row + k] * s
        for (g in 0 until 4 * hs) {
            gates[g] = bias[g] + dot(wih, g * e, x, e) + dot(whh, g * hs, h, hs)
        }
        for (j in 0 until hs) {
            val i = sigmoid(gates[j])
            val f = sigmoid(gates[hs + j])
            val gg = tanh(gates[2 * hs + j])
            val o = sigmoid(gates[3 * hs + j])
            c[j] = f * c[j] + i * gg
            h[j] = o * tanh(c[j])
        }
    }

    private fun output() {
        for (k in 0 until emb) y[k] = projB[k] + dot(projW, k * hidden, h, hidden)
        var m = Float.NEGATIVE_INFINITY
        for (v in vocab.indices) {
            var s0 = 0f
            var s1 = 0f
            val row = v * emb
            var k = 0
            while (k < emb - 1) {
                s0 += table[row + k] * y[k]
                s1 += table[row + k + 1] * y[k + 1]
                k += 2
            }
            if (k < emb) s0 += table[row + k] * y[k]
            val logit = (s0 + s1) * scales[v] + outB[v]
            costs[v] = logit
            if (logit > m) m = logit
        }
        var sum = 0.0
        for (v in vocab.indices) sum += exp((costs[v] - m).toDouble())
        val lse = m + ln(sum).toFloat()
        unknownCost = lse - costs[UNK]
        // Specials are never a next word.
        for (v in vocab.indices) costs[v] = if (v < FIRST_WORD) Float.POSITIVE_INFINITY else lse - costs[v]
    }

    companion object {
        const val ASSET = "dict/en_next_word.bin"
        const val START = 1
        const val UNK = 2
        const val NUM = 3
        const val FIRST_WORD = 4

        private fun sigmoid(v: Float) = 1f / (1f + exp(-v))

        private fun dot(w: FloatArray, wo: Int, v: FloatArray, n: Int): Float {
            var s0 = 0f
            var s1 = 0f
            var s2 = 0f
            var s3 = 0f
            var k = 0
            while (k < n - 3) {
                s0 += w[wo + k] * v[k]
                s1 += w[wo + k + 1] * v[k + 1]
                s2 += w[wo + k + 2] * v[k + 2]
                s3 += w[wo + k + 3] * v[k + 3]
                k += 4
            }
            while (k < n) {
                s0 += w[wo + k] * v[k]
                k++
            }
            return (s0 + s1) + (s2 + s3)
        }

        /** Reads the asset written by tools/lm_model/export.py. */
        fun load(input: InputStream): NextWordModel {
            val data = input.use { it.readBytes() }
            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { buf.get(it) }
            require(String(magic, Charsets.US_ASCII) == "SNW1") { "not a next-word model" }
            val v = buf.int
            val e = buf.int
            val hs = buf.int
            val vocab = Array(v) {
                val len = buf.short.toInt() and 0xffff
                val b = ByteArray(len).also { buf.get(it) }
                String(b, Charsets.UTF_8)
            }
            val scales = FloatArray(v) { buf.float }
            val table = ByteArray(v * e).also { buf.get(it) }
            fun half(n: Int) = FloatArray(n) { halfToFloat(buf.short) }
            val wih = half(4 * hs * e)
            val whh = half(4 * hs * hs)
            val bih = half(4 * hs)
            val bhh = half(4 * hs)
            val projW = half(e * hs)
            val projB = half(e)
            val outB = half(v)
            require(!buf.hasRemaining()) { "next-word model has ${buf.remaining()} bytes left over" }
            val bias = FloatArray(4 * hs) { bih[it] + bhh[it] }
            val ids = HashMap<String, Int>(v * 2)
            for (i in FIRST_WORD until v) ids[vocab[i]] = i
            return NextWordModel(vocab, e, hs, table, scales, wih, whh, bias, projW, projB, outB, ids)
        }

        /** The same weights with working memory of its own, for another thread. */
        fun copyOf(m: NextWordModel) = NextWordModel(m.vocab, m.emb, m.hidden, m.table, m.scales, m.wih, m.whh, m.bias, m.projW, m.projB, m.outB, m.ids)

        private fun halfToFloat(hbits: Short): Float {
            val h = hbits.toInt() and 0xffff
            val sign = (h ushr 15) and 1
            val exponent = (h ushr 10) and 0x1f
            val mantissa = h and 0x3ff
            val bits = when {
                exponent == 0 && mantissa == 0 -> sign shl 31
                exponent == 0 -> {
                    var m = mantissa
                    var ex = -1
                    do {
                        ex++
                        m = m shl 1
                    } while (m and 0x400 == 0)
                    (sign shl 31) or ((127 - 15 - ex) shl 23) or ((m and 0x3ff) shl 13)
                }
                exponent == 0x1f -> (sign shl 31) or (0xff shl 23) or (mantissa shl 13)
                else -> (sign shl 31) or ((exponent - 15 + 127) shl 23) or (mantissa shl 13)
            }
            return java.lang.Float.intBitsToFloat(bits)
        }
    }
}
