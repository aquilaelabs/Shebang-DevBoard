package dev.shebang.devboard.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import dev.shebang.devboard.EnglishStrings
import dev.shebang.devboard.layout.FieldVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FieldInfoTest {
    @Test
    fun terminalFieldsNeverCompose() {
        val f = FieldInfo.from(InputType.TYPE_NULL, 0)
        assertTrue(f.isTerminal)
        assertFalse(f.allowsComposing)
        assertFalse(f.allowsGlide)
        assertTrue(f.enterIsKeyEvent)
    }

    @Test
    fun passwordAndNoSuggestionsDisableSuggestions() {
        val pw = FieldInfo.from(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, EditorInfo.IME_ACTION_DONE)
        assertTrue(pw.isPassword)
        assertFalse(pw.allowsComposing)
        val ns = FieldInfo.from(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, 0)
        assertTrue(ns.noSuggestions)
        assertFalse(ns.allowsComposing)
        val numPw = FieldInfo.from(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, 0)
        assertTrue(numPw.isPassword)
    }

    @Test
    fun numericClassesUseTheNumericLayout() {
        assertEquals(FieldVariant.NUMBER, FieldInfo.from(InputType.TYPE_CLASS_NUMBER, 0).variant)
        assertEquals(FieldVariant.PHONE, FieldInfo.from(InputType.TYPE_CLASS_PHONE, 0).variant)
        assertEquals(FieldVariant.DATE, FieldInfo.from(InputType.TYPE_CLASS_DATETIME, 0).variant)
        assertTrue(FieldInfo.from(InputType.TYPE_CLASS_PHONE, 0).isNumeric)
        assertFalse(FieldInfo.from(InputType.TYPE_CLASS_NUMBER, 0).allowsComposing)
    }

    @Test
    fun emailAndUrlVariants() {
        assertEquals(FieldVariant.EMAIL, FieldInfo.from(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, 0).variant)
        assertEquals(FieldVariant.EMAIL, FieldInfo.from(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, 0).variant)
        assertEquals(FieldVariant.URL, FieldInfo.from(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, 0).variant)
    }

    @Test
    fun aPlainFieldAskingForAnEmailIsAnEmailField() {
        val text = InputType.TYPE_CLASS_TEXT
        assertEquals(FieldVariant.EMAIL, FieldInfo.from(text, 0, "Email").variant)
        assertEquals(FieldVariant.EMAIL, FieldInfo.from(text or InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT, 0, "Your e-mail address").variant)
        assertEquals(FieldVariant.EMAIL, FieldInfo.from(text, 0, "Email or username").variant)
        // Not a word containing it, a field of another kind, or a message.
        assertEquals(FieldVariant.PLAIN, FieldInfo.from(text, 0, "Gmail search").variant)
        assertEquals(FieldVariant.PLAIN, FieldInfo.from(text or InputType.TYPE_TEXT_VARIATION_EMAIL_SUBJECT, 0, "Email subject").variant)
        assertEquals(FieldVariant.PLAIN, FieldInfo.from(text or InputType.TYPE_TEXT_FLAG_MULTI_LINE, 0, "Compose email").variant)
        assertEquals(FieldVariant.PLAIN, FieldInfo.from(text, 0, "Name").variant)
        assertEquals(true, FieldInfo.from(text or InputType.TYPE_TEXT_VARIATION_PASSWORD, 0, "Email password").isPassword)
    }

    @Test
    fun aWebFieldWithoutTheAutocorrectFlagIsExact() {
        // What Firefox 157 reports: xterm.js's input (autocorrect=off), a plain textarea, a text input.
        assertTrue(FieldInfo.from(0x400a1, 0x12000001).exact)
        assertTrue(FieldInfo.from(0x400a1, 0x12000001).noAutocorrect)
        assertFalse(FieldInfo.from(0x4c0a1, 0x12000001).exact)
        assertFalse(FieldInfo.from(0x480a1, 0x12000001).exact)
        // Not a web field: an app's own text box rarely sets the flag and still wants autocorrect.
        assertFalse(FieldInfo.from(InputType.TYPE_CLASS_TEXT, 0).exact)
        assertFalse(FieldInfo.from(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, 0).exact)
        // Suggestions and glide stay.
        assertTrue(FieldInfo.from(0x400a1, 0x12000001).allowsGlide)
    }

    @Test
    fun theEnterKeyWearsAGlyphForWhatItDoes() {
        val text = InputType.TYPE_CLASS_TEXT
        val multi = text or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        fun kind(type: Int, options: Int) = FieldInfo.from(type, options).enterKind
        assertEquals(EnterKind.SEARCH, kind(text, EditorInfo.IME_ACTION_SEARCH))
        assertEquals(EnterKind.SEARCH, kind(multi, EditorInfo.IME_ACTION_SEARCH))
        assertEquals(EnterKind.SUBMIT, kind(text or InputType.TYPE_TEXT_VARIATION_URI, EditorInfo.IME_ACTION_GO))
        assertEquals(EnterKind.SUBMIT, kind(text, EditorInfo.IME_ACTION_SEND))
        assertEquals(EnterKind.SUBMIT, kind(text, EditorInfo.IME_ACTION_DONE))
        // A new line, a plain Enter, moving between fields, and terminals keep the return arrow.
        assertEquals(EnterKind.RETURN, kind(multi, EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION))
        assertEquals(EnterKind.RETURN, kind(text, EditorInfo.IME_ACTION_UNSPECIFIED))
        assertEquals(EnterKind.RETURN, kind(text, EditorInfo.IME_ACTION_NEXT))
        assertEquals(EnterKind.RETURN, kind(text, EditorInfo.IME_ACTION_PREVIOUS))
        assertEquals(EnterKind.RETURN, kind(InputType.TYPE_NULL, EditorInfo.IME_ACTION_SEARCH))

        assertEquals("Search", EnglishStrings.of(FieldInfo.from(text, EditorInfo.IME_ACTION_SEARCH).enterSpoken))
        assertEquals("Send", EnglishStrings.of(FieldInfo.from(text, EditorInfo.IME_ACTION_SEND).enterSpoken))
        assertEquals("Go", EnglishStrings.of(FieldInfo.from(text, EditorInfo.IME_ACTION_GO).enterSpoken))
        assertEquals("Done", EnglishStrings.of(FieldInfo.from(text, EditorInfo.IME_ACTION_DONE).enterSpoken))
        assertEquals("Enter", EnglishStrings.of(FieldInfo.from(multi, EditorInfo.IME_FLAG_NO_ENTER_ACTION).enterSpoken))
    }

    @Test
    fun enterFollowsImeOptions() {
        val go = FieldInfo.from(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_GO)
        assertEquals(EditorInfo.IME_ACTION_GO, go.editorAction)
        assertFalse(go.enterIsNewline)
        assertFalse(go.enterIsKeyEvent)

        // A multi-line field that asks for an action without IME_FLAG_NO_ENTER_ACTION gets it (the Play Store's
        // search box, B14); with the flag, as TextView adds to every multi-line EditText, Enter is a new line.
        val multiSearch = FieldInfo.from(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, EditorInfo.IME_ACTION_SEARCH)
        assertTrue(multiSearch.multiline)
        assertFalse(multiSearch.enterIsNewline)
        assertFalse(multiSearch.enterIsKeyEvent)
        assertEquals(EditorInfo.IME_ACTION_SEARCH, multiSearch.editorAction)

        val messageBox = FieldInfo.from(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION,
        )
        assertTrue(messageBox.enterIsNewline)

        val multiPlain = FieldInfo.from(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, EditorInfo.IME_ACTION_UNSPECIFIED)
        assertTrue(multiPlain.enterIsNewline)
        val multiNone = FieldInfo.from(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE, EditorInfo.IME_ACTION_NONE)
        assertTrue(multiNone.enterIsNewline)

        val noAction = FieldInfo.from(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_ENTER_ACTION)
        assertTrue(noAction.enterIsNewline)

        val unspecified = FieldInfo.from(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_UNSPECIFIED)
        assertTrue(unspecified.enterIsKeyEvent)
    }
}
