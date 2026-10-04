package dev.shebang.devboard.dict

import dev.shebang.devboard.store.JsonFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Words the user removed from the keyboard's built-in dictionary (exact spellings, as the list writes them):
 * they are not offered, glided or corrected to, and learned words do not bring them back. Restoring one, or
 * all, puts the built-in word back. Android's personal dictionary is not affected. Kept in the app's private
 * files; one instance per process ([get]), shared by the keyboard and its settings; [version] changes with
 * every edit, so the keyboard knows to rebuild. Thread-safe.
 */
class RemovedWords(private val file: File?) {
    @Serializable
    private data class Stored(val words: List<String> = emptyList())

    private val words = HashSet<String>()
    private var loaded = false

    @Volatile
    var version = 0
        private set

    @Synchronized
    private fun load() {
        if (loaded) return
        loaded = true
        file?.let { JsonFile(it).read(Stored.serializer(), json) }?.let { words.addAll(it.words) }
    }

    /** The removed words, in dictionary order (case-insensitive). */
    @Synchronized
    fun list(): List<String> {
        load()
        return words.sortedWith(compareBy({ it.lowercase() }, { it }))
    }

    /** A copy of the set, for building the dictionary without them. */
    @Synchronized
    fun snapshot(): Set<String> {
        load()
        return HashSet(words)
    }

    @Synchronized
    fun remove(word: String) {
        load()
        if (words.add(word)) save()
    }

    @Synchronized
    fun restore(word: String) {
        load()
        if (words.remove(word)) save()
    }

    @Synchronized
    fun restoreAll() {
        load()
        if (words.isEmpty()) return
        words.clear()
        save()
    }

    /** Writes the set. Off the main thread. */
    private fun save() {
        version++
        val f = file ?: return
        runCatching { JsonFile(f).write(Stored.serializer(), json, Stored(list())) }
    }

    companion object {
        const val FILE = "removed_words.json"
        private val json = Json { ignoreUnknownKeys = true }

        @Volatile private var instance: RemovedWords? = null

        fun get(filesDir: File): RemovedWords =
            instance ?: synchronized(this) { instance ?: RemovedWords(File(filesDir, FILE)).also { instance = it } }
    }
}
