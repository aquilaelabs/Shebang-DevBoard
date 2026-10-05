package dev.shebang.devboard.dict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** The packs turned on or off, and the word lists the user imports. */
class WordPackStoreTest {
    private fun dir() = Files.createTempDirectory("packs").toFile()

    @Test
    fun aListIsOneWordPerLineAndForgivesCommonFormats() {
        val (words, skipped) = WordPackStore.parse(
            """
            # my words
            Kubectl
            kubectl
            ﻿Zorbly
            "quoted"
            word,123
            freq${'\t'}42
            two words
            12345

            x
            ${"a".repeat(49)}
            Kubectl
            """.trimIndent().lineSequence(),
        )
        assertEquals(listOf("Kubectl", "kubectl", "Zorbly", "quoted", "word", "freq", "x"), words)
        // A phrase, a number and a word too long to be one.
        assertEquals(3, skipped)
    }

    @Test
    fun importsAreKeptTurnedOffAndDeleted() {
        val d = dir()
        val store = WordPackStore(d)
        val v0 = store.version
        val r = store.import("Team names", "Zorbly\nQuuxly\n".byteInputStream(), now = 1)
        val list = r.list!!
        assertEquals(2, list.words)
        assertTrue(store.version > v0)
        assertEquals(listOf("Zorbly", "Quuxly"), store.enabledImportedWords())
        // Kept across restarts.
        val again = WordPackStore(d)
        assertEquals(listOf("Team names"), again.lists().map { it.name })
        assertEquals(listOf("Zorbly", "Quuxly"), again.enabledImportedWords())
        again.setListEnabled(list.id, false)
        assertTrue(again.enabledImportedWords().isEmpty())
        again.delete(list.id)
        assertTrue(again.lists().isEmpty())
        assertFalse(java.io.File(d, "${WordPackStore.LISTS_DIR}/${list.id}.txt").exists())
    }

    @Test
    fun aFileWithNoWordsIsRefused() {
        val r = WordPackStore(dir()).import("Empty", "# nothing\n\n123\n".byteInputStream())
        assertNull(r.list)
        assertNotNull(r.error)
    }

    @Test
    fun builtInPacksAreOnUntilTurnedOff() {
        val d = dir()
        val store = WordPackStore(d)
        assertTrue(store.isEnabled("dev"))
        store.setEnabled("dev", false)
        assertFalse(WordPackStore(d).isEnabled("dev"))
        assertTrue(WordPackStore(d).isEnabled("computer"))
        store.setEnabled("dev", true)
        assertTrue(WordPackStore(d).isEnabled("dev"))
    }
}
