package dev.shebang.devboard.ime

import android.os.Handler
import android.os.Looper
import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.Executor

/** Email addresses remembered from email fields and offered there again. */
class EmailMemoryTest {
    private var clock = 1000L
    private val memory = EmailMemory(null) { clock++ }

    @Test
    fun onlyAddressesAreRecorded() {
        memory.record("sean@example.com, not an address; ana@mail.example.org bob@nodot")
        assertEquals(listOf("ana@mail.example.org", "sean@example.com"), memory.list().map { it.address }.sorted())
        assertTrue(EmailMemory.isAddress("first.last+tag@sub.example.co"))
        assertFalse(EmailMemory.isAddress("bob@example"))
        assertFalse(EmailMemory.isAddress("@example.com"))
    }

    @Test
    fun matchesBeginWithWhatIsTypedMostUsedFirst() {
        memory.record("sam@example.com")
        memory.record("sean@example.com")
        memory.record("Sean@Example.com")
        assertEquals(listOf("Sean@Example.com", "sam@example.com"), memory.matching("s"))
        assertEquals(listOf("Sean@Example.com"), memory.matching("SE"))
        // Typed in full, it is not offered again.
        assertEquals(emptyList<String>(), memory.matching("sean@example.com"))
        assertEquals(listOf("Sean@Example.com", "sam@example.com"), memory.matching(""))
    }

    @Test
    fun theSameNameAtTwoProvidersShowsBothUntilTheProviderIsTyped() {
        memory.record("sean@gmail.com")
        memory.record("sean@live.com")
        assertEquals(setOf("sean@gmail.com", "sean@live.com"), memory.matching("sean").toSet())
        assertEquals(setOf("sean@gmail.com", "sean@live.com"), memory.matching("sean@").toSet())
        assertEquals(listOf("sean@live.com"), memory.matching("sean@l"))
    }

    @Test
    fun theLeastUsedGoWhenFull() {
        memory.record("keep@example.com")
        memory.record("keep@example.com")
        for (i in 0 until EmailMemory.MAX_ADDRESSES) memory.record("a$i@example.com")
        assertEquals(EmailMemory.MAX_ADDRESSES, memory.list().size)
        assertEquals("keep@example.com", memory.list().first().address)
    }

    @Test
    fun keptAcrossInstances() {
        val dir = Files.createTempDirectory("emails").toFile()
        EmailMemory(java.io.File(dir, EmailMemory.FILE)).record("sean@example.com")
        assertEquals(listOf("sean@example.com"), EmailMemory(java.io.File(dir, EmailMemory.FILE)).matching("se"))
        dir.deleteRecursively()
    }

    private val ic = FakeInputConnection()
    private var shown: List<String> = emptyList()

    private fun controller(inputType: Int) = TextInputController(
        { ic },
        object : TextInputController.Ui {
            override fun showCandidates(words: List<String>) {
                shown = words
            }
            override fun setComposing(composing: Boolean) = Unit
        },
        Executor { it.run() },
        Handler(Looper.getMainLooper()),
        object : TextInputController.Learner {
            override fun learnWord(word: String, previous: String?, sentenceStart: Boolean) = Unit
            override fun learnGlide(observations: FloatArray) = Unit
            override fun correction(stroke: FloatArray?, word: Int, dictionary: dev.shebang.devboard.dict.Dictionary) = Unit
        },
        postToMain = { it.run() },
    ).also {
        it.startInput(FieldInfo.from(inputType, 0))
        it.emails = memory
    }

    private val emailField = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS

    @Test
    fun anEmailFieldOffersTheAddressBeingTyped() {
        memory.record("sean@example.com")
        val c = controller(emailField)
        c.refreshEmails()
        assertTrue("sean@example.com" in shown)
        for (ch in "se") c.typeText(ch.toString())
        c.refreshEmails()
        assertTrue("sean@example.com" in shown)
        c.pickCandidate("sean@example.com")
        assertEquals("sean@example.com", ic.toString())
    }

    @Test
    fun anAddressEnteredIsRememberedWhenTheFieldIsLeft() {
        val c = controller(emailField)
        for (ch in "ana@example.org") c.typeText(ch.toString())
        c.refreshEmails()
        // Left for a field of another kind, as an app may do before the keyboard hears the field is left.
        c.startInput(FieldInfo.from(InputType.TYPE_NULL, 0))
        assertEquals(listOf("ana@example.org"), memory.matching("an"))
    }

    @Test
    fun otherFieldsNeitherOfferNorRemember() {
        memory.record("sean@example.com")
        val c = controller(InputType.TYPE_CLASS_TEXT)
        c.refreshEmails()
        assertFalse("sean@example.com" in shown)
        for (ch in "ana@example.org") c.typeText(ch.toString())
        c.refreshEmails()
        c.rememberEmails()
        assertEquals(emptyList<String>(), memory.matching("an"))
        // Nor a field that asks for no suggestions, even for email.
        val quiet = controller(emailField or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        quiet.refreshEmails()
        assertFalse("sean@example.com" in shown)
    }
}
