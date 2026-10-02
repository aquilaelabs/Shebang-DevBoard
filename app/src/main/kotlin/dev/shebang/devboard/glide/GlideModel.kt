package dev.shebang.devboard.glide

import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tanh

/**
 * A learned reading of a glide stroke: a small network trained on the FUTO swipe dataset's training split
 * (tools/glide_model) that says, point by point, how likely each key is meant there (or none). [cost] is how
 * unlikely a word's keys are given the whole stroke (CTC); the decoder adds it to each candidate's own cost.
 * Plain loops, no library: a convolution over the stroke's features, then two bidirectional GRU layers.
 *
 * Points are resampled [SPACING] key pitches apart, in pitches with the top letter row's centre at v = 0.5,
 * with times in ms. Not thread-safe: one per decoder.
 */
class GlideModel private constructor(
    private val conv: Int,
    private val hidden: Int,
    private val layers: Int,
    private val convW: FloatArray,
    private val convB: FloatArray,
    /** Per layer and direction (forward then reverse): weight_ih, weight_hh, bias_ih, bias_hh. */
    private val gru: Array<Array<FloatArray>>,
    private val outW: FloatArray,
    private val outB: FloatArray,
) {
    private var capT = 0
    private var feat = FloatArray(0)
    private var x0 = FloatArray(0)
    private var x1 = FloatArray(0)
    private var x2 = FloatArray(0)
    /** Log-probabilities of the last [read]: [T, 27], blank first. */
    private var logp = FloatArray(0)
    private var steps = 0
    private val gi = FloatArray(3 * hidden)
    private val gh = FloatArray(3 * hidden)
    private val h = FloatArray(hidden)

    private fun ensure(t: Int) {
        if (t <= capT) return
        capT = max(t, 2 * capT)
        feat = FloatArray(capT * FEATURES)
        x0 = FloatArray(capT * conv)
        x1 = FloatArray(capT * 2 * hidden)
        x2 = FloatArray(capT * 2 * hidden)
        logp = FloatArray(capT * CLASSES)
    }

    /** Reads a stroke of [count] points; [cost] then scores words against it. */
    fun read(u: FloatArray, v: FloatArray, t: FloatArray, count: Int) {
        val n = count
        steps = n
        if (n == 0) return
        ensure(n)
        features(u, v, t, n)
        // Convolution, kernel 5, padding 2, then ReLU.
        for (i in 0 until n) {
            for (o in 0 until conv) {
                var s = convB[o]
                val wo = o * FEATURES * 5
                for (k in 0 until 5) {
                    val j = i + k - 2
                    if (j < 0 || j >= n) continue
                    val fj = j * FEATURES
                    var w = wo + k
                    for (f in 0 until FEATURES) {
                        s += convW[w] * feat[fj + f]
                        w += 5
                    }
                }
                x0[i * conv + o] = if (s > 0f) s else 0f
            }
        }
        var input = x0
        var inSize = conv
        for (layer in 0 until layers) {
            val out = if (layer % 2 == 0) x1 else x2
            gruPass(gru[2 * layer], input, inSize, out, n, reverse = false)
            gruPass(gru[2 * layer + 1], input, inSize, out, n, reverse = true)
            input = out
            inSize = 2 * hidden
        }
        // Output layer and log-softmax.
        for (i in 0 until n) {
            val base = i * CLASSES
            var m = Float.NEGATIVE_INFINITY
            for (c in 0 until CLASSES) {
                val s = outB[c] + dot(outW, c * inSize, input, i * inSize, inSize)
                logp[base + c] = s
                if (s > m) m = s
            }
            var sum = 0f
            for (c in 0 until CLASSES) sum += exp(logp[base + c] - m)
            val lse = m + ln(sum)
            for (c in 0 until CLASSES) logp[base + c] -= lse
        }
    }

    /** One direction of one GRU layer, written to its half of [out] ([T, 2H]). */
    private fun gruPass(w: Array<FloatArray>, input: FloatArray, inSize: Int, out: FloatArray, n: Int, reverse: Boolean) {
        val wih = w[0]
        val whh = w[1]
        val bih = w[2]
        val bhh = w[3]
        val hs = hidden
        h.fill(0f)
        val half = if (reverse) hs else 0
        for (step in 0 until n) {
            val i = if (reverse) n - 1 - step else step
            val xi = i * inSize
            for (g in 0 until 3 * hs) {
                gi[g] = bih[g] + dot(wih, g * inSize, input, xi, inSize)
                gh[g] = bhh[g] + dot(whh, g * hs, h, 0, hs)
            }
            for (j in 0 until hs) {
                val r = sigmoid(gi[j] + gh[j])
                val z = sigmoid(gi[hs + j] + gh[hs + j])
                val nn = tanh(gi[2 * hs + j] + r * gh[2 * hs + j])
                h[j] = (1f - z) * nn + z * h[j]
            }
            System.arraycopy(h, 0, out, i * 2 * hs + half, hs)
        }
    }

    private fun features(u: FloatArray, v: FloatArray, t: FloatArray, n: Int) {
        val last = n - 1
        for (i in 0 until n) {
            val nx = min(i + 1, last)
            val pv = max(i - 1, 0)
            val f = i * FEATURES
            feat[f] = u[i] / 5f - 1f
            feat[f + 1] = v[i] / 1.5f - 1f
            feat[f + 2] = (u[nx] - u[pv]) / (2 * SPACING)
            feat[f + 3] = (v[nx] - v[pv]) / (2 * SPACING)
            val span = max(1, nx - pv).toFloat()
            feat[f + 4] = ln(1f + max(0f, (t[nx] - t[pv]) / span)) / 4f
            for (c in 0 until 26) {
                val du = u[i] - KEY_U[c]
                val dv = v[i] - KEY_V[c]
                feat[f + 5 + c] = exp(-(du * du + dv * dv) / (2f * 0.36f))
            }
        }
    }

    /**
     * -ln P(keys | the stroke last [read]), keys 0..25 with no letter twice in a row (as
     * [LexiconTrie.keySequence] gives them); [IMPOSSIBLE] when the stroke is too short for them.
     */
    fun cost(keys: IntArray, count: Int): Float {
        val n = steps
        if (count == 0 || n < count) return IMPOSSIBLE
        val states = 2 * count + 1
        var prev = DoubleArray(states) { Double.NEGATIVE_INFINITY }
        var next = DoubleArray(states)
        prev[0] = logp[0].toDouble()
        prev[1] = logp[keys[0] + 1].toDouble()
        for (i in 1 until n) {
            val base = i * CLASSES
            for (s in 0 until states) {
                var a = prev[s]
                if (s >= 1) a = logAdd(a, prev[s - 1])
                // A key may follow the key before it with no blank between, when they differ (always here).
                if (s >= 2 && s % 2 == 1) a = logAdd(a, prev[s - 2])
                val c = if (s % 2 == 0) 0 else keys[s / 2] + 1
                next[s] = a + logp[base + c]
            }
            val tmp = prev
            prev = next
            next = tmp
        }
        val ll = logAdd(prev[states - 1], prev[states - 2])
        return if (ll == Double.NEGATIVE_INFINITY) IMPOSSIBLE else (-ll).toFloat()
    }

    /** For tests: the log-probabilities of the last [read], [T, 27]. */
    fun logProbabilities(): FloatArray = logp.copyOf(steps * CLASSES)

    companion object {
        const val ASSET = "glide/glide_model.bin"
        const val SPACING = 0.25f
        const val FEATURES = 31
        private const val CLASSES = 27
        /** The cost of keys a stroke cannot carry (CTC finds no path). */
        const val IMPOSSIBLE = 1e4f

        private val KEY_U = FloatArray(26)
        private val KEY_V = FloatArray(26)

        init {
            val rows = arrayOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
            val start = floatArrayOf(0.5f, 1f, 2f)
            for (r in rows.indices) for ((i, ch) in rows[r].withIndex()) {
                KEY_U[ch - 'a'] = start[r] + i
                KEY_V[ch - 'a'] = r + 0.5f
            }
        }

        private fun sigmoid(x: Float) = 1f / (1f + exp(-x))

        /** a[ao until ao + n] . b[bo until bo + n], in four running sums (lets the JIT keep the units busy). */
        private fun dot(a: FloatArray, ao: Int, b: FloatArray, bo: Int, n: Int): Float {
            var s0 = 0f
            var s1 = 0f
            var s2 = 0f
            var s3 = 0f
            var k = 0
            val end4 = n - 3
            while (k < end4) {
                s0 += a[ao + k] * b[bo + k]
                s1 += a[ao + k + 1] * b[bo + k + 1]
                s2 += a[ao + k + 2] * b[bo + k + 2]
                s3 += a[ao + k + 3] * b[bo + k + 3]
                k += 4
            }
            while (k < n) {
                s0 += a[ao + k] * b[bo + k]
                k++
            }
            return (s0 + s1) + (s2 + s3)
        }

        private fun logAdd(a: Double, b: Double): Double {
            if (a == Double.NEGATIVE_INFINITY) return b
            if (b == Double.NEGATIVE_INFINITY) return a
            return if (a > b) a + kotlin.math.ln1p(exp(b - a)) else b + kotlin.math.ln1p(exp(a - b))
        }

        /** Reads the asset written by tools/glide_model/export.py. */
        fun load(input: InputStream): GlideModel {
            val data = DataInputStream(input.buffered()).use { it.readBytes() }
            val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(4).also { buf.get(it) }
            require(String(magic, Charsets.US_ASCII) == "SGM1") { "not a glide model" }
            val conv = buf.int
            val hidden = buf.int
            val layers = buf.int
            fun read(size: Int) = FloatArray(size) { halfToFloat(buf.short) }
            val convW = read(conv * FEATURES * 5)
            val convB = read(conv)
            val gru = Array(2 * layers) { k ->
                val inSize = if (k / 2 == 0) conv else 2 * hidden
                arrayOf(read(3 * hidden * inSize), read(3 * hidden * hidden), read(3 * hidden), read(3 * hidden))
            }
            val outW = read(CLASSES * 2 * hidden)
            val outB = read(CLASSES)
            require(!buf.hasRemaining()) { "glide model has ${buf.remaining()} bytes left over" }
            return GlideModel(conv, hidden, layers, convW, convB, gru, outW, outB)
        }

        /** The same weights for another decoder (each needs its own working memory). */
        fun copyOf(m: GlideModel) = GlideModel(m.conv, m.hidden, m.layers, m.convW, m.convB, m.gru, m.outW, m.outB)

        private fun halfToFloat(hbits: Short): Float {
            val h = hbits.toInt() and 0xffff
            val sign = (h ushr 15) and 1
            val exponent = (h ushr 10) and 0x1f
            val mantissa = h and 0x3ff
            val bits = when {
                exponent == 0 && mantissa == 0 -> sign shl 31
                exponent == 0 -> {
                    // Subnormal: normalise.
                    var m = mantissa
                    var e = -1
                    do {
                        e++
                        m = m shl 1
                    } while (m and 0x400 == 0)
                    (sign shl 31) or ((127 - 15 - e) shl 23) or ((m and 0x3ff) shl 13)
                }
                exponent == 0x1f -> (sign shl 31) or (0xff shl 23) or (mantissa shl 13)
                else -> (sign shl 31) or ((exponent - 15 + 127) shl 23) or (mantissa shl 13)
            }
            return java.lang.Float.intBitsToFloat(bits)
        }
    }
}
