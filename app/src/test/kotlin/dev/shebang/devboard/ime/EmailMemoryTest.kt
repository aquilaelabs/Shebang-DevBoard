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
    /** The address cards on the strip now. */
    private var cards: List<String> = emptyList()

    private fun controller(inputType: Int) = TextInputController(
        { ic },
        object : TextInputController.Ui {
            override fun showCandidates(words: List<String>) {
                shown = words
            }
            override fun setComposing(composing: Boolean) = Unit
            override fun showEmails(addresses: List<String>) {
                cards = addresses
            }
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

    private fun type(c: TextInputController, s: String) {
        for (ch in s) {
            c.typeText(ch.toString())
            c.refreshEmails()
        }
    }

    @Test
    fun anEmailFieldOffersTheAddressBeingTypedAsACard() {
        memory.record("sean@example.com")
        val c = controller(emailField)
        // Nothing offered when the field opens: its row is the password manager's.
        c.refreshEmails(edited = false)
        assertEquals(emptyList<String>(), cards)
        type(c, "se")
        assertEquals(listOf("sean@example.com"), cards)
        // Cards, not words on the strip.
        assertFalse("sean@example.com" in shown)
        c.pickEmail("sean@example.com")
        assertEquals("sean@example.com", ic.toString())
        assertEquals(emptyList<String>(), cards)
    }

    @Test
    fun everyAddressThatBeginsTheSameIsOffered() {
        memory.record("sean.bowman@gmail.com")
        memory.record("sean.bowman@live.com")
        memory.record("someone@else.org")
        val c = controller(emailField)
        type(c, "sean.b")
        assertEquals(setOf("sean.bowman@gmail.com", "sean.bowman@live.com"), cards.toSet())
        type(c, "owman@l")
        assertEquals(listOf("sean.bowman@live.com"), cards)
    }

    @Test
    fun noCardsWhileWhatIsTypedCouldStillBeAWord() {
        memory.record("sean.bowman@gmail.com")
        val c = controller(emailField)
        c.predictionModel = dev.shebang.devboard.dict.NgramModelTest.dictionary to dev.shebang.devboard.dict.NgramModelTest.lm
        type(c, "sea")
        assertEquals(emptyList<String>(), cards)
        type(c, "n.")
        assertEquals(listOf("sean.bowman@gmail.com"), cards)
    }

    @Test
    fun swipedAwayCardsStayAwayUntilTheNextAddress() {
        memory.record("sean.bowman@gmail.com")
        memory.record("ana@example.org")
        val c = controller(emailField)
        type(c, "sean.")
        assertEquals(listOf("sean.bowman@gmail.com"), cards)
        c.dismissEmails()
        type(c, "b")
        assertEquals(emptyList<String>(), cards)
        // The next address in the field gets its cards.
        type(c, " an")
        assertEquals(listOf("ana@example.org"), cards)
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
        type(c, "se")
        assertEquals(emptyList<String>(), cards)
        for (ch in "ana@example.org") c.typeText(ch.toString())
        c.refreshEmails()
        c.rememberEmails()
        assertEquals(emptyList<String>(), memory.matching("an"))
        // Nor a field that asks for no suggestions, even for email.
        ic.finishComposingText()
        ic.text.setLength(0)
        ic.cursor = 0
        val quiet = controller(emailField or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        type(quiet, "se")
        assertEquals(emptyList<String>(), cards)
    }
}
