package dev.shebang.devboard.ime

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Text the user copied lately, newest first, for the clipboard panel: kept only on this phone, in the app's
 * private files (left out of backups). Only what the keyboard sees while it is up is recorded; copies an app
 * marks sensitive (a password manager's) are never kept. At most [MAX_ITEMS]; unpinned copies older than
 * [KEEP_MS] go. Thread-safe.
 */
class ClipboardHistory(private val file: File?, private val now: () -> Long = { System.currentTimeMillis() }) {

    @Serializable
    data class Item(val text: String, val time: Long, val pinned: Boolean = false)

    @Serializable
    private data class Stored(val items: List<Item> = emptyList())

    private var items = ArrayList<Item>()
    private var loaded = false

    @Synchronized
    private fun load() {
        if (loaded) return
        loaded = true
        val f = file ?: return
        if (!f.exists()) return
        runCatching { json.decodeFromString(Stored.serializer(), f.readText()) }.getOrNull()?.let { items = ArrayList(it.items) }
    }

    @Synchronized
    private fun save() {
        val f = file ?: return
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(json.encodeToString(Stored.serializer(), Stored(items)))
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
    }

    /** The items to show, newest first, pinned ones first. */
    @Synchronized
    fun list(): List<Item> {
        load()
        if (prune()) save()
        return items.sortedWith(compareByDescending<Item> { it.pinned }.thenByDescending { it.time })
    }

    /** Records a copy (the same text again moves to the top, keeping its pin). */
    @Synchronized
    fun add(text: String) {
        if (text.isBlank() || text.length > MAX_CHARS) return
        load()
        val old = items.firstOrNull { it.text == text }
        items.removeAll { it.text == text }
        items.add(0, Item(text, now(), old?.pinned ?: false))
        prune()
        save()
    }

    @Synchronized
    fun setPinned(text: String, pinned: Boolean) {
        load()
        val i = items.indexOfFirst { it.text == text }
        if (i < 0) return
        items[i] = items[i].copy(pinned = pinned)
        save()
    }

    @Synchronized
    fun remove(text: String) {
        load()
        if (items.removeAll { it.text == text }) save()
    }

    /** Forgets every unpinned copy. */
    @Synchronized
    fun clear() {
        load()
        items.removeAll { !it.pinned }
        save()
    }

    /** Drops unpinned items past [KEEP_MS] and keeps at most [MAX_ITEMS] (pinned ones first). */
    private fun prune(): Boolean {
        val before = items.size
        val cutoff = now() - KEEP_MS
        items.removeAll { !it.pinned && it.time < cutoff }
        if (items.size > MAX_ITEMS) {
            val keep = items.sortedWith(compareByDescending<Item> { it.pinned }.thenByDescending { it.time }).take(MAX_ITEMS).toSet()
            items.retainAll(keep)
        }
        return items.size != before
    }

    companion object {
        const val FILE = "clipboard_history.json"
        const val MAX_ITEMS = 20
        const val KEEP_MS = 24 * 60 * 60 * 1000L
        /** Longer copies (a whole document) are not kept. */
        const val MAX_CHARS = 20_000
        private val json = Json { ignoreUnknownKeys = true }
    }
}
