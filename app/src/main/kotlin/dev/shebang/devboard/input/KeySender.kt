package dev.shebang.devboard.input

import android.os.SystemClock
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.InputConnection

/**
 * Sends real hardware-style KeyEvents (down + up, with meta state) through the InputConnection,
 * bracketed by the modifier keys' own down/up so apps that track modifiers see a consistent stream.
 */
object KeySender {
    private val modifierOrder = arrayOf(Modifier.CTRL, Modifier.ALT, Modifier.SHIFT, Modifier.META)

    fun send(ic: InputConnection?, plan: KeyEventPlan) {
        ic ?: return
        val now = SystemClock.uptimeMillis()
        val meta = plan.metaState
        // Modifier downs, accumulating meta as a physical keyboard would.
        var acc = 0
        ic.beginBatchEdit()
        try {
            for (m in modifierOrder) {
                if (meta and m.metaMask != 0) {
                    acc = acc or m.metaMask
                    ic.sendKeyEvent(event(now, KeyEvent.ACTION_DOWN, m.keyCode, acc))
                }
            }
            ic.sendKeyEvent(event(now, KeyEvent.ACTION_DOWN, plan.keyCode, meta))
            ic.sendKeyEvent(event(now, KeyEvent.ACTION_UP, plan.keyCode, meta))
            for (m in modifierOrder.reversed()) {
                if (meta and m.metaMask != 0) {
                    ic.sendKeyEvent(event(now, KeyEvent.ACTION_UP, m.keyCode, acc))
                    acc = acc and m.metaMask.inv()
                }
            }
        } finally {
            ic.endBatchEdit()
        }
    }

    fun sendPlain(ic: InputConnection?, keyCode: Int) = send(ic, KeyEventPlan(keyCode, 0))

    private fun event(time: Long, action: Int, code: Int, meta: Int): KeyEvent =
        KeyEvent(time, time, action, code, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE)
}
