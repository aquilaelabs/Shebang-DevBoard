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
 * letters until that letter has its own evidence, and capped at [MAX_OFFSET].
 *
 * A bad day cannot do lasting harm. A glide that strays far from its letters teaches nothing; each
 * observation's pull is clipped to [MAX_PULL]; only [DAILY_BUDGET] observations count per day, which bounds
 * how far one day can move a key to a small fraction of it; and the state at the start of each of the last
 * [KEEP_DAYS] days is kept, so the user can go back to any of them. Thread-safe; offsets are read as snapshots.
 */
class GlideAdaptation(
    private val file: File?,
    private val today: () -> Int = { (System.currentTimeMillis() / 86_400_000L).toInt() },
) {

    @Serializable
    private data class State(
        val offU: List<Float> = emptyList(),
        val offV: List<Float> = emptyList(),
        val n: List<Float> = emptyList(),
        val gU: Float = 0f,
        val gV: Float = 0f,
        val gN: Float = 0f,
    )

    @Serializable
    private data class Snapshot(val day: Int, val state: State)

    @Serializable
    private data class Stored(
        val version: Int = 2,
        val offU: List<Float> = emptyList(),
        val offV: List<Float> = emptyList(),
        val n: List<Float> = emptyList(),
        val gU: Float = 0f,
        val gV: Float = 0f,
        val gN: Float = 0f,
        val glides: Int = 0,
        val corrections: Int = 0,
        val budgetDay: Int = 0,
        val budgetUsed: Int = 0,
        val snapshots: List<Snapshot> = emptyList(),
    )

    private val offU = FloatArray(26)
    private val offV = FloatArray(26)
    private val n = FloatArray(26)
    private var gU = 0f
    private var gV = 0f
    private var gN = 0f
    private var budgetDay = 0
    private var budgetUsed = 0
    private val snapshots = ArrayList<Snapshot>()
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
        applyState(State(s.offU, s.offV, s.n, s.gU, s.gV, s.gN))
        glides = s.glides
        corrections = s.corrections
        budgetDay = s.budgetDay
        budgetUsed = s.budgetUsed
        snapshots.clear()
        snapshots.addAll(s.snapshots)
        version++
    }

    @Synchronized
    fun save() {
        val f = file ?: return
        if (!dirty) return
        val cur = state()
        val s = Stored(
            offU = cur.offU, offV = cur.offV, n = cur.n, gU = gU, gV = gV, gN = gN,
            glides = glides, corrections = corrections, budgetDay = budgetDay, budgetUsed = budgetUsed,
            snapshots = snapshots.toList(),
        )
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(json.encodeToString(Stored.serializer(), s))
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
        dirty = false
    }

    private fun state() = State(offU.toList(), offV.toList(), n.toList(), gU, gV, gN)

    private fun applyState(s: State) {
        offU.fill(0f)
        offV.fill(0f)
        n.fill(0f)
        for (i in 0 until minOf(26, s.offU.size, s.offV.size, s.n.size)) {
            offU[i] = s.offU[i]
            offV[i] = s.offV[i]
            n[i] = s.n[i]
        }
        gU = s.gU
        gV = s.gV
        gN = s.gN
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
     * Learns from a kept glide's observations: triples (letter 0..25, du, dv), each the gap between where the
     * stroke passed the letter and the key centre the decoder used ([offsets] at the time). [weight] repeats
     * the update. Returns false when nothing was learned: the glide strayed too far from its letters to be
     * trusted, or today's budget is spent.
     */
    @Synchronized
    fun learn(observations: FloatArray, weight: Int = 1): Boolean {
        load()
        if (meanDistance(observations) > MAX_GLIDE_MEAN) return false
        val day = today()
        if (day != budgetDay) {
            // A new day: keep where things stood before it, for going back.
            snapshot(day)
            budgetDay = day
            budgetUsed = 0
        }
        val count = observations.size / 3 * weight
        if (budgetUsed + count > DAILY_BUDGET) return false
        budgetUsed += count
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
                offU[c] += clip(au - offU[c]) / n[c]
                offV[c] += clip(av - offV[c]) / n[c]
                gN = minOf(gN + 1f, MAX_GLOBAL_N)
                gU += clip(au - gU) / gN
                gV += clip(av - gV) / gN
            }
        }
        dirty = true
        version++
        return true
    }

    private fun clip(r: Float) = r.coerceIn(-MAX_PULL, MAX_PULL)

    private fun snapshot(day: Int) {
        if (snapshots.any { it.day == day }) return
        snapshots.add(Snapshot(day, state()))
        snapshots.removeAll { it.day <= day - KEEP_DAYS }
        while (snapshots.size > KEEP_DAYS) snapshots.removeAt(0)
    }

    /**
     * Learns from a correction: the original stroke re-aligned to the word the user meant, at twice the
     * weight of a kept glide. Only when the stroke plausibly was that word (it passed near its letters on
     * average): replacing a glide with an unrelated word is a change of mind, not a mis-glide, and forcing
     * the stroke onto it would teach nonsense. Returns whether it was used.
     */
    fun learnCorrection(observations: FloatArray): Boolean {
        if (!isPlausibleCorrection(observations)) return false
        return learn(observations, weight = 2)
    }

    /** Days (days since 1970, UTC) whose starting state can be gone back to, newest first. */
    @Synchronized
    fun restoreDays(): List<Int> {
        load()
        return snapshots.map { it.day }.sortedDescending()
    }

    /**
     * Goes back to how things stood at the start of [day] (or the nearest kept day before it; nothing at all
     * if it predates every snapshot and there was no learning then). Snapshots after it are dropped.
     */
    @Synchronized
    fun restore(day: Int) {
        load()
        val snap = snapshots.filter { it.day <= day }.maxByOrNull { it.day }
        if (snap != null) applyState(snap.state) else applyState(State())
        snapshots.removeAll { it.day > (snap?.day ?: Int.MIN_VALUE) }
        // Learning resumes today with a fresh budget and a fresh snapshot of the restored state.
        budgetDay = 0
        budgetUsed = 0
        dirty = true
        version++
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
        applyState(State())
        glides = 0
        corrections = 0
        budgetDay = 0
        budgetUsed = 0
        snapshots.clear()
        dirty = true
        version++
    }

    companion object {
        const val FILE = "glide_adaptation.json"
        const val MAX_OFFSET = 0.35f
        private const val SHRINK = 5f
        private const val MAX_LETTER_N = 200f
        private const val MAX_GLOBAL_N = 800f
        /** Single points farther than this from the key (in pitches) are misalignments, not habits. */
        private const val OUTLIER = 0.8f
        /** How far one observation can pull an estimate, before the 1/n step. */
        const val MAX_PULL = 0.25f
        /** A glide teaches only if it passed this close to its letters on average (key pitches). */
        const val MAX_GLIDE_MEAN = 0.45f
        /** A correction teaches only if its stroke passed this close to the meant word's letters on average (key pitches). */
        const val MAX_CORRECTION_MEAN = 0.4f
        /** Letter observations learned per day at most (about 80 words' worth). */
        const val DAILY_BUDGET = 400
        /** Days of starting states kept for going back. */
        const val KEEP_DAYS = 14

        fun meanDistance(observations: FloatArray): Double {
            var n = 0
            var sum = 0.0
            var i = 0
            while (i + 2 < observations.size) {
                sum += sqrt((observations[i + 1] * observations[i + 1] + observations[i + 2] * observations[i + 2]).toDouble())
                n++
                i += 3
            }
            return if (n == 0) Double.MAX_VALUE else sum / n
        }

        fun isPlausibleCorrection(observations: FloatArray): Boolean = meanDistance(observations) <= MAX_CORRECTION_MEAN

        private val json = Json { ignoreUnknownKeys = true }

        @Volatile
        private var instance: GlideAdaptation? = null

        fun get(filesDir: File): GlideAdaptation =
            instance ?: synchronized(this) { instance ?: GlideAdaptation(File(filesDir, FILE)).also { instance = it } }
    }
}
