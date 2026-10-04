package dev.shebang.devboard.glide

import dev.shebang.devboard.store.JsonFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File

/**
 * How the user's recent glides ended up, for Export diagnostics: a real-world measure of glide accuracy on
 * this phone. Each glided word gets one outcome once it is final (eight more glided words follow, or the
 * field changes): kept as glided, fixed from the strip, glided again, edited (typed or backspaced into),
 * deleted, or fixed by the next glide. Only the outcome and the word's length are kept, never the word; the
 * last [MAX] glides. What the keyboard cannot see is not counted: a word fixed later, after leaving the field,
 * or a wrong word never noticed counts as kept. Thread-safe.
 */
class GlideOutcomes(private val file: File?) {
    @Serializable
    private data class Stored(val codes: List<Int> = emptyList())

    private val codes = ArrayDeque<Int>()
    private var loaded = false
    private var dirty = false

    @Synchronized
    private fun load() {
        if (loaded) return
        loaded = true
        file?.let { JsonFile(it).read(Stored.serializer(), json) }?.let { codes.addAll(it.codes.takeLast(MAX)) }
    }

    /** One glided word's [outcome] (one of the constants), and how many letters it had. In memory; [save] writes. */
    @Synchronized
    fun add(outcome: Int, letters: Int) {
        load()
        codes.addLast(outcome * 32 + letters.coerceIn(0, 31))
        while (codes.size > MAX) codes.removeFirst()
        dirty = true
    }

    /** Writes the outcomes if they changed. Off the main thread. */
    @Synchronized
    fun save() {
        val f = file ?: return
        if (!dirty) return
        runCatching { JsonFile(f).write(Stored.serializer(), json, Stored(codes.toList())) }
        dirty = false
    }

    @Synchronized
    fun reset() {
        load()
        codes.clear()
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
            for ((i, name) in NAMES.withIndex()) put(name, JsonPrimitive(of.count { it / 32 == i }))
        }
        val all = codes.toList()
        return buildJsonObject {
            put("window", JsonPrimitive(MAX))
            put("all", counts(all))
            put("byLength", buildJsonObject {
                for ((label, range) in LENGTHS) put(label, counts(all.filter { it % 32 in range }))
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
        private val NAMES = listOf("kept", "fixedFromStrip", "glidedAgain", "edited", "deleted", "fixedByNextGlide")
        private val LENGTHS = listOf("1-2" to 0..2, "3" to 3..3, "4" to 4..4, "5" to 5..5, "6-7" to 6..7, "8+" to 8..31)
        private val json = Json { ignoreUnknownKeys = true }

        @Volatile private var instance: GlideOutcomes? = null

        fun get(filesDir: File): GlideOutcomes =
            instance ?: synchronized(this) { instance ?: GlideOutcomes(File(filesDir, FILE)).also { instance = it } }
    }
}
