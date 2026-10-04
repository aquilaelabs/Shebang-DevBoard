package dev.shebang.devboard.dict

import dev.shebang.devboard.store.JsonFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream

/**
 * Which word packs are on, and the word lists the user imported. Built-in packs are on unless turned off. An
 * imported list is copied into the app's private files (`word_lists/`), so the original file can go; it stays
 * on the phone and is never sent anywhere. One instance per process ([get]), shared by the keyboard and its
 * settings; [version] changes with every edit, so the keyboard knows to rebuild. Thread-safe.
 */
class WordPackStore(private val dir: File?) {
    /** A list the user imported: what it is called, how many words it gave, and whether it is on. */
    @Serializable
    data class ImportedList(val id: String, val name: String, val words: Int, val enabled: Boolean = true, val added: Long = 0)

    @Serializable
    private data class Stored(val disabled: List<String> = emptyList(), val lists: List<ImportedList> = emptyList())

    /** What an import did: the list added (null when nothing could be read), and the lines left out. */
    class ImportResult(val list: ImportedList?, val skipped: Int, val error: String? = null)

    private var state = Stored()
    private var loaded = false

    @Volatile
    var version = 0
        private set

    private val file get() = dir?.let { File(it, FILE) }
    private fun listFile(id: String) = dir?.let { File(File(it, LISTS_DIR), "$id.txt") }

    @Synchronized
    private fun load() {
        if (loaded) return
        loaded = true
        file?.let { JsonFile(it).read(Stored.serializer(), json) }?.let { state = it }
    }

    /** Whether the built-in pack stored under [key] ([WordPacks.BuiltIn.key]) is on. */
    @Synchronized
    fun isEnabled(key: String): Boolean {
        load()
        return key !in state.disabled
    }

    @Synchronized
    fun setEnabled(key: String, on: Boolean) {
        load()
        if (isEnabled(key) == on) return
        state = state.copy(disabled = if (on) state.disabled - key else state.disabled + key)
        save()
    }

    /** The imported lists, oldest first. */
    @Synchronized
    fun lists(): List<ImportedList> {
        load()
        return state.lists
    }

    @Synchronized
    fun setListEnabled(id: String, on: Boolean) {
        load()
        if (state.lists.none { it.id == id && it.enabled != on }) return
        state = state.copy(lists = state.lists.map { if (it.id == id) it.copy(enabled = on) else it })
        save()
    }

    @Synchronized
    fun delete(id: String) {
        load()
        if (state.lists.none { it.id == id }) return
        listFile(id)?.delete()
        state = state.copy(lists = state.lists.filter { it.id != id })
        save()
    }

    /** Reads a word list from [input] (see [parse]) and keeps it under [name]. Off the main thread. */
    @Synchronized
    fun import(name: String, input: InputStream, now: Long = System.currentTimeMillis()): ImportResult {
        load()
        if (state.lists.size >= MAX_LISTS) return ImportResult(null, 0, "You can keep up to $MAX_LISTS word lists. Delete one first.")
        val text = runCatching { readCapped(input) }.getOrElse { return ImportResult(null, 0, "The file could not be read.") }
            ?: return ImportResult(null, 0, "The file is larger than ${MAX_BYTES / 1_000_000} MB.")
        val (words, skipped) = parse(text.lineSequence())
        if (words.isEmpty()) return ImportResult(null, skipped, "No words found. Put one word on each line.")
        var id = "list${now}"
        while (state.lists.any { it.id == id }) id += "x"
        val f = listFile(id)
        if (f != null) {
            f.parentFile?.mkdirs()
            runCatching { f.writeText(words.joinToString("\n", postfix = "\n")) }.onFailure { return ImportResult(null, skipped, "The list could not be saved.") }
        }
        val list = ImportedList(id, name.ifBlank { "Word list" }.take(60), words.size, true, now)
        state = state.copy(lists = state.lists + list)
        save()
        return ImportResult(list, skipped)
    }

    /** The words of every imported list that is on, for building the dictionary. */
    @Synchronized
    fun enabledImportedWords(): List<String> {
        load()
        val out = ArrayList<String>()
        for (l in state.lists) {
            if (!l.enabled) continue
            val f = listFile(l.id) ?: continue
            runCatching { f.useLines { lines -> lines.filter { it.isNotBlank() }.forEach { out.add(it) } } }
        }
        return out
    }

    private fun save() {
        version++
        val f = file ?: return
        runCatching { JsonFile(f).write(Stored.serializer(), json, state) }
    }

    companion object {
        const val FILE = "word_packs.json"
        const val LISTS_DIR = "word_lists"
        /** At most this many words are kept from one list. */
        const val MAX_WORDS = 100_000
        const val MAX_LISTS = 20
        const val MAX_BYTES = 5_000_000
        private const val MAX_WORD_LENGTH = 48
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * The words of a list: one per line; `#` starts a comment line; anything after a tab or a comma is left
         * out, so a CSV or a word-frequency list works; surrounding quotes go. A line with a space inside (a
         * phrase), with no letter, or longer than 48 characters is skipped. Each spelling once, in file order,
         * at most [MAX_WORDS]. Returns the words and how many lines were skipped.
         */
        fun parse(lines: Sequence<String>): Pair<List<String>, Int> {
            val out = LinkedHashSet<String>()
            var skipped = 0
            for (raw in lines) {
                var line = raw.removePrefix("﻿").trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val cut = line.indexOfFirst { it == '\t' || it == ',' }
                if (cut >= 0) line = line.substring(0, cut).trim()
                line = line.trim('"', '\'', '“', '”').trim()
                val ok = line.isNotEmpty() && line.length <= MAX_WORD_LENGTH && line.none { it.isWhitespace() || it.isISOControl() } &&
                    line.any { it.isLetter() }
                if (!ok) {
                    skipped++
                    continue
                }
                if (out.size >= MAX_WORDS) {
                    skipped++
                    continue
                }
                out.add(line)
            }
            return out.toList() to skipped
        }

        /** The stream as text, or null when it is larger than [MAX_BYTES]. */
        private fun readCapped(input: InputStream): String? {
            val buf = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                buf.write(chunk, 0, n)
                if (buf.size() > MAX_BYTES) return null
            }
            return buf.toString(Charsets.UTF_8.name())
        }

        @Volatile private var instance: WordPackStore? = null

        fun get(filesDir: File): WordPackStore =
            instance ?: synchronized(this) { instance ?: WordPackStore(filesDir).also { instance = it } }
    }
}
