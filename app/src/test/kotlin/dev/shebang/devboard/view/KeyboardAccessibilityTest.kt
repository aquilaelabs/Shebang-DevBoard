package dev.shebang.devboard.view

import dev.shebang.devboard.EnglishStrings
import dev.shebang.devboard.R
import dev.shebang.devboard.layout.Key
import dev.shebang.devboard.layout.KeyDef
import org.junit.Assert.assertEquals
import org.junit.Test

/** What a screen reader says for each kind of key. */
class KeyboardAccessibilityTest {
    private fun key(def: KeyDef) = Key(def, 0, 0f, 0f, 10f, 10f)

    /** What a screen reader says, in English: the resource's text, or the key's own label. */
    private fun spoken(key: Key, shift: ShiftState, enter: Int = R.string.key_enter) =
        KeyboardAccessibility.spokenNameRes(key, shift, enter)?.let { EnglishStrings.of(it) } ?: KeyboardAccessibility.spokenLabel(key, shift)

    @Test
    fun lettersFollowShift() {
        val q = key(KeyDef(text = "q"))
        assertEquals("q", spoken(q, ShiftState.OFF))
        assertEquals("Q", spoken(q, ShiftState.ON))
    }

    @Test
    fun functionalKeysSayWhatTheyDo() {
        assertEquals("Delete", spoken(key(KeyDef(action = "backspace")), ShiftState.OFF))
        assertEquals("Enter", spoken(key(KeyDef(action = "enter")), ShiftState.OFF))
        assertEquals("Search", spoken(key(KeyDef(action = "enter")), ShiftState.OFF, R.string.key_search))
        assertEquals("Shift, caps lock", spoken(key(KeyDef(action = "shift")), ShiftState.LOCKED))
        assertEquals("Code mode", spoken(key(KeyDef(label = "#!", action = "mode_code")), ShiftState.OFF))
        assertEquals("Left", spoken(key(KeyDef(label = "←", code = "DPAD_LEFT")), ShiftState.OFF))
        assertEquals("?", spoken(key(KeyDef(text = "?")), ShiftState.ON))
    }
}
