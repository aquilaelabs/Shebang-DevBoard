package dev.shebang.devboard.store

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * One JSON document in the app's private files, for the stores that keep what the keyboard learns.
 *
 * Written so a crash, a power cut or a full disk part-way never damages it: the new text goes to a
 * temporary file, is synced to storage, and then replaces the old one in a single atomic rename. Read so
 * that a file this version cannot parse is never silently replaced: it is set aside as [unreadable] (a
 * later version may still read it) and the store starts empty; the next save writes a new file beside it.
 * Deleting what the user asked to delete also discards that copy ([discardUnreadable]), so nothing they
 * deleted lingers in it.
 */
class JsonFile(val file: File) {
    private val tmp: File get() = File(file.parentFile, file.name + ".tmp")

    /** Where a file that could not be read is kept instead of being overwritten. */
    val unreadable: File get() = File(file.parentFile, file.name + ".unreadable")

    /** The document, or null when there is none or it cannot be read (then it is set aside). */
    fun <T> read(serializer: KSerializer<T>, json: Json): T? {
        if (file.exists()) {
            parse(file, serializer, json)?.let { return it }
            runCatching { Files.move(file.toPath(), unreadable.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        }
        // A save stopped between writing and renaming leaves its finished temporary file.
        if (tmp.exists()) parse(tmp, serializer, json)?.let { return it }
        return null
    }

    /** Replaces the document with [value], atomically. Call off the main thread. */
    fun <T> write(serializer: KSerializer<T>, json: Json, value: T) {
        file.parentFile?.mkdirs()
        FileOutputStream(tmp).use { out ->
            out.write(json.encodeToString(serializer, value).toByteArray())
            out.fd.sync()
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /** Forgets a copy set aside as unreadable: after the user deletes something, nothing of it may remain. */
    fun discardUnreadable() {
        unreadable.delete()
    }

    /** Removes the document and everything kept beside it. */
    fun delete() {
        file.delete()
        tmp.delete()
        unreadable.delete()
    }

    private fun <T> parse(f: File, serializer: KSerializer<T>, json: Json): T? =
        runCatching { json.decodeFromString(serializer, f.readText()) }.getOrNull()
}
