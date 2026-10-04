package dev.shebang.devboard.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
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
    fun enterFollowsImeOptions() {
        val go = FieldInfo.from(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_GO)
        assertEquals(EditorInfo.IME_ACTION_GO, go.editorAction)
        assertFalse(go.enterIsNewline)
        assertFalse(go.enterIsKeyEvent)

        val multi = FieldInfo.from(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, EditorInfo.IME_ACTION_SEND)
        assertTrue(multi.multiline)
        assertTrue(multi.enterIsNewline)

        val noAction = FieldInfo.from(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_ENTER_ACTION)
        assertTrue(noAction.enterIsNewline)

        val unspecified = FieldInfo.from(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_UNSPECIFIED)
        assertTrue(unspecified.enterIsKeyEvent)
    }
}
