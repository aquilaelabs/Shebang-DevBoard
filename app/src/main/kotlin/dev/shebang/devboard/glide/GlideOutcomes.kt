package dev.shebang.devboard.glide

import dev.shebang.devboard.store.JsonFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import kotlin.math.hypot

/**
 * How the user's recent glides ended up, for Export diagnostics: a real-world measure of glide accuracy on
 * this phone. Each glided word gets one outcome once it is final (eight more glided words follow, or the
 * field changes): kept as glided, fixed from the strip, glided again, edited (typed or backspaced into),
 * deleted, or fixed by the next glide. Only the outcome, the word's length, and how far and how long the stroke
 * went (each as a coarse band) are kept, never the word or the stroke; the last [MAX] glides. The bands tell a
 * glide made on purpose from a tap that slid (R40). For a glided word deleted on its own, also whether the next word
 * written was the same word, one the strip offered for it, or another (counts only; R23). What the keyboard cannot see is not counted: a word fixed later, after leaving the field,
 * or a wrong word never noticed counts as kept. Thread-safe.
 */
class GlideOutcomes(private val file: File?) {
    @Serializable
    private data class Stored(val codes: List<Int> = emptyList(), val replaced: List<Int> = emptyList())

    private val codes = ArrayDeque<Int>()
    /** Deleted glided words and what replaced them: how * 32 + letters, the last [MAX]. */
    private val replaced = ArrayDeque<Int>()
    private var loaded = false
    private var dirty = false

    @Synchronized
    private fun load() {
        if (loaded) return
        loaded = true
        file?.let { JsonFile(it).read(Stored.serializer(), json) }?.let {
            codes.addAll(it.codes.takeLast(MAX))
            replaced.addAll(it.replaced.takeLast(MAX))
        }
    }

    /**
     * One glided word's [outcome] (one of the constants), how many letters it had, and its stroke's [reach] (the
     * farthest it went from where the finger came down, in key pitches) and [durationMs], each negative when not
     * known. In memory; [save] writes.
     */
    @Synchronized
    fun add(outcome: Int, letters: Int, reach: Float = -1f, durationMs: Long = -1L) {
        load()
        var code = outcome * 32 + letters.coerceIn(0, 31)
        if (reach >= 0f && durationMs >= 0L) {
            code += STROKE_KNOWN + band(reach, REACH_BANDS) * REACH_UNIT + band(durationMs.toFloat(), DURATION_BANDS) * DURATION_UNIT
        }
        codes.addLast(code)
        while (codes.size > MAX) codes.removeFirst()
        dirty = true
    }

    /** A glided word of [letters] letters deleted on its own, and what the next word written was ([how]). */
    @Synchronized
    fun addReplacement(how: Int, letters: Int) {
        load()
        replaced.addLast(how * 32 + letters.coerceIn(0, 31))
        while (replaced.size > MAX) replaced.removeFirst()
        dirty = true
    }

    /** Writes the outcomes if they changed. Off the main thread. */
    @Synchronized
    fun save() {
        val f = file ?: return
        if (!dirty) return
        runCatching { JsonFile(f).write(Stored.serializer(), json, Stored(codes.toList(), replaced.toList())) }
        dirty = false
    }

    @Synchronized
    fun reset() {
        load()
        codes.clear()
        replaced.clear()
        dirty = true
        save()
        file?.let { JsonFile(it).discardUnreadable() }
    }

    /** Counts by outcome, overall and by word length, for a diagnostics export. */
    @Synchronized
    fun summary(): JsonObject {
        load()
        fun counts(of: List<Int>) = buildJsonObject {
            put("glides", JsonPrimitive(of.size))
            for ((i, name) in NAMES.withIndex()) put(name, JsonPrimitive(of.count { outcomeOf(it) == i }))
        }
        // Glides saved before the stroke was kept have no bands, and are left out of those counts.
        val known = codes.filter { it and STROKE_KNOWN != 0 }
        fun banded(labels: List<String>, unit: Int, of: List<Int>) = buildJsonObject {
            for ((i, label) in labels.withIndex()) put(label, counts(of.filter { (it / unit) % 4 == i }))
        }
        val all = codes.toList()
        return buildJsonObject {
            put("window", JsonPrimitive(MAX))
            put("all", counts(all))
            put("byLength", buildJsonObject {
                for ((label, range) in LENGTHS) put(label, counts(all.filter { it % 32 in range }))
            })
            put("strokesKnown", JsonPrimitive(known.size))
            put("byReach", banded(REACH_LABELS, REACH_UNIT, known))
            put("byDuration", banded(DURATION_LABELS, DURATION_UNIT, known))
            // The words a slid tap would make: how far their strokes went.
            put("oneOrTwoLettersByReach", banded(REACH_LABELS, REACH_UNIT, known.filter { it % 32 in 1..2 }))
            // A glided word deleted on its own, and the next word written: was the fix one the strip offered?
            fun replacements(of: List<Int>) = buildJsonObject {
                put("deleted", JsonPrimitive(of.size))
                for ((i, name) in REPLACED_NAMES.withIndex()) put(name, JsonPrimitive(of.count { it / 32 == i }))
            }
            val r = replaced.toList()
            put("replacedAfterDelete", buildJsonObject {
                put("all", replacements(r))
                put("oneOrTwoLetters", replacements(r.filter { it % 32 in 1..2 }))
                put("threeOrMoreLetters", replacements(r.filter { it % 32 >= 3 }))
            })
        }
    }

    companion object {
        const val FILE = "glide_outcomes.json"
        const val MAX = 500
        const val KEPT = 0
        const val STRIP = 1
        const val REDONE = 2
        const val EDITED = 3
        const val DELETED = 4
        const val NEXT_GLIDE = 5
        /** What replaced a deleted glided word: itself again, a word the strip offered for it, another word. */
        const val REPLACED_SAME = 0
        const val REPLACED_OFFERED = 1
        const val REPLACED_OTHER = 2
        private val REPLACED_NAMES = listOf("bySameWord", "byAWordTheStripOffered", "byAnotherWord")
        private val NAMES = listOf("kept", "fixedFromStrip", "glidedAgain", "edited", "deleted", "fixedByNextGlide")
        private val LENGTHS = listOf("1" to 0..1, "2" to 2..2, "3" to 3..3, "4" to 4..4, "5" to 5..5, "6-7" to 6..7, "8+" to 8..31)
        /** Bits above the length (5) and the outcome (3): reach band, duration band, and whether they are known. */
        private const val REACH_UNIT = 256
        private const val DURATION_UNIT = 1024
        private const val STROKE_KNOWN = 4096
        /** Band edges: reach in key pitches, duration in milliseconds; four bands each. */
        private val REACH_BANDS = floatArrayOf(1f, 2f, 4f)
        private val REACH_LABELS = listOf("under1Key", "1to2Keys", "2to4Keys", "4KeysOrMore")
        private val DURATION_BANDS = floatArrayOf(150f, 300f, 600f)
        private val DURATION_LABELS = listOf("under150ms", "150to300ms", "300to600ms", "600msOrMore")

        private fun outcomeOf(code: Int) = (code / 32) % 8

        private fun band(value: Float, edges: FloatArray): Int = edges.count { value >= it }

        /** How far [stroke] (x,y pairs) went from its first point, in its own units; -1 when there is none. */
        fun reach(stroke: FloatArray?): Float {
            if (stroke == null || stroke.size < 2) return -1f
            var far = 0f
            for (i in 2 until stroke.size - 1 step 2) {
                far = maxOf(far, hypot(stroke[i] - stroke[0], stroke[i + 1] - stroke[1]))
            }
            return far
        }

        private val json = Json { ignoreUnknownKeys = true }

        @Volatile private var instance: GlideOutcomes? = null

        fun get(filesDir: File): GlideOutcomes =
            instance ?: synchronized(this) { instance ?: GlideOutcomes(File(filesDir, FILE)).also { instance = it } }
    }
}
