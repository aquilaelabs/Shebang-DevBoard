package dev.shebang.devboard.dict

/**
 * An immutable word list sorted by word with a frequency tier per entry
 * (SCOWL size level: 10 = the most common thousands of words, 50 = large-dictionary rare words).
 *
 * Words are stored lowercase for matching in [lower]; [words] keeps the original casing ("I", "Monday").
 */
class Dictionary(
    /** Original-case words, sorted case-insensitively. */
    val words: Array<String>,
    /** Lowercased words, same order as [words]. */
    val lower: Array<String>,
    /** Tier per word (10, 20, 35, 40, 50, 60). */
    val tiers: IntArray,
) {
    val size: Int get() = words.size

    /** Entries indexed by (first letter, last letter) of the lowercase word, for glide pruning. */
    private val byEnds: Array<IntArray?> = arrayOfNulls(26 * 26)

    init {
        val buckets = arrayOfNulls<MutableList<Int>>(26 * 26)
        for (i in lower.indices) {
            val w = lower[i]
            if (w.length < 2) continue
            val f = w[0] - 'a'
            val l = w[w.length - 1] - 'a'
            if (f !in 0..25 || l !in 0..25) continue
            val b = f * 26 + l
            (buckets[b] ?: ArrayList<Int>().also { buckets[b] = it }).add(i)
        }
        for (b in buckets.indices) byEnds[b] = buckets[b]?.toIntArray()
    }

    fun indicesByEnds(first: Char, last: Char): IntArray {
        val f = first.lowercaseChar() - 'a'
        val l = last.lowercaseChar() - 'a'
        if (f !in 0..25 || l !in 0..25) return EMPTY
        return byEnds[f * 26 + l] ?: EMPTY
    }

    /** Index of [word] (case-insensitive) or -1. */
    fun indexOf(word: String): Int {
        val target = word.lowercase()
        var lo = 0
        var hi = lower.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = lower[mid].compareTo(target)
            when {
                c < 0 -> lo = mid + 1
                c > 0 -> hi = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    fun contains(word: String): Boolean = indexOf(word) >= 0

    /** Index of the first entry whose lowercase form equals [lowerWord] (already lowercase), or -1. */
    fun indexOfLower(lowerWord: String): Int {
        var lo = 0
        var hi = lower.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (lower[mid] < lowerWord) lo = mid + 1 else hi = mid
        }
        return if (lo < lower.size && lower[lo] == lowerWord) lo else -1
    }

    /** Range [start, end) of entries starting with [prefix] (lowercase compare). */
    fun prefixRange(prefix: String): IntRange {
        val p = prefix.lowercase()
        var lo = 0
        var hi = lower.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (lower[mid] < p) lo = mid + 1 else hi = mid
        }
        val start = lo
        hi = lower.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (lower[mid].startsWith(p)) lo = mid + 1 else hi = mid
        }
        return start until lo
    }

    /**
     * This dictionary plus [extra] words (with their tiers) that it does not already hold, in search order.
     * Used for learned and system user-dictionary words.
     */
    fun withExtraWords(extra: List<Pair<String, Int>>): Dictionary {
        val add = extra.filter { indexOfLower(it.first.lowercase()) < 0 }
            .distinctBy { it.first.lowercase() }
            .sortedWith(compareBy({ it.first.lowercase() }, { it.first }))
        if (add.isEmpty()) return this
        val n = size + add.size
        val w = arrayOfNulls<String>(n)
        val l = arrayOfNulls<String>(n)
        val t = IntArray(n)
        var i = 0
        var j = 0
        for (k in 0 until n) {
            val takeBase = j >= add.size || (i < size && run {
                val a = add[j].first
                val c = lower[i].compareTo(a.lowercase())
                c < 0 || (c == 0 && words[i] <= a)
            })
            if (takeBase) {
                w[k] = words[i]
                l[k] = lower[i]
                t[k] = tiers[i]
                i++
            } else {
                w[k] = add[j].first
                l[k] = add[j].first.lowercase()
                t[k] = add[j].second
                j++
            }
        }
        @Suppress("UNCHECKED_CAST")
        return Dictionary(w as Array<String>, l as Array<String>, t)
    }

    /** Relative frequency weight for a tier; the ratio between tiers is what matters for ranking. */
    fun weight(index: Int): Double = tierWeight(tiers[index])

    companion object {
        private val EMPTY = IntArray(0)

        fun tierWeight(tier: Int): Double = when {
            tier <= 10 -> 1.0
            tier <= 20 -> 0.45
            tier <= 35 -> 0.18
            tier <= 40 -> 0.08
            tier <= 50 -> 0.03
            else -> 0.01
        }

        /** Parses the `word<TAB>tier` asset format. Lines must already be sorted case-insensitively. */
        fun parse(lines: Sequence<String>): Dictionary {
            val words = ArrayList<String>(70_000)
            val tiers = ArrayList<Int>(70_000)
            for (line in lines) {
                if (line.isEmpty()) continue
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                words.add(line.substring(0, tab))
                tiers.add(line.substring(tab + 1).trim().toIntOrNull() ?: 60)
            }
            // Sort by lowercase so binary search on `lower` is valid whatever the input order. The bundled list
            // is written in this order already, so the (slow, boxing) sort is skipped when it isn't needed.
            // Lowercase once up front; doing it inside the comparator allocates on every comparison.
            val lowered = Array(words.size) { words[it].lowercase() }
            var sorted = true
            for (i in 1 until words.size) {
                val c = lowered[i - 1].compareTo(lowered[i])
                if (c > 0 || (c == 0 && words[i - 1] > words[i])) {
                    sorted = false
                    break
                }
            }
            if (sorted) {
                return Dictionary(words.toTypedArray(), lowered, tiers.toIntArray())
            }
            val order = words.indices.sortedWith(compareBy({ lowered[it] }, { words[it] }))
            val w = Array(order.size) { words[order[it]] }
            val l = Array(order.size) { lowered[order[it]] }
            val t = IntArray(order.size) { tiers[order[it]] }
            return Dictionary(w, l, t)
        }
    }
}
