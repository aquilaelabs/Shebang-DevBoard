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
            ${'\uFEFF'}Zorbly
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

    @Test
    fun aFrequencyColumnSetsWhereImportedWordsStart() {
        // Counts on a log scale of the list's own: its commonest quarter at 35, the next at 40, the rest at 50.
        val (words, counts, skipped) = WordPackStore.parseCounted(
            sequenceOf("Zorbly\t1000000", "Quuxly,\"2000\"", "Blorp\t10", "Frizzle", "# a comment", "bad word\t5"),
        )
        assertEquals(listOf("Zorbly", "Quuxly", "Blorp", "Frizzle"), words)
        assertEquals(1, skipped)
        val tiers = WordPackStore.tiersFor(words, counts)
        assertEquals(35, tiers["Zorbly"])
        assertEquals(40, tiers["Quuxly"])
        assertEquals(WordPackStore.IMPORTED_TIER, tiers["Blorp"])
        assertEquals("no count, no tier of its own", null, tiers["Frizzle"])

        val store = WordPackStore(java.nio.file.Files.createTempDirectory("packs").toFile())
        store.import("Frequencies", "Zorbly\t1000000\nQuuxly\t2000\nFrizzle\n".byteInputStream())
        assertEquals(listOf("Zorbly" to 35, "Quuxly" to 40, "Frizzle" to WordPackStore.IMPORTED_TIER), store.enabledImportedTiers())
        assertEquals(listOf("Zorbly", "Quuxly", "Frizzle"), store.enabledImportedWords())
    }

    @Test
    fun aListSavedBeforeTiersStartsAtTheImportedTier() {
        val dir = java.nio.file.Files.createTempDirectory("packs").toFile()
        val store = WordPackStore(dir)
        store.import("Old", "Zorbly\nQuuxly\n".byteInputStream())
        // Rewrite the saved list as earlier versions kept it: bare words.
        java.io.File(dir, WordPackStore.LISTS_DIR).listFiles()!!.single().writeText("Zorbly\nQuuxly\n")
        assertEquals(listOf("Zorbly" to WordPackStore.IMPORTED_TIER, "Quuxly" to WordPackStore.IMPORTED_TIER), store.enabledImportedTiers())
    }
}
