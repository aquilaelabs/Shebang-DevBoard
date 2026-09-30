package dev.shebang.devboard.input

import android.view.KeyEvent

/** A keycode plus whether Shift must be held to produce the character on a US layout. */
data class KeyStroke(val keyCode: Int, val shift: Boolean)

/** Maps printable ASCII to US-keyboard keycodes so Ctrl/Alt + a main-keyboard key can be sent as a real KeyEvent. */
object CharKeyCodes {
    private val plain = HashMap<Char, Int>(96)
    private val shifted = HashMap<Char, Int>(48)

    init {
        for (c in 'a'..'z') plain[c] = KeyEvent.KEYCODE_A + (c - 'a')
        for (c in 'A'..'Z') shifted[c] = KeyEvent.KEYCODE_A + (c - 'A')
        for (c in '0'..'9') plain[c] = KeyEvent.KEYCODE_0 + (c - '0')
        plain[' '] = KeyEvent.KEYCODE_SPACE
        plain['\n'] = KeyEvent.KEYCODE_ENTER
        plain['\t'] = KeyEvent.KEYCODE_TAB
        plain['-'] = KeyEvent.KEYCODE_MINUS
        plain['='] = KeyEvent.KEYCODE_EQUALS
        plain['['] = KeyEvent.KEYCODE_LEFT_BRACKET
        plain[']'] = KeyEvent.KEYCODE_RIGHT_BRACKET
        plain['\\'] = KeyEvent.KEYCODE_BACKSLASH
        plain[';'] = KeyEvent.KEYCODE_SEMICOLON
        plain['\''] = KeyEvent.KEYCODE_APOSTROPHE
        plain['`'] = KeyEvent.KEYCODE_GRAVE
        plain[','] = KeyEvent.KEYCODE_COMMA
        plain['.'] = KeyEvent.KEYCODE_PERIOD
        plain['/'] = KeyEvent.KEYCODE_SLASH
        val shiftPairs = ")!@#\$%^&*("
        for (i in shiftPairs.indices) shifted[shiftPairs[i]] = KeyEvent.KEYCODE_0 + i
        shifted['_'] = KeyEvent.KEYCODE_MINUS
        shifted['+'] = KeyEvent.KEYCODE_EQUALS
        shifted['{'] = KeyEvent.KEYCODE_LEFT_BRACKET
        shifted['}'] = KeyEvent.KEYCODE_RIGHT_BRACKET
        shifted['|'] = KeyEvent.KEYCODE_BACKSLASH
        shifted[':'] = KeyEvent.KEYCODE_SEMICOLON
        shifted['"'] = KeyEvent.KEYCODE_APOSTROPHE
        shifted['~'] = KeyEvent.KEYCODE_GRAVE
        shifted['<'] = KeyEvent.KEYCODE_COMMA
        shifted['>'] = KeyEvent.KEYCODE_PERIOD
        shifted['?'] = KeyEvent.KEYCODE_SLASH
    }

    fun forChar(c: Char): KeyStroke? {
        plain[c]?.let { return KeyStroke(it, false) }
        shifted[c]?.let { return KeyStroke(it, true) }
        return null
    }
}
