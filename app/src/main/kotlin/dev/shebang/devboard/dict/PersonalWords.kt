package dev.shebang.devboard.dict

import dev.shebang.devboard.store.JsonFile
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
    /**
     * How often each word was rejected right after the word before it, keyed like [pairs] and already faded
     * with time ([PersonalWords.reject]): the word model lowers the word mostly there.
     */
    val rejections: Map<String, Double> = emptyMap(),
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
 * older use counting less) are forgotten. Before the first word learned each day, the vocabulary as it stood
 * is kept beside the file for [KEEP_DAYS] days, so a bad day can be undone with [restore]; deleting a word
 * deletes it from those copies too. Thread-safe.
 */
class PersonalWords(private val file: File?, private val today: () -> Int = { (System.currentTimeMillis() / 86_400_000L).toInt() }) {

    @Serializable
    private data class StoredWord(val w: String, val d: String, val c: Int, val t: Int, val k: Boolean)

    @Serializable
    private data class StoredPair(val p: String, val w: String, val c: Int, val t: Int)

    /** A rejection of [w] right after [p]: how many (fading, so not whole) as of day [t]. */
    @Serializable
    private data class StoredReject(val p: String, val w: String, val c: Double, val t: Int)

    @Serializable
    private data class Stored(
        val version: Int = 1,
        val words: List<StoredWord> = emptyList(),
        val pairs: List<StoredPair> = emptyList(),
        val total: Int = 0,
        val rejects: List<StoredReject> = emptyList(),
    )

    private class Entry(var display: String, var count: Int, var lastDay: Int, var known: Boolean)
    private class PairEntry(var count: Int, var lastDay: Int)
    private class RejectEntry(var count: Double, var day: Int)

    private val words = HashMap<String, Entry>()
    private val pairs = HashMap<String, PairEntry>()
    private val rejects = HashMap<String, RejectEntry>()
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

    /** The day of the copy kept before today's learning, once it is taken. */
    private var snapshotDay = Int.MIN_VALUE

    @Synchronized
    fun load() {
        if (loaded) return
        loaded = true
        val f = file ?: return
        val stored = read(f) ?: return
        apply(stored)
    }

    private fun read(f: File): Stored? = JsonFile(f).read(Stored.serializer(), json)

    private fun apply(stored: Stored) {
        words.clear()
        pairs.clear()
        rejects.clear()
        for (s in stored.words) words[s.w] = Entry(s.d, s.c, s.t, s.k)
        for (s in stored.pairs) pairs[PersonalSnapshot.pairKey(s.p, s.w)] = PairEntry(s.c, s.t)
        for (s in stored.rejects) rejects[PersonalSnapshot.pairKey(s.p, s.w)] = RejectEntry(s.c, s.t)
        total = stored.total
    }

    private fun stored() = Stored(
        words = words.map { (w, e) -> StoredWord(w, e.display, e.count, e.lastDay, e.known) },
        pairs = pairs.map { (k, e) ->
            val cut = k.indexOf('\u0001')
            StoredPair(k.substring(0, cut), k.substring(cut + 1), e.count, e.lastDay)
        },
        total = total,
        rejects = rejects.map { (k, e) ->
            val cut = k.indexOf('\u0001')
            StoredReject(k.substring(0, cut), k.substring(cut + 1), e.count, e.day)
        },
    )

    private fun write(f: File, stored: Stored) = JsonFile(f).write(Stored.serializer(), json, stored)

    /** Copies set aside as unreadable (the vocabulary's or a kept day's) go when the user deletes words. */
    private fun discardUnreadable() {
        val f = file ?: return
        val base = f.name.removeSuffix(".json")
        f.parentFile?.listFiles()?.forEach { if (it.name.startsWith(base) && it.name.endsWith(".unreadable")) it.delete() }
    }

    /** Writes the vocabulary if it changed. Call off the main thread. */
    @Synchronized
    fun save() {
        val f = file ?: return
        if (!dirty) return
        write(f, stored())
        dirty = false
    }

    // ---- Going back to an earlier day ----------------------------------------------------------------

    private fun snapshotFile(day: Int): File? = file?.let { File(it.parentFile, it.name.removeSuffix(".json") + ".day-" + day + ".json") }

    private fun snapshotDays(): List<Int> {
        val f = file ?: return emptyList()
        val prefix = f.name.removeSuffix(".json") + ".day-"
        return f.parentFile?.listFiles()?.mapNotNull { g ->
            if (g.name.startsWith(prefix) && g.name.endsWith(".json")) g.name.removePrefix(prefix).removeSuffix(".json").toIntOrNull() else null
        }.orEmpty()
    }

    /** Before the first word learned on [day]: keep the vocabulary as it stood, and forget copies past [KEEP_DAYS]. */
    private fun snapshotBefore(day: Int) {
        if (day == snapshotDay) return
        snapshotDay = day
        val snap = snapshotFile(day) ?: return
        if (!snap.exists()) runCatching { write(snap, stored()) }
        for (d in snapshotDays()) if (d <= day - KEEP_DAYS) snapshotFile(d)?.delete()
    }

    /** Days (days since 1970, UTC) whose starting vocabulary can be gone back to, newest first. */
    @Synchronized
    fun restoreDays(): List<Int> = snapshotDays().sortedDescending()

    /**
     * Goes back to the vocabulary as it stood at the start of [day] (or the nearest kept day before it;
     * empty if it predates every copy). Copies after it are dropped.
     */
    @Synchronized
    fun restore(day: Int) {
        load()
        val d = snapshotDays().filter { it <= day }.maxOrNull()
        val stored = d?.let { snapshotFile(it) }?.let { read(it) }
        if (stored != null) apply(stored) else apply(Stored())
        for (later in snapshotDays()) if (d == null || later > d) snapshotFile(later)?.delete()
        snapshotDay = Int.MIN_VALUE
        dirty = true
        vocabularyVersion++
        countsVersion++
    }

    /** Removes [lower] from every kept copy, so going back never brings a deleted word back. */
    private fun scrubSnapshots(lower: String?) {
        for (d in snapshotDays()) {
            val f = snapshotFile(d) ?: continue
            if (lower == null) {
                JsonFile(f).delete()
                continue
            }
            val st = read(f) ?: continue
            if (st.words.none { it.w == lower } && st.rejects.none { it.p == lower || it.w == lower }) continue
            val removed = st.words.firstOrNull { it.w == lower }?.c ?: 0
            runCatching {
                write(f, st.copy(
                    words = st.words.filter { it.w != lower },
                    pairs = st.pairs.filter { it.p != lower && it.w != lower },
                    total = maxOf(0, st.total - removed),
                    rejects = st.rejects.filter { it.p != lower && it.w != lower },
                ))
            }
        }
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
        snapshotBefore(day)
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
        // Writing the word here again takes back a rejection of it here (after a one-letter word too: "a lot").
        val rejectKey = PersonalSnapshot.pairKey(rejectionContext(previous), lower)
        rejects[rejectKey]?.let { r ->
            r.count = faded(r, day) - 1.0
            r.day = day
            if (r.count <= 0.05) rejects.remove(rejectKey)
        }
        total++
        dirty = true
        countsVersion++
        if (words.size > MAX_WORDS || pairs.size > MAX_PAIRS) evict(day)
    }

    /**
     * Adds [word] by hand (Settings > Learning and privacy > Personal words): a word at once, offered, glided and corrected to like
     * one used [NEW_WORD_THRESHOLD] times, and written as given ("GitHub" keeps its capitals). Adding a word
     * already learned makes it known and takes the new spelling. Returns false for a word [isLearnable] refuses.
     */
    @Synchronized
    fun add(word: String): Boolean {
        val w = word.trim()
        if (!isLearnable(w)) return false
        load()
        val lower = w.lowercase()
        val day = today()
        snapshotBefore(day)
        val e = words[lower]
        if (e == null) {
            words[lower] = Entry(w, NEW_WORD_THRESHOLD, day, true)
            total += NEW_WORD_THRESHOLD
        } else {
            if (e.count < NEW_WORD_THRESHOLD) {
                total += NEW_WORD_THRESHOLD - e.count
                e.count = NEW_WORD_THRESHOLD
            }
            e.display = w
            e.lastDay = day
            e.known = true
        }
        dirty = true
        vocabularyVersion++
        countsVersion++
        if (words.size > MAX_WORDS) evict(day)
        return true
    }

    /**
     * [word] was rejected right after [previous]: an autocorrect to it undone, a glide of it changed, redone or
     * deleted at once, or the check mark tapped against it. The word model lowers it mostly after that word, a
     * little everywhere once it is rejected after several ([NgramModel]). A rejection fades by half every
     * [REJECT_HALF_LIFE_DAYS], at most [REJECT_CAP] count, and writing the word there again takes one back.
     */
    @Synchronized
    fun reject(word: String, previous: String?) {
        if (!isLearnable(word)) return
        load()
        val day = today()
        snapshotBefore(day)
        val key = PersonalSnapshot.pairKey(rejectionContext(previous), word.lowercase())
        val r = rejects[key]
        if (r == null) rejects[key] = RejectEntry(1.0, day) else {
            r.count = minOf(REJECT_CAP, faded(r, day) + 1.0)
            r.day = day
        }
        if (rejects.size > MAX_REJECTS) {
            rejects.entries.sortedBy { faded(it.value, day) }.take(rejects.size - MAX_REJECTS * 9 / 10).forEach { rejects.remove(it.key) }
        }
        dirty = true
        // A rejection is rarer than a use and says more: it brings the next rebuild nearer.
        countsVersion += REJECT_REBUILD_WEIGHT
    }

    /** The word before, as rejections key it: a one-letter word too ("a lot"), which is never learned itself. */
    private fun rejectionContext(previous: String?): String {
        val p = previous?.takeIf { w -> w.isNotEmpty() && w.length <= 32 && w.all { it.isLetter() || it == '\'' || it == '-' } }
        return p?.lowercase() ?: NO_PREVIOUS
    }

    private fun faded(r: RejectEntry, day: Int) = r.count * Math.pow(0.5, (day - r.day).coerceAtLeast(0) / REJECT_HALF_LIFE_DAYS)

    @Synchronized
    fun delete(lower: String) {
        load()
        val hadRejects = rejects.keys.removeAll { it.startsWith(lower + "\u0001") || it.endsWith("\u0001" + lower) }
        val e = words.remove(lower)
        if (e == null) {
            if (hadRejects) {
                scrubSnapshots(lower)
                dirty = true
                countsVersion++
            }
            return
        }
        total = maxOf(0, total - e.count)
        pairs.keys.removeAll { it.startsWith(lower + "\u0001") || it.endsWith("\u0001" + lower) }
        scrubSnapshots(lower)
        discardUnreadable()
        dirty = true
        vocabularyVersion++
        countsVersion++
    }

    @Synchronized
    fun clear() {
        load()
        words.clear()
        pairs.clear()
        rejects.clear()
        total = 0
        // Deleting everything deletes the kept copies too.
        scrubSnapshots(null)
        discardUnreadable()
        snapshotDay = Int.MIN_VALUE
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
        val day = today()
        return PersonalSnapshot(
            words.map { (w, e) -> PersonalWord(w, e.display, e.count, e.known) },
            pairs.mapValues { it.value.count },
            total,
            rejects.mapValues { faded(it.value, day) }.filterValues { it >= 0.05 },
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
        /** Days of starting vocabularies kept for going back. */
        const val KEEP_DAYS = 14
        const val MAX_WORDS = 5000
        const val MAX_PAIRS = 20000
        const val MAX_REJECTS = 5000
        /** A rejection fades by half in this many days, so one bad day does not bury a word. */
        const val REJECT_HALF_LIFE_DAYS = 21.0
        /** At most this many rejections count for one word after one word. */
        const val REJECT_CAP = 5.0
        private const val REJECT_REBUILD_WEIGHT = 5
        /** The "word before" of a rejection with none (a sentence start): it counts only toward the word itself. */
        const val NO_PREVIOUS = "\u0002"
        private const val HALF_LIFE_DAYS = 60.0
        const val FILE = "personal_words.json"

        private val json = Json { ignoreUnknownKeys = true }

        /** Words worth learning: 2 to 32 letters, with apostrophes or inner hyphens or underscores ("max_retries"); no digits or other symbols. */
        fun isLearnable(word: String): Boolean {
            if (word.length !in 2..32) return false
            if (!word.first().isLetter() || !word.last().isLetter()) return false
            return word.all { it.isLetter() || it == '\'' || it == '-' || it == '_' }
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
