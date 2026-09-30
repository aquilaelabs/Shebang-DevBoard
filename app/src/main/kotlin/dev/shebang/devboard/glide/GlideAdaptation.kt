package dev.shebang.devboard.glide

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.math.sqrt

/**
 * How this user glides, learned on the device: where on each letter key their strokes actually pass,
 * relative to its centre. The decoder moves its idea of each key centre by these offsets (in key pitches).
 *
 * Learning comes from glides the user kept (weight 1) and, more strongly, from corrections (weight 2): the
 * original stroke re-aligned to the word the user meant. Each letter's offset is a running average that
 * never stops adapting (at least 1/[MAX_LETTER_N] per observation), shrunk toward the average over all
 * letters until that letter has its own evidence, and capped at [MAX_OFFSET] so a bad streak cannot move a
 * key off itself. Also counts glides and corrections. Thread-safe; offsets are read as snapshots.
 */
class GlideAdaptation(private val file: File?) {

    @Serializable
    private data class Stored(
        val version: Int = 1,
        val offU: List<Float> = emptyList(),
        val offV: List<Float> = emptyList(),
        val n: List<Float> = emptyList(),
        val gU: Float = 0f,
        val gV: Float = 0f,
        val gN: Float = 0f,
        val glides: Int = 0,
        val corrections: Int = 0,
    )

    private val offU = FloatArray(26)
    private val offV = FloatArray(26)
    private val n = FloatArray(26)
    private var gU = 0f
    private var gV = 0f
    private var gN = 0f
    private var loaded = false
    private var dirty = false

    var glides = 0
        private set
    var corrections = 0
        private set

    /** Changes with every update; snapshots can be reused while it stays the same. */
    @Volatile
    var version = 0
        private set

    @Synchronized
    fun load() {
        if (loaded) return
        loaded = true
        val f = file ?: return
        if (!f.exists()) return
        val s = runCatching { json.decodeFromString(Stored.serializer(), f.readText()) }.getOrNull() ?: return
        for (i in 0 until minOf(26, s.offU.size, s.offV.size, s.n.size)) {
            offU[i] = s.offU[i]
            offV[i] = s.offV[i]
            n[i] = s.n[i]
        }
        gU = s.gU
        gV = s.gV
        gN = s.gN
        glides = s.glides
        corrections = s.corrections
        version++
    }

    @Synchronized
    fun save() {
        val f = file ?: return
        if (!dirty) return
        val s = Stored(offU = offU.toList(), offV = offV.toList(), n = n.toList(), gU = gU, gV = gV, gN = gN, glides = glides, corrections = corrections)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(json.encodeToString(Stored.serializer(), s))
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
        dirty = false
    }

    /** Effective offsets: 26 horizontal then 26 vertical, in key pitches. A new array each call. */
    @Synchronized
    fun offsets(): FloatArray {
        val out = FloatArray(52)
        // The shared offset only counts once there is some evidence for it.
        val gTrust = minOf(1f, gN / 20f)
        for (c in 0 until 26) {
            var u = (n[c] * offU[c] + SHRINK * gU * gTrust) / (n[c] + SHRINK)
            var v = (n[c] * offV[c] + SHRINK * gV * gTrust) / (n[c] + SHRINK)
            val len = sqrt(u * u + v * v)
            if (len > MAX_OFFSET) {
                u *= MAX_OFFSET / len
                v *= MAX_OFFSET / len
            }
            out[c] = u
            out[26 + c] = v
        }
        return out
    }

    /**
     * Learns from observations: triples (letter 0..25, du, dv), each the gap between where the stroke passed
     * the letter and the key centre the decoder used ([offsets] at the time). [weight] repeats the update.
     */
    @Synchronized
    fun learn(observations: FloatArray, weight: Int = 1) {
        load()
        val eff = offsets()
        var i = 0
        while (i + 2 < observations.size) {
            val c = observations[i].toInt()
            val du = observations[i + 1]
            val dv = observations[i + 2]
            i += 3
            if (c !in 0..25 || kotlin.math.abs(du) > OUTLIER || kotlin.math.abs(dv) > OUTLIER) continue
            // Where this user actually touches the key, relative to its centre.
            val au = eff[c] + du
            val av = eff[26 + c] + dv
            repeat(weight) {
                n[c] = minOf(n[c] + 1f, MAX_LETTER_N)
                offU[c] += (au - offU[c]) / n[c]
                offV[c] += (av - offV[c]) / n[c]
                gN = minOf(gN + 1f, MAX_GLOBAL_N)
                gU += (au - gU) / gN
                gV += (av - gV) / gN
            }
        }
        dirty = true
        version++
    }

    /**
     * Learns from a correction: the original stroke re-aligned to the word the user meant, at twice the
     * weight of a kept glide. Only when the stroke plausibly was that word (it passed near its letters on
     * average): replacing a glide with an unrelated word is a change of mind, not a mis-glide, and forcing
     * the stroke onto it would teach nonsense. Returns whether it was used.
     */
    fun learnCorrection(observations: FloatArray): Boolean {
        if (!isPlausibleCorrection(observations)) return false
        learn(observations, weight = 2)
        return true
    }

    @Synchronized
    fun recordGlide() {
        load()
        glides++
        dirty = true
    }

    @Synchronized
    fun recordCorrection() {
        load()
        corrections++
        dirty = true
    }

    @Synchronized
    fun reset() {
        load()
        offU.fill(0f)
        offV.fill(0f)
        n.fill(0f)
        gU = 0f
        gV = 0f
        gN = 0f
        glides = 0
        corrections = 0
        dirty = true
        version++
    }

    companion object {
        const val FILE = "glide_adaptation.json"
        const val MAX_OFFSET = 0.35f
        private const val SHRINK = 5f
        private const val MAX_LETTER_N = 50f
        private const val MAX_GLOBAL_N = 200f
        /** Observations farther than this from the key (in pitches) are misalignments, not habits. */
        private const val OUTLIER = 0.8f
        /** A correction teaches only if its stroke passed this close to the meant word's letters on average (key pitches). */
        const val MAX_CORRECTION_MEAN = 0.4f

        fun isPlausibleCorrection(observations: FloatArray): Boolean {
            var n = 0
            var sum = 0.0
            var i = 0
            while (i + 2 < observations.size) {
                sum += kotlin.math.sqrt((observations[i + 1] * observations[i + 1] + observations[i + 2] * observations[i + 2]).toDouble())
                n++
                i += 3
            }
            return n > 0 && sum / n <= MAX_CORRECTION_MEAN
        }

        private val json = Json { ignoreUnknownKeys = true }

        @Volatile
        private var instance: GlideAdaptation? = null

        fun get(filesDir: File): GlideAdaptation =
            instance ?: synchronized(this) { instance ?: GlideAdaptation(File(filesDir, FILE)).also { instance = it } }
    }
}
