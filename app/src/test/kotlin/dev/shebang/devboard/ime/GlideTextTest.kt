package dev.shebang.devboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlideTextTest {
    @Test
    fun contextWordReadsLikeTheModel() {
        assertEquals(GlideText.SENTENCE_START, GlideText.contextWord(""))
        assertEquals(GlideText.SENTENCE_START, GlideText.contextWord("Hello there. "))
        assertEquals(GlideText.SENTENCE_START, GlideText.contextWord("line one\n"))
        assertEquals("there", GlideText.contextWord("Hello there "))
        assertEquals("hello", GlideText.contextWord("Hello, "))
        assertEquals("don't", GlideText.contextWord("I don’t"))
        assertEquals("", GlideText.contextWord("room 42 "))
        assertEquals("", GlideText.contextWord("a + "))
    }

    @Test
    fun casingFollowsTheTemplate() {
        assertEquals("Of", GlideText.matchCase("If", "of"))
        assertEquals("OF", GlideText.matchCase("IF", "of"))
        assertEquals("of", GlideText.matchCase("if", "of"))
        assertEquals("I", GlideText.matchCase("a", "I"))
    }

    @Test
    fun wordStarts() {
        assertTrue(GlideText.atWordStart("", 0))
        assertTrue(GlideText.atWordStart("a b", 2))
        assertTrue(GlideText.atWordStart("(x", 1))
        assertFalse(GlideText.atWordStart("ab", 1))
    }
}
