package dev.shebang.devboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tidying dictation by rules (R16): hesitations, stutters, spoken corrections, "scratch that". */
class DictationCleanupTest {
    private fun tidy(s: String) = DictationCleanup.tidy(s).text

    @Test
    fun theRamblerExample() {
        assertEquals("So let's meet on Wednesday at three.", tidy("Um, so let's meet on Tuesday, no wait, Wednesday at, uh, three."))
    }

    @Test
    fun hesitationsGo() {
        assertEquals("I think we should go.", tidy("Um, I think, uh, we should go."))
        assertEquals("Hmm, interesting" .let { tidy(it) }, "Interesting")
    }

    @Test
    fun stuttersAndRepeatedPhrasesAreSaidOnce() {
        assertEquals("I think we should go.", tidy("I I think we should go."))
        assertEquals("We should go now.", tidy("We should, we should go now."))
        assertEquals("Can you send it.", tidy("Can you can you send it."))
    }

    @Test
    fun meantDoublesStay() {
        assertEquals("He had had enough.", tidy("He had had enough."))
        assertEquals("I know that that is true.", tidy("I know that that is true."))
    }

    @Test
    fun aRestatedCorrectionReplacesFromTheRepeatedWord() {
        assertEquals("Meet me on Wednesday.", tidy("Meet me on Tuesday, no wait, on Wednesday."))
        assertEquals("Send it to Sam.", tidy("Send it to Alex, I mean, to Sam."))
    }

    @Test
    fun aOneWordCorrectionReplacesTheWordBefore() {
        assertEquals("It costs fifty dollars.", tidy("It costs forty, sorry, fifty dollars."))
    }

    @Test
    fun correctionWordsUsedPlainlyStay() {
        assertEquals("I mean it.", tidy("I mean it."))
        assertEquals("Sorry I'm late.", tidy("Sorry I'm late."))
        assertEquals("I'd rather stay.", tidy("I'd rather stay."))
    }

    @Test
    fun scratchThatDropsTheSentenceBefore() {
        assertEquals("Hello there. See you tomorrow.", tidy("Hello there. Call me now, scratch that, see you tomorrow."))
    }

    @Test
    fun scratchThatAtTheStartTakesBackThePieceBefore() {
        val r = DictationCleanup.tidy("Scratch that. Let's meet on Friday.")
        assertTrue(r.dropPrevious)
        assertEquals("Let's meet on Friday.", r.text)
        assertFalse(DictationCleanup.tidy("Let's meet on Friday.").dropPrevious)
    }

    @Test
    fun plainSpeechIsUntouched() {
        val s = "And so my fellow Americans, ask not what your country can do for you."
        assertEquals(s, tidy(s))
    }
}

/** "Scratch that" at the start of a piece takes back the piece before, while it stands before the cursor. */
class DropDictationTest {
    @Test
    fun theLastPieceIsTakenBackWhileItStands() {
        val ic = FakeInputConnection("Note: ")
        val c = TextInputController(
            { ic },
            object : TextInputController.Ui {
                override fun showCandidates(words: List<String>) = Unit
                override fun setComposing(composing: Boolean) = Unit
            },
            java.util.concurrent.Executor { it.run() },
            android.os.Handler(android.os.Looper.getMainLooper()),
            postToMain = { it.run() },
        ).also { it.startInput(FieldInfo.from(android.text.InputType.TYPE_CLASS_TEXT, 0)) }
        c.insertDictation("Call me now.")
        assertEquals("Note: Call me now.", ic.toString())
        c.dropLastDictation()
        assertEquals("Note: ", ic.toString())
        c.insertDictation("See you tomorrow.")
        c.typeText("!")
        // Something was typed after it: it no longer stands right before the cursor, so it stays.
        c.dropLastDictation()
        assertEquals("Note: See you tomorrow.!", ic.toString())
    }
}
