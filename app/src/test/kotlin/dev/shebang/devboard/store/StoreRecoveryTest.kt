package dev.shebang.devboard.store

import dev.shebang.devboard.dict.PersonalWords
import dev.shebang.devboard.glide.GlideAdaptation
import dev.shebang.devboard.ime.ClipboardHistory
import dev.shebang.devboard.ime.EmailMemory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Each store keeps a file it cannot read instead of overwriting it, and deleting data also deletes that copy. */
class StoreRecoveryTest {
    private val dir = Files.createTempDirectory("stores").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun damaged(name: String): File = File(dir, name).also { it.writeText("{\"truncat") }

    @Test
    fun learnedWordsAreNotOverwritten() {
        val f = damaged("personal_words.json")
        val words = PersonalWords(f) { 20000 }
        words.learn("kubectl", null, false) { false }
        words.save()
        assertEquals("{\"truncat", File(dir, "personal_words.json.unreadable").readText())
        assertTrue(PersonalWords(f) { 20000 }.list().any { it.lower == "kubectl" })
        // Deleting everything learned deletes the set-aside copy as well.
        words.clear()
        words.save()
        assertFalse(File(dir, "personal_words.json.unreadable").exists())
    }

    @Test
    fun adaptationEmailsAndClipboardAreNotOverwritten() {
        val a = GlideAdaptation(damaged("glide_adaptation.json"))
        a.load()
        a.reset()
        a.save()
        assertFalse(File(dir, "glide_adaptation.json.unreadable").exists())

        val e = EmailMemory(damaged("emails.json"))
        e.record("sean@example.com")
        assertTrue(File(dir, "emails.json.unreadable").exists())
        assertEquals(listOf("sean@example.com"), EmailMemory(File(dir, "emails.json")).matching("se"))
        e.clear()
        assertFalse(File(dir, "emails.json.unreadable").exists())

        val c = ClipboardHistory(damaged("clipboard_history.json"))
        c.add("ssh dev@build.local")
        assertTrue(File(dir, "clipboard_history.json.unreadable").exists())
        c.clear()
        assertFalse(File(dir, "clipboard_history.json.unreadable").exists())
    }
}
