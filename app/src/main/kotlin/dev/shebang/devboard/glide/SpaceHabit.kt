package dev.shebang.devboard.glide

import dev.shebang.devboard.store.JsonFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Whether turning a low tap on a letter into a space ([TapModel.meansSpace]) helps this user: each such
 * space is kept or taken back with the very next key (backspace). Most people keep nearly all of them (on
 * TSI about 3% would be wrong); someone whose taps simply land low on the letters takes many back, and for
 * them the help turns off ([enabled]) until adaptation is reset. Recent ones count most: both tallies fade by
 * [FADE] at each new one. Kept in the app's private files with the other learned data. Thread-safe.
 */
class SpaceHabit(private val file: File?) {
    @Serializable
    private data class Stored(val undone: Float = 0f, val kept: Float = 0f, val off: Boolean = false)

    private var state = Stored()
    private var loaded = false
    private var dirty = false

    @Synchronized
    private fun load() {
        if (loaded) return
        loaded = true
        file?.let { JsonFile(it).read(Stored.serializer(), json) }?.let { state = it }
    }

    /** Whether letters may turn into spaces for this user. */
    @Synchronized
    fun enabled(): Boolean {
        load()
        return !state.off
    }

    /**
     * One space made from a letter tap was taken back with backspace ([undone]) or kept. Returns [enabled].
     * Only in memory: [save] writes it (off the main thread).
     */
    @Synchronized
    fun record(undone: Boolean): Boolean {
        load()
        if (state.off) return false
        val u = state.undone * FADE + (if (undone) 1f else 0f)
        val k = state.kept * FADE + (if (undone) 0f else 1f)
        state = Stored(u, k, off = u >= MIN_UNDONE && u / (u + k) > MAX_UNDONE_SHARE)
        dirty = true
        return !state.off
    }

    @Synchronized
    fun reset() {
        load()
        state = Stored()
        dirty = true
        save()
        file?.let { JsonFile(it).discardUnreadable() }
    }

    /** The tallies, for a diagnostics export. */
    @Synchronized
    fun summary(): Triple<Float, Float, Boolean> {
        load()
        return Triple(state.undone, state.kept, !state.off)
    }

    /** Writes the tallies if they changed. Off the main thread. */
    @Synchronized
    fun save() {
        val f = file ?: return
        if (!dirty) return
        runCatching { JsonFile(f).write(Stored.serializer(), json, state) }
        dirty = false
    }

    companion object {
        const val FILE = "space_habit.json"
        /** How much each earlier space still counts when a new one comes (about the last 30 count). */
        const val FADE = 0.97f
        /** At least this many (faded) taken back, and more than this share of them, turn the help off. */
        const val MIN_UNDONE = 3f
        const val MAX_UNDONE_SHARE = 0.2f
        private val json = Json { ignoreUnknownKeys = true }

        @Volatile private var instance: SpaceHabit? = null

        fun get(filesDir: File): SpaceHabit =
            instance ?: synchronized(this) { instance ?: SpaceHabit(File(filesDir, FILE)).also { instance = it } }
    }
}
