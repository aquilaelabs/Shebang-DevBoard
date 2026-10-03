package dev.shebang.devboard.view

import dev.shebang.devboard.layout.Key
import dev.shebang.devboard.layout.KeyDef
import org.junit.Assert.assertEquals
import org.junit.Test

/** What a screen reader says for each kind of key. */
class KeyboardAccessibilityTest {
    private fun key(def: KeyDef) = Key(def, 0, 0f, 0f, 10f, 10f)

    @Test
    fun lettersFollowShift() {
        val q = key(KeyDef(text = "q"))
        assertEquals("q", KeyboardAccessibility.spokenName(q, ShiftState.OFF))
        assertEquals("Q", KeyboardAccessibility.spokenName(q, ShiftState.ON))
    }

    @Test
    fun functionalKeysSayWhatTheyDo() {
        assertEquals("Delete", KeyboardAccessibility.spokenName(key(KeyDef(action = "backspace")), ShiftState.OFF))
        assertEquals("Shift, caps lock", KeyboardAccessibility.spokenName(key(KeyDef(action = "shift")), ShiftState.LOCKED))
        assertEquals("Code mode", KeyboardAccessibility.spokenName(key(KeyDef(label = "#!", action = "mode_code")), ShiftState.OFF))
        assertEquals("Left", KeyboardAccessibility.spokenName(key(KeyDef(label = "←", code = "DPAD_LEFT")), ShiftState.OFF))
        assertEquals("?", KeyboardAccessibility.spokenName(key(KeyDef(text = "?")), ShiftState.ON))
    }
}
