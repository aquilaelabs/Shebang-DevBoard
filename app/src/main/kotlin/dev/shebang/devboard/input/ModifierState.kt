package dev.shebang.devboard.input

import android.view.KeyEvent

enum class Modifier(val metaMask: Int, val keyCode: Int) {
    CTRL(KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON, KeyEvent.KEYCODE_CTRL_LEFT),
    ALT(KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON, KeyEvent.KEYCODE_ALT_LEFT),
    SHIFT(KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON, KeyEvent.KEYCODE_SHIFT_LEFT),
    META(KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON, KeyEvent.KEYCODE_META_LEFT);

    companion object {
        fun parse(name: String): Modifier? = when (name.lowercase()) {
            "ctrl", "control" -> CTRL
            "alt" -> ALT
            "shift" -> SHIFT
            "meta", "super", "cmd", "win" -> META
            else -> null
        }
    }
}

enum class ModState { OFF, ONESHOT, LOCKED }

/**
 * Sticky modifier state machine for the terminal bar.
 *
 * Tap while OFF -> ONESHOT (applies to the next key, then clears).
 * Second tap within [doubleTapMs] -> LOCKED (stays until tapped again).
 * Tap while LOCKED, or while ONESHOT after the double-tap window -> OFF.
 */
class ModifierState(private val doubleTapMs: Long = 350) {
    private val states = Array(Modifier.entries.size) { ModState.OFF }
    private val lastTap = LongArray(Modifier.entries.size)

    fun state(m: Modifier): ModState = states[m.ordinal]
    fun isActive(m: Modifier): Boolean = states[m.ordinal] != ModState.OFF
    val anyActive: Boolean get() = states.any { it != ModState.OFF }

    fun tap(m: Modifier, nowMs: Long) {
        val i = m.ordinal
        states[i] = when (states[i]) {
            ModState.OFF -> ModState.ONESHOT
            ModState.ONESHOT -> if (nowMs - lastTap[i] <= doubleTapMs) ModState.LOCKED else ModState.OFF
            ModState.LOCKED -> ModState.OFF
        }
        lastTap[i] = nowMs
    }

    /** Meta-state bits for the currently active modifiers, without consuming them. */
    fun metaState(): Int {
        var meta = 0
        for (m in Modifier.entries) if (states[m.ordinal] != ModState.OFF) meta = meta or m.metaMask
        return meta
    }

    /** Meta-state for the next key: returns the active bits and clears every one-shot modifier. */
    fun consume(): Int {
        val meta = metaState()
        for (i in states.indices) if (states[i] == ModState.ONESHOT) states[i] = ModState.OFF
        return meta
    }

    fun clearAll() {
        for (i in states.indices) states[i] = ModState.OFF
    }

    /** True when only Shift is active: a letter then simply types its capital instead of a KeyEvent. */
    val isShiftOnly: Boolean
        get() = isActive(Modifier.SHIFT) && !isActive(Modifier.CTRL) && !isActive(Modifier.ALT) && !isActive(Modifier.META)
}
