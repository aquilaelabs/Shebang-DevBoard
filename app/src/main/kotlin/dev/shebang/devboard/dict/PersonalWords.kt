package dev.shebang.devboard.dict

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** One word of the personal vocabulary, as the settings screen lists it. */
data class PersonalWord(
    val lower: String,
    /** How to write it: the dictionary's casing for known words, or the casing it was typed with. */
    val display: String,
    val count: Int,
    /** In the dictionary, or used often enough ([PersonalWords.NEW_WORD_THRESHOLD]) to be treated as a word. */
    val known: Boolean,
    /** From Android's own user dictionary rather than learned here. */
    val system: Boolean = false,
)

/** An immutable copy of the personal vocabulary, for building the dictionary and language model. */
class PersonalSnapshot(
    val words: List<PersonalWord>,
    /** Pair counts keyed "previous\u0001word", both lowercase. */
    val pairs: Map<String, Int>,
    val totalTokens: Int,
) {
    companion object {
        val EMPTY = PersonalSnapshot(emptyList(), emptyMap(), 0)
        fun pairKey(prev: String, word: String) = prev + "\u0001" + word
    }
}

/**
 * Words the user types or glides, and which word followed which, learned on the device. Kept in the app's
 * private files (excluded from backups) and never sent anywhere. The keyboard decides which fields may teach
 * it (never passwords, terminals or fields that ask for no personalised learning); this class only counts.
 *
 * A word missing from the dictionary becomes a word after [NEW_WORD_THRESHOLD] uses, so one typo is not
 * learned. The vocabulary is bounded: past [MAX_WORDS] words or [MAX_PAIRS] pairs, the least used (with
 * older use counting less) are forgotten. Thread-safe.
 */
class PersonalWords(private val file: File?, private val today: () -> Int = { (System.currentTimeMillis() / 86_400_000L).toInt() }) {

    @Serializable
    private data class StoredWord(val w: String, val d: String, val c: Int, val t: Int, val k: Boolean)

    @Serializable
    private data class StoredPair(val p: String, val w: String, val c: Int, val t: Int)

    @Serializable
    private data class Stored(val version: Int = 1, val words: List<StoredWord> = emptyList(), val pairs: List<StoredPair> = emptyList(), val total: Int = 0)

    private class Entry(var display: String, var count: Int, var lastDay: Int, var known: Boolean)
    private class PairEntry(var count: Int, var lastDay: Int)

    private val words = HashMap<String, Entry>()
    private val pairs = HashMap<String, PairEntry>()
    private var total = 0
    private var loaded = false
    private var dirty = false

    /** Changes whenever the set of known words changes (a rebuild of the dictionary is due). */
    @Volatile
    var vocabularyVersion = 0
        private set

    /** Changes with every learned or forgotten word (a rebuild would refresh the frequencies). */
    @Volatile
    var countsVersion = 0
        private set

    @Synchronized
    fun load() {
        if (loaded) return
        loaded = true
        val f = file ?: return
        if (!f.exists()) return
        val stored = runCatching { json.decodeFromString(Stored.serializer(), f.readText()) }.getOrNull() ?: return
        for (s in stored.words) words[s.w] = Entry(s.d, s.c, s.t, s.k)
        for (s in stored.pairs) pairs[PersonalSnapshot.pairKey(s.p, s.w)] = PairEntry(s.c, s.t)
        total = stored.total
    }

    /** Writes the vocabulary if it changed. Call off the main thread. */
    @Synchronized
    fun save() {
        val f = file ?: return
        if (!dirty) return
        val stored = Stored(
            words = words.map { (w, e) -> StoredWord(w, e.display, e.count, e.lastDay, e.known) },
            pairs = pairs.map { (k, e) ->
                val cut = k.indexOf('\u0001')
                StoredPair(k.substring(0, cut), k.substring(cut + 1), e.count, e.lastDay)
            },
            total = total,
        )
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(json.encodeToString(Stored.serializer(), stored))
        if (!tmp.renameTo(f)) {
            f.delete()
            tmp.renameTo(f)
        }
        dirty = false
    }

    /**
     * Counts one use of [word], following [previous] (null at the start of a sentence or after something
     * that is not a word). [inDictionary] tells whether the lowercase form is already a dictionary word.
     * [sentenceStart] is whether it began a sentence, where a capital says nothing about the word.
     */
    @Synchronized
    fun learn(word: String, previous: String?, sentenceStart: Boolean, inDictionary: (String) -> Boolean) {
        if (!isLearnable(word)) return
        load()
        val lower = word.lowercase()
        val day = today()
        val e = words[lower]
        if (e == null) {
            val known = inDictionary(lower)
            words[lower] = Entry(displayForm(word, sentenceStart), 1, day, known)
        } else {
            e.count++
            e.lastDay = day
            if (!e.known && e.count >= NEW_WORD_THRESHOLD) {
                e.known = true
                vocabularyVersion++
            }
            // A capitalised use mid-sentence is evidence of a proper name.
            if (!e.known || !inDictionary(lower)) {
                val form = displayForm(word, sentenceStart)
                if (form != lower) e.display = form
            }
        }
        if (previous != null && isLearnable(previous)) {
            val key = PersonalSnapshot.pairKey(previous.lowercase(), lower)
            val p = pairs[key]
            if (p == null) pairs[key] = PairEntry(1, day) else {
                p.count++
                p.lastDay = day
            }
        }
        total++
        dirty = true
        countsVersion++
        if (words.size > MAX_WORDS || pairs.size > MAX_PAIRS) evict(day)
    }

    @Synchronized
    fun delete(lower: String) {
        load()
        val e = words.remove(lower) ?: return
        total = maxOf(0, total - e.count)
        pairs.keys.removeAll { it.startsWith(lower + "\u0001") || it.endsWith("\u0001" + lower) }
        dirty = true
        vocabularyVersion++
        countsVersion++
    }

    @Synchronized
    fun clear() {
        load()
        words.clear()
        pairs.clear()
        total = 0
        dirty = true
        vocabularyVersion++
        countsVersion++
    }

    /** Learned words, most used first. */
    @Synchronized
    fun list(): List<PersonalWord> {
        load()
        return words.map { (w, e) -> PersonalWord(w, e.display, e.count, e.known) }.sortedWith(compareByDescending<PersonalWord> { it.count }.thenBy { it.lower })
    }

    @Synchronized
    fun snapshot(): PersonalSnapshot {
        load()
        return PersonalSnapshot(
            words.map { (w, e) -> PersonalWord(w, e.display, e.count, e.known) },
            pairs.mapValues { it.value.count },
            total,
        )
    }

    private fun evict(day: Int) {
        fun score(count: Int, lastDay: Int) = count * Math.pow(0.5, (day - lastDay) / HALF_LIFE_DAYS)
        if (words.size > MAX_WORDS) {
            val drop = words.entries.sortedBy { score(it.value.count, it.value.lastDay) }.take(words.size - MAX_WORDS * 9 / 10)
            for (d in drop) {
                words.remove(d.key)
                if (d.value.known) vocabularyVersion++
            }
        }
        if (pairs.size > MAX_PAIRS) {
            val drop = pairs.entries.sortedBy { score(it.value.count, it.value.lastDay) }.take(pairs.size - MAX_PAIRS * 9 / 10)
            for (d in drop) pairs.remove(d.key)
        }
    }

    companion object {
        const val NEW_WORD_THRESHOLD = 2
        const val MAX_WORDS = 5000
        const val MAX_PAIRS = 20000
        private const val HALF_LIFE_DAYS = 60.0
        const val FILE = "personal_words.json"

        private val json = Json { ignoreUnknownKeys = true }

        /** Words worth learning: 2 to 32 letters, apostrophes or inner hyphens, no digits or other symbols. */
        fun isLearnable(word: String): Boolean {
            if (word.length !in 2..32) return false
            if (!word.first().isLetter() || !word.last().isLetter()) return false
            return word.all { it.isLetter() || it == '\'' || it == '-' }
        }

        /**
         * How a newly seen word is written: "GitHub" or "NASA" keep their capitals; "Tokyo" keeps its capital
         * unless it began a sentence; anything else is lowercase.
         */
        fun displayForm(word: String, sentenceStart: Boolean): String {
            val innerCaps = word.drop(1).any { it.isUpperCase() }
            if (innerCaps) return word
            if (word.first().isUpperCase() && !sentenceStart) return word
            return word.lowercase()
        }

        @Volatile
        private var instance: PersonalWords? = null

        fun get(filesDir: File): PersonalWords =
            instance ?: synchronized(this) { instance ?: PersonalWords(File(filesDir, FILE)).also { instance = it } }
    }
}
