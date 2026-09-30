package dev.shebang.devboard.layout

import android.view.KeyEvent

/**
 * Names accepted in layout and bar JSON, mapped to Android keycodes.
 * The constants are compile-time ints, so this table works in plain JVM tests too.
 */
object KeyCodeNames {
    private val table: Map<String, Int> = buildMap {
        put("ESCAPE", KeyEvent.KEYCODE_ESCAPE)
        put("TAB", KeyEvent.KEYCODE_TAB)
        put("ENTER", KeyEvent.KEYCODE_ENTER)
        put("SPACE", KeyEvent.KEYCODE_SPACE)
        put("DEL", KeyEvent.KEYCODE_DEL)
        put("FORWARD_DEL", KeyEvent.KEYCODE_FORWARD_DEL)
        put("INSERT", KeyEvent.KEYCODE_INSERT)
        put("DPAD_LEFT", KeyEvent.KEYCODE_DPAD_LEFT)
        put("DPAD_RIGHT", KeyEvent.KEYCODE_DPAD_RIGHT)
        put("DPAD_UP", KeyEvent.KEYCODE_DPAD_UP)
        put("DPAD_DOWN", KeyEvent.KEYCODE_DPAD_DOWN)
        put("MOVE_HOME", KeyEvent.KEYCODE_MOVE_HOME)
        put("MOVE_END", KeyEvent.KEYCODE_MOVE_END)
        put("PAGE_UP", KeyEvent.KEYCODE_PAGE_UP)
        put("PAGE_DOWN", KeyEvent.KEYCODE_PAGE_DOWN)
        put("CTRL_LEFT", KeyEvent.KEYCODE_CTRL_LEFT)
        put("ALT_LEFT", KeyEvent.KEYCODE_ALT_LEFT)
        put("SHIFT_LEFT", KeyEvent.KEYCODE_SHIFT_LEFT)
        put("META_LEFT", KeyEvent.KEYCODE_META_LEFT)
        put("MINUS", KeyEvent.KEYCODE_MINUS)
        put("EQUALS", KeyEvent.KEYCODE_EQUALS)
        put("LEFT_BRACKET", KeyEvent.KEYCODE_LEFT_BRACKET)
        put("RIGHT_BRACKET", KeyEvent.KEYCODE_RIGHT_BRACKET)
        put("BACKSLASH", KeyEvent.KEYCODE_BACKSLASH)
        put("SEMICOLON", KeyEvent.KEYCODE_SEMICOLON)
        put("APOSTROPHE", KeyEvent.KEYCODE_APOSTROPHE)
        put("GRAVE", KeyEvent.KEYCODE_GRAVE)
        put("COMMA", KeyEvent.KEYCODE_COMMA)
        put("PERIOD", KeyEvent.KEYCODE_PERIOD)
        put("SLASH", KeyEvent.KEYCODE_SLASH)
        for (i in 0..9) put("$i", KeyEvent.KEYCODE_0 + i)
        for (c in 'A'..'Z') put("$c", KeyEvent.KEYCODE_A + (c - 'A'))
        for (i in 1..12) put("F$i", KeyEvent.KEYCODE_F1 + (i - 1))
    }

    fun lookup(name: String): Int? = table[name.uppercase()]

    /** All names, for the bar editor's key picker. */
    val names: List<String> get() = table.keys.sorted()
}
