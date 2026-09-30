package dev.shebang.devboard.input

import android.view.KeyEvent

/** What to send for one logical key press: a down/up pair with this keycode and meta state. */
data class KeyEventPlan(val keyCode: Int, val metaState: Int) {
    val hasCtrl: Boolean get() = metaState and KeyEvent.META_CTRL_ON != 0
    val hasAlt: Boolean get() = metaState and KeyEvent.META_ALT_ON != 0
    val hasShift: Boolean get() = metaState and KeyEvent.META_SHIFT_ON != 0
}

/** Result of combining a main-keyboard character with the sticky modifiers. */
sealed class CharDispatch {
    /** Insert this text (no modifiers, or Shift only). */
    data class Text(val text: String) : CharDispatch()
    /** Send a KeyEvent (Ctrl/Alt/Meta held). */
    data class Event(val plan: KeyEventPlan) : CharDispatch()
}

object KeyEventMapper {
    /** Meta bits for a set of modifier names from bar JSON ("ctrl", "shift"). */
    fun metaFor(mods: Collection<String>): Int {
        var meta = 0
        for (name in mods) Modifier.parse(name)?.let { meta = meta or it.metaMask }
        return meta
    }

    /** A bar/layout key with an explicit keycode: its own modifiers plus the sticky ones. */
    fun planForKey(keyCode: Int, ownMeta: Int, stickyMeta: Int): KeyEventPlan = KeyEventPlan(keyCode, ownMeta or stickyMeta)

    /**
     * A main-keyboard character with sticky modifiers active.
     * Shift alone (or nothing) types text: "Shift + a letter just types the capital".
     * Ctrl/Alt/Meta produce a KeyEvent for the character's US keycode, adding Shift when the character needs it.
     */
    fun dispatchChar(text: String, stickyMeta: Int): CharDispatch {
        val nonShift = stickyMeta and (KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON or KeyEvent.META_META_ON)
        if (nonShift == 0) {
            val shifted = stickyMeta and KeyEvent.META_SHIFT_ON != 0
            return CharDispatch.Text(if (shifted) text.uppercase() else text)
        }
        val c = text.singleOrNull() ?: return CharDispatch.Text(text)
        val stroke = CharKeyCodes.forChar(c) ?: return CharDispatch.Text(text)
        var meta = stickyMeta
        if (stroke.shift || (c.isLetter() && c.isUpperCase())) meta = meta or Modifier.SHIFT.metaMask
        return CharDispatch.Event(KeyEventPlan(stroke.keyCode, meta))
    }
}
