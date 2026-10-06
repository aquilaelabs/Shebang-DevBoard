package dev.shebang.devboard.dict

import dev.shebang.devboard.store.JsonFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import dev.shebang.devboard.R

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
    /**
     * Why an import was refused: a string resource, with its number ([arg]) where it has one, or a plurals resource
     * ([plural]) counted by [arg].
     */
    class ImportError(val text: Int, val arg: Int? = null, val plural: Boolean = false)

    class ImportResult(val list: ImportedList?, val skipped: Int, val error: ImportError? = null)

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
        if (state.lists.size >= MAX_LISTS) return ImportResult(null, 0, ImportError(R.plurals.import_too_many, MAX_LISTS, plural = true))
        val text = runCatching { readCapped(input) }.getOrElse { return ImportResult(null, 0, ImportError(R.string.dict_unreadable)) }
            ?: return ImportResult(null, 0, ImportError(R.string.import_too_large, MAX_BYTES / 1_000_000))
        val (words, counts, skipped) = parseCounted(text.lineSequence())
        if (words.isEmpty()) return ImportResult(null, skipped, ImportError(R.string.import_no_words))
        val tiers = tiersFor(words, counts)
        var id = "list${now}"
        while (state.lists.any { it.id == id }) id += "x"
        val f = listFile(id)
        if (f != null) {
            f.parentFile?.mkdirs()
            runCatching { f.writeText(words.joinToString("\n", postfix = "\n") { "$it\t${tiers[it] ?: IMPORTED_TIER}" }) }
                .onFailure { return ImportResult(null, skipped, ImportError(R.string.import_not_saved)) }
        }
        val list = ImportedList(id, name.ifBlank { "Word list" }.take(60), words.size, true, now)
        state = state.copy(lists = state.lists + list)
        save()
        return ImportResult(list, skipped)
    }

    /** The words of every imported list that is on. */
    fun enabledImportedWords(): List<String> = enabledImportedTiers().map { it.first }

    /**
     * The words of every imported list that is on, each with its tier, for building the dictionary: from the
     * list's frequency column when it had one ([tiersFor]), otherwise [IMPORTED_TIER]. Lists saved before tiers
     * were kept hold bare words, which get [IMPORTED_TIER].
     */
    @Synchronized
    fun enabledImportedTiers(): List<Pair<String, Int>> {
        load()
        val out = ArrayList<Pair<String, Int>>()
        for (l in state.lists) {
            if (!l.enabled) continue
            val f = listFile(l.id) ?: continue
            runCatching {
                f.useLines { lines ->
                    for (line in lines) {
                        if (line.isBlank()) continue
                        val tab = line.indexOf('\t')
                        if (tab < 0) out += line to IMPORTED_TIER
                        else out += line.substring(0, tab) to (line.substring(tab + 1).trim().toIntOrNull() ?: IMPORTED_TIER)
                    }
                }
            }
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
        /**
         * Where an imported word starts when the list gives no frequency: with the rarest regular words, so the
         * keyboard's own words come first until the user's use lifts it (the owner's design, 6 Oct).
         */
        const val IMPORTED_TIER = 50

        /**
         * Tiers from a list's frequency column: its commonest quarter (on a log scale of its own counts) at 35, as
         * common as everyday words, the next quarter at 40, the rest at 50. Never above 35, so a list cannot push
         * the commonest English aside. Words without a count, or a list without counts, get [IMPORTED_TIER].
         */
        fun tiersFor(words: List<String>, counts: Map<String, Double>): Map<String, Int> {
            val max = counts.values.filter { it > 0 }.maxOrNull() ?: return emptyMap()
            if (max <= 1.0) return emptyMap()
            val top = kotlin.math.ln(max)
            val out = HashMap<String, Int>()
            for (w in words) {
                val c = counts[w] ?: continue
                if (c <= 0) continue
                val r = kotlin.math.ln(maxOf(c, 1.0)) / top
                out[w] = when {
                    r >= 0.75 -> 35
                    r >= 0.5 -> 40
                    else -> IMPORTED_TIER
                }
            }
            return out
        }
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * The words of a list: one per line; `#` starts a comment line; anything after a tab or a comma is left
         * out, so a CSV or a word-frequency list works; surrounding quotes go. A line with a space inside (a
         * phrase), with no letter, or longer than 48 characters is skipped. Each spelling once, in file order,
         * at most [MAX_WORDS]. Returns the words and how many lines were skipped.
         */
        fun parse(lines: Sequence<String>): Pair<List<String>, Int> = parseCounted(lines).let { (w, _, s) -> w to s }

        /**
         * As [parse], with the number in a list's second column when there is one (a word-frequency list,
         * "word<TAB>count" or "word,count"): the words, their counts, and the lines skipped.
         */
        fun parseCounted(lines: Sequence<String>): Triple<List<String>, Map<String, Double>, Int> {
            val out = LinkedHashSet<String>()
            val counts = HashMap<String, Double>()
            var skipped = 0
            for (raw in lines) {
                var line = raw.removePrefix("\uFEFF").trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val cut = line.indexOfFirst { it == '\t' || it == ',' }
                var count: Double? = null
                if (cut >= 0) {
                    count = line.substring(cut + 1).trim().split('\t', ',', ' ').firstOrNull()?.trim()?.trim('"', '\'')?.toDoubleOrNull()
                    line = line.substring(0, cut).trim()
                }
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
                if (count != null && count.isFinite() && count > 0) counts[line] = maxOf(counts[line] ?: 0.0, count)
            }
            return Triple(out.toList(), counts, skipped)
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
