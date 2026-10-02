package dev.shebang.devboard.glide

import dev.shebang.devboard.dict.Dictionary
import dev.shebang.devboard.dict.NgramModel

/**
 * The dictionary as a tree of key sequences, for the streaming glide decoder.
 *
 * A word's path is its letters with apostrophes dropped and repeated letters collapsed ("don't" -> d,o,n,t;
 * "hello" -> h,e,l,o), because a glide cannot show either. Several words can end on one node ("to" and
 * "too"); only context and frequency can choose between them. Each spelling group (US/us) contributes one
 * representative. Words with letters that have no key, or fewer than two keys, are left out.
 *
 * Children are stored contiguously (CSR), so walking the tree allocates nothing.
 */
class LexiconTrie private constructor(
    val nodeCount: Int,
    /** Letter index 0..25 per node; -1 for the root. */
    val letter: ByteArray,
    val parent: IntArray,
    val childStart: IntArray,
    val childEnd: IntArray,
    val children: IntArray,
    val wordStart: IntArray,
    val wordEnd: IntArray,
    /** Dictionary indices of the words ending at each node, see [wordStart]. */
    val words: IntArray,
    /** Lowest unigram cost of any word at or below the node, minus the lowest in the tree (so >= 0). */
    val lookahead: FloatArray,
) {
    fun hasWords(node: Int): Boolean = wordEnd[node] > wordStart[node]

    companion object {
        const val ROOT = 0

        /**
         * Collapsed a-z key sequence of a lowercase word into [out]; returns its length, or -1 when the word
         * has a character other than a-z and the apostrophe.
         */
        fun keySequence(lower: String, out: IntArray): Int {
            var n = 0
            var prev = -1
            for (ch in lower) {
                if (ch == '\'') continue
                if (ch !in 'a'..'z') return -1
                val c = ch - 'a'
                if (c == prev) continue
                if (n >= out.size) return -1
                out[n++] = c
                prev = c
            }
            return n
        }

        fun build(dictionary: Dictionary, lm: NgramModel): LexiconTrie {
            var cap = 1 shl 16
            var letter = ByteArray(cap)
            var parent = IntArray(cap)
            var firstChild = IntArray(cap) { -1 }
            var nextSibling = IntArray(cap) { -1 }
            var wordHead = IntArray(cap) { -1 }
            val wordNext = IntArray(dictionary.size) { -1 }
            val wordOf = IntArray(dictionary.size)
            var entries = 0
            var nodes = 1
            letter[ROOT] = -1
            parent[ROOT] = -1

            val seq = IntArray(64)
            var i = 0
            val n = dictionary.size
            while (i < n) {
                // One representative per lowercase form: lowest tier, then the all-lowercase spelling.
                var j = i + 1
                while (j < n && dictionary.lower[j] == dictionary.lower[i]) j++
                var rep = i
                for (k in i until j) {
                    val better = dictionary.tiers[k] < dictionary.tiers[rep] ||
                        (dictionary.tiers[k] == dictionary.tiers[rep] && dictionary.words[k] == dictionary.lower[k] &&
                            dictionary.words[rep] != dictionary.lower[rep])
                    if (better) rep = k
                }
                val len = keySequence(dictionary.lower[rep], seq)
                if (len >= 2) {
                    var node = ROOT
                    for (s in 0 until len) {
                        val c = seq[s]
                        var child = firstChild[node]
                        while (child != -1 && letter[child].toInt() != c) child = nextSibling[child]
                        if (child == -1) {
                            if (nodes == cap) {
                                cap *= 2
                                letter = letter.copyOf(cap)
                                parent = parent.copyOf(cap)
                                firstChild = firstChild.copyOf(cap).also { it.fill(-1, nodes, cap) }
                                nextSibling = nextSibling.copyOf(cap).also { it.fill(-1, nodes, cap) }
                                wordHead = wordHead.copyOf(cap).also { it.fill(-1, nodes, cap) }
                            }
                            child = nodes++
                            letter[child] = c.toByte()
                            parent[child] = node
                            nextSibling[child] = firstChild[node]
                            firstChild[node] = child
                        }
                        node = child
                    }
                    wordOf[entries] = rep
                    wordNext[entries] = wordHead[node]
                    wordHead[node] = entries
                    entries++
                }
                i = j
            }

            // Compact: children sorted by letter, words contiguous per node.
            val childStart = IntArray(nodes)
            val childEnd = IntArray(nodes)
            val children = IntArray(maxOf(0, nodes - 1))
            val wordStart = IntArray(nodes)
            val wordEnd = IntArray(nodes)
            val words = IntArray(entries)
            var c = 0
            var w = 0
            val scratch = IntArray(26)
            for (node in 0 until nodes) {
                var k = 0
                var ch = firstChild[node]
                while (ch != -1) {
                    scratch[k++] = ch
                    ch = nextSibling[ch]
                }
                // Insertion sort by letter (at most 26).
                for (a in 1 until k) {
                    val x = scratch[a]
                    var b = a - 1
                    while (b >= 0 && letter[scratch[b]] > letter[x]) {
                        scratch[b + 1] = scratch[b]
                        b--
                    }
                    scratch[b + 1] = x
                }
                childStart[node] = c
                for (a in 0 until k) children[c++] = scratch[a]
                childEnd[node] = c
                wordStart[node] = w
                var e = wordHead[node]
                while (e != -1) {
                    words[w++] = wordOf[e]
                    e = wordNext[e]
                }
                wordEnd[node] = w
            }

            // Lookahead: best unigram cost in each subtree. Children are created after their parents.
            val lookahead = FloatArray(nodes) { Float.MAX_VALUE }
            for (node in 0 until nodes) {
                for (k in wordStart[node] until wordEnd[node]) {
                    val cost = lm.unigramCost(words[k])
                    if (cost < lookahead[node]) lookahead[node] = cost
                }
            }
            for (node in nodes - 1 downTo 1) {
                val p = parent[node]
                if (lookahead[node] < lookahead[p]) lookahead[p] = lookahead[node]
            }
            val best = lookahead[ROOT]
            for (node in 0 until nodes) lookahead[node] -= best

            return LexiconTrie(
                nodes, letter.copyOf(nodes), parent.copyOf(nodes), childStart, childEnd, children,
                wordStart, wordEnd, words, lookahead,
            )
        }
    }
}

/**
 * Everything the streaming decoder needs about language, built once off the main thread, and the learned
 * reading of strokes ([GlideModel]) when the app has one.
 */
class GlideLanguage(
    val dictionary: Dictionary,
    val lm: NgramModel,
    val trie: LexiconTrie,
    val model: GlideModel? = null,
    val nextWord: dev.shebang.devboard.dict.NextWordModel? = null,
) {
    /** The next-word model's id of each dictionary word (by its lowercase form), -1 where it has none. */
    val nextWordIds: IntArray = IntArray(dictionary.size) { i ->
        nextWord?.idOf(dictionary.lower[i])?.takeIf { it != dev.shebang.devboard.dict.NextWordModel.UNK } ?: -1
    }

    companion object {
        fun build(dictionary: Dictionary, lm: NgramModel, model: GlideModel? = null, nextWord: dev.shebang.devboard.dict.NextWordModel? = null): GlideLanguage =
            GlideLanguage(dictionary, lm, LexiconTrie.build(dictionary, lm), model, nextWord)
    }
}
