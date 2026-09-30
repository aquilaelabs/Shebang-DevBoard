package dev.shebang.devboard.dict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SuggesterTest {
    private val dict = Dictionary.parse(
        """
        the	10
        there	10
        then	10
        them	10
        theme	35
        thermal	50
        hello	10
        help	10
        held	20
        world	10
        would	10
        I	10
        Monday	35
        café	20
        """.trimIndent().lineSequence().map { it.trim() }
    )
    private val s = Suggester(dict)

    @Test
    fun prefixCompletionsRankCommonWordsFirst() {
        val words = s.suggest("the", 3).map { it.word }
        assertEquals("the", words[0])
        assertTrue(words.containsAll(listOf("then", "them")) || words.containsAll(listOf("there", "then")) || words.containsAll(listOf("there", "them")))
        assertTrue("theme" !in words.take(2) || "thermal" !in words)
    }

    @Test
    fun rareCompletionsComeAfterCommonOnes() {
        val words = s.suggest("ther", 3).map { it.word }
        assertEquals("there", words[0])
        assertEquals("thermal", s.suggest("therm", 3).map { it.word }[0])
        // A shorter, more frequent completion outranks a longer one at the same tier.
        assertEquals("help", s.suggest("hel", 3).map { it.word }[0])
    }

    @Test
    fun correctionsHandleTyposAndTranspositions() {
        assertEquals("hello", s.suggest("helo", 3).first().word)
        assertEquals("world", s.suggest("wrold", 3).first().word)
        assertTrue(s.suggest("wprld", 3).first { it.isCorrection }.word == "world")
    }

    @Test
    fun casingFollowsWhatWasTyped() {
        assertEquals("Hello", s.suggest("Hell", 1).first().word)
        assertEquals("HELLO", s.suggest("HELL", 1).first().word)
        assertEquals("Monday", s.suggest("mon", 1).first().word)
    }

    @Test
    fun autocorrectOnlyFiresForConfidentCommonWords() {
        assertEquals("hello", s.autocorrect("helo"))
        assertNull(s.autocorrect("hello"))
        assertNull(s.autocorrect("zzzzzz"))
        assertNull(s.autocorrect("h"))
    }

    @Test
    fun autocorrectFromReusesCandidatesWithoutScanning() {
        assertEquals("hello", s.autocorrectFrom("helo", listOf(Suggestion("hello", 1.0, isCorrection = true))))
        assertNull(s.autocorrectFrom("helo", emptyList()))
        // Every candidate counts, completions included; the likeliest slip of a common word wins.
        assertEquals("hello", s.autocorrectFrom("helo", listOf(Suggestion("help", 1.0, isCorrection = false), Suggestion("hello", 0.5, isCorrection = true))))
        assertEquals("the", s.autocorrectFrom("te", listOf(Suggestion("tea", 1.0, isCorrection = false), Suggestion("the", 0.5, isCorrection = true))))
        // A word the dictionary knows is never changed.
        assertNull(s.autocorrectFrom("hello", listOf(Suggestion("help", 1.0, isCorrection = true))))
        // Two-letter words are only corrected by a letter they dropped.
        assertNull(s.autocorrectFrom("js", listOf(Suggestion("is", 1.0, isCorrection = true))))
    }

    @Test
    fun editDistanceBound() {
        assertEquals(0, EditDistance.bounded("abc", "abc", 2))
        assertEquals(1, EditDistance.bounded("abc", "abd", 2))
        assertEquals(1, EditDistance.bounded("abc", "acb", 2))
        assertEquals(-1, EditDistance.bounded("abc", "xyz", 2))
        assertEquals(2, EditDistance.bounded("kitten", "sittin", 2))
    }

    @Test
    fun dictionaryLookups() {
        assertTrue(dict.contains("Hello"))
        assertTrue(dict.contains("i"))
        assertTrue(!dict.contains("nope"))
        assertEquals(6, dict.prefixRange("the").count())
        assertEquals(0, dict.prefixRange("xyz").count())
        assertEquals(1, dict.indicesByEnds('w', 'd').size + dict.indicesByEnds('q', 'q').size - 1)
    }

    @Test
    fun bundledDictionaryLoadsAndIsSorted() {
        val d = Dictionary.parse(java.io.File("src/main/assets/dict/en_words.txt").readLines().asSequence())
        assertTrue(d.size > 50_000)
        assertTrue(d.contains("keyboard"))
        assertTrue(d.contains("the"))
        assertEquals(10, d.tiers[d.indexOf("the")])
        for (i in 1 until d.size) assertTrue(d.lower[i - 1] <= d.lower[i])
    }
}
