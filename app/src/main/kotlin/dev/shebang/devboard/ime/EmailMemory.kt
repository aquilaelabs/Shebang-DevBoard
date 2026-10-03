package dev.shebang.devboard.ime

import dev.shebang.devboard.store.JsonFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Email addresses the user entered in email fields, offered on the strip as they type one again: kept only on
 * this phone, in the app's private files (left out of backups). At most [MAX_ADDRESSES]; the least used go
 * first. Thread-safe; one instance per process ([get]), shared by the keyboard and its settings.
 */
class EmailMemory(private val file: File?, private val now: () -> Long = { System.currentTimeMillis() }) {

    @Serializable
    data class Address(val address: String, val count: Int, val time: Long)

    @Serializable
    private data class Stored(val addresses: List<Address> = emptyList())

    private var addresses = ArrayList<Address>()
    private var loaded = false

    @Synchronized
    private fun load() {
        if (loaded) return
        loaded = true
        val f = file ?: return
        JsonFile(f).read(Stored.serializer(), json)?.let { addresses = ArrayList(it.addresses) }
    }

    @Synchronized
    private fun save() {
        val f = file ?: return
        JsonFile(f).write(Stored.serializer(), json, Stored(addresses))
    }

    /** The addresses, most used first. */
    @Synchronized
    fun list(): List<Address> {
        load()
        return addresses.sortedWith(ORDER)
    }

    /** Records each address in [text] (what an email field held); anything not shaped like one is ignored. */
    @Synchronized
    fun record(text: String) {
        val found = text.split(SEPARATORS).map { it.trim() }.filter { isAddress(it) }.distinctBy { it.lowercase() }
        if (found.isEmpty()) return
        load()
        val t = now()
        for (a in found) {
            val i = addresses.indexOfFirst { it.address.equals(a, ignoreCase = true) }
            if (i >= 0) addresses[i] = Address(a, addresses[i].count + 1, t) else addresses += Address(a, 1, t)
        }
        if (addresses.size > MAX_ADDRESSES) {
            addresses.sortWith(ORDER)
            while (addresses.size > MAX_ADDRESSES) addresses.removeAt(addresses.size - 1)
        }
        save()
    }

    /**
     * Up to [limit] addresses that begin with [typed] (ignoring case) and are longer than it, most used first;
     * with nothing typed, the most used.
     */
    @Synchronized
    fun matching(typed: String, limit: Int = 3): List<String> {
        load()
        return addresses.asSequence()
            .filter { it.address.length > typed.length && it.address.startsWith(typed, ignoreCase = true) }
            .sortedWith(ORDER).take(limit).map { it.address }.toList()
    }

    @Synchronized
    fun delete(address: String) {
        load()
        if (addresses.removeAll { it.address.equals(address, ignoreCase = true) }) save()
        file?.let { JsonFile(it).discardUnreadable() }
    }

    @Synchronized
    fun clear() {
        load()
        addresses.clear()
        save()
        file?.let { JsonFile(it).discardUnreadable() }
    }

    companion object {
        const val FILE = "emails.json"
        const val MAX_ADDRESSES = 50
        private val ORDER = compareByDescending<Address> { it.count }.thenByDescending { it.time }
        /** Between addresses in one field: spaces, commas and semicolons. */
        val SEPARATORS = Regex("[\\s,;]+")
        private val SHAPE = Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9\\-]+(\\.[A-Za-z0-9\\-]+)*\\.[A-Za-z]{2,}")
        private val json = Json { ignoreUnknownKeys = true }

        /** Whether [s] is shaped like an email address: a name, @, a domain with a dot and a top level. */
        fun isAddress(s: String): Boolean = s.length <= 254 && SHAPE.matches(s)

        @Volatile private var instance: EmailMemory? = null

        fun get(filesDir: File): EmailMemory =
            instance ?: synchronized(this) { instance ?: EmailMemory(File(filesDir, FILE)).also { instance = it } }
    }
}
