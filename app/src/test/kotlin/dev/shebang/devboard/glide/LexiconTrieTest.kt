package dev.shebang.devboard.glide

import dev.shebang.devboard.dict.NgramModelTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LexiconTrieTest {
    private val dictionary get() = NgramModelTest.dictionary
    private val trie by lazy { LexiconTrie.build(dictionary, NgramModelTest.lm) }

    private fun walk(letters: String): Int {
        var node = LexiconTrie.ROOT
        for (ch in letters) {
            var next = -1
            for (e in trie.childStart[node] until trie.childEnd[node]) {
                val c = trie.children[e]
                if (trie.letter[c].toInt() == ch - 'a') next = c
            }
            if (next < 0) return -1
            node = next
        }
        return node
    }

    private fun wordsAt(node: Int) = (trie.wordStart[node] until trie.wordEnd[node]).map { dictionary.lower[trie.words[it]] }.toSet()

    @Test
    fun keySequencesDropApostrophesAndRepeats() {
        val out = IntArray(16)
        assertEquals(4, LexiconTrie.keySequence("don't", out))
        assertEquals(listOf('d', 'o', 'n', 't'), (0 until 4).map { 'a' + out[it] })
        assertEquals(4, LexiconTrie.keySequence("hello", out))
        assertEquals(-1, LexiconTrie.keySequence("café", out))
    }

    @Test
    fun wordsShareNodesWhenTheirPathsMatch() {
        assertTrue(wordsAt(walk("to")).containsAll(listOf("to", "too")))
        assertTrue("don't" in wordsAt(walk("dont")))
        assertTrue("it's" in wordsAt(walk("its")))
        assertTrue(wordsAt(walk("god")).containsAll(listOf("good", "god")))
    }

    @Test
    fun childrenAreSortedAndLookaheadIsMonotonic() {
        for (node in 0 until trie.nodeCount) {
            var prev = -1
            for (e in trie.childStart[node] until trie.childEnd[node]) {
                val c = trie.children[e]
                assertTrue(trie.letter[c] > prev)
                prev = trie.letter[c].toInt()
                assertTrue("lookahead must not decrease toward the root", trie.lookahead[node] <= trie.lookahead[c])
            }
        }
        assertEquals(0f, trie.lookahead[LexiconTrie.ROOT], 0f)
    }
}
