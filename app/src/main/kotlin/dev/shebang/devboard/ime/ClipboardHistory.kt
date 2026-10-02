package dev.shebang.devboard.ime

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Text and pictures the user copied lately, newest first, for the clipboard panel: kept only on this phone, in
 * the app's private files (left out of backups); a picture is a copy of the image in [imageDir]. Only what the
 * keyboard sees while it is up is recorded; copies an app marks sensitive (a password manager's) are never
 * kept. At most [MAX_ITEMS]; unpinned copies older than [KEEP_MS] go, and their pictures with them.
 * Thread-safe.
 */
class ClipboardHistory(private val file: File?, private val now: () -> Long = { System.currentTimeMillis() }) {

    /** Where pictures are kept: beside the history file. */
    val imageDir: File? get() = file?.parentFile?.resolve(IMAGE_DIR)

    @Serializable
    data class Item(
        val text: String,
        val time: Long,
        val pinned: Boolean = false,
        /** A picture: its file name in [imageDir] ([text] is then empty), and its type ("image/png"). */
        val image: String? = null,
        val mime: String? = null,
    ) {
        /** What identifies the item for pinning and removing. */
        val key: String get() = image?.let { IMAGE_KEY + it } ?: text
        val isImage: Boolean get() = image != null
    }

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
        put(Item(text, now()))
    }

    /**
     * Records a picture from [bytes] (at most [MAX_IMAGE_BYTES]) of type [mime]; the same picture again moves
     * to the top. Returns whether it was kept.
     */
    @Synchronized
    fun addImage(bytes: ByteArray, mime: String): Boolean {
        val dir = imageDir ?: return false
        if (bytes.isEmpty() || bytes.size > MAX_IMAGE_BYTES || !mime.startsWith("image/")) return false
        val ext = when (mime) {
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            else -> "jpg"
        }
        val name = sha1(bytes).take(20) + "." + ext
        dir.mkdirs()
        val f = File(dir, name)
        if (!f.exists()) f.writeBytes(bytes)
        put(Item("", now(), image = name, mime = mime))
        return true
    }

    private fun put(item: Item) {
        load()
        val old = items.firstOrNull { it.key == item.key }
        items.removeAll { it.key == item.key }
        items.add(0, item.copy(pinned = old?.pinned ?: false))
        prune()
        save()
    }

    /** The picture file of [item], or null. */
    fun imageFile(item: Item): File? = item.image?.let { n -> imageDir?.resolve(n) }?.takeIf { it.isFile }

    @Synchronized
    fun setPinned(key: String, pinned: Boolean) {
        load()
        val i = items.indexOfFirst { it.key == key }
        if (i < 0) return
        items[i] = items[i].copy(pinned = pinned)
        save()
    }

    @Synchronized
    fun remove(key: String) {
        load()
        val gone = items.filter { it.key == key }
        if (gone.isEmpty()) return
        items.removeAll { it.key == key }
        deleteImages(gone)
        save()
    }

    /** Forgets every unpinned copy. */
    @Synchronized
    fun clear() {
        load()
        val gone = items.filter { !it.pinned }
        items.removeAll { !it.pinned }
        deleteImages(gone)
        save()
    }

    private fun deleteImages(gone: List<Item>) {
        for (g in gone) g.image?.let { n -> if (items.none { it.image == n }) imageDir?.resolve(n)?.delete() }
    }

    /** Drops unpinned items past [KEEP_MS] and keeps at most [MAX_ITEMS] (pinned ones first). */
    private fun prune(): Boolean {
        val before = ArrayList(items)
        val cutoff = now() - KEEP_MS
        items.removeAll { !it.pinned && it.time < cutoff }
        if (items.size > MAX_ITEMS) {
            val keep = items.sortedWith(compareByDescending<Item> { it.pinned }.thenByDescending { it.time }).take(MAX_ITEMS).toSet()
            items.retainAll(keep)
        }
        deleteImages(before.filter { it !in items })
        return items.size != before.size
    }

    private fun sha1(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val FILE = "clipboard_history.json"
        const val IMAGE_DIR = "clip_images"
        private const val IMAGE_KEY = "image:"
        /** Bigger pictures are not kept. */
        const val MAX_IMAGE_BYTES = 5 * 1024 * 1024
        const val MAX_ITEMS = 20
        const val KEEP_MS = 24 * 60 * 60 * 1000L
        /** Longer copies (a whole document) are not kept. */
        const val MAX_CHARS = 20_000
        private val json = Json { ignoreUnknownKeys = true }
    }
}
