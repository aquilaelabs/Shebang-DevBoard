package dev.shebang.devboard.view

import android.graphics.Rect
import android.os.Bundle
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import dev.shebang.devboard.layout.Key
import dev.shebang.devboard.layout.KeyAction

/**
 * The keyboard for TalkBack and other screen readers: each drawn key is a virtual view with a spoken name,
 * found by exploring with a finger, and typed when activated (TalkBack's lift-to-type in a keyboard, or a
 * double tap). Glide, long-press rows and swipes are touch gestures a screen reader's exploration replaces,
 * so they are not offered here.
 */
internal class KeyboardAccessibility(private val keyboard: KeyboardView) : ExploreByTouchHelper(keyboard) {
    private val bounds = Rect()

    private fun keys(): List<Key> = keyboard.geometry?.keys.orEmpty()

    override fun getVirtualViewAt(x: Float, y: Float): Int {
        val keys = keys()
        val i = keys.indexOfFirst { !it.def.spacer && it.contains(x, y) }
        return if (i >= 0) i else INVALID_ID
    }

    override fun getVisibleVirtualViews(ids: MutableList<Int>) {
        keys().forEachIndexed { i, k -> if (!k.def.spacer) ids += i }
    }

    // ExploreByTouchHelper requires bounds in the parent, though the node method is marked deprecated.
    @Suppress("DEPRECATION")
    override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
        val key = keys().getOrNull(id)
        if (key == null) {
            // A key that went with a layout change: an empty node the platform drops.
            node.contentDescription = ""
            node.setBoundsInParent(bounds.apply { setEmpty() })
            return
        }
        node.contentDescription = spokenName(key, keyboard.shiftState)
        node.className = "android.widget.Button"
        node.isClickable = true
        node.addAction(AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK)
        bounds.set(key.left.toInt(), key.top.toInt(), key.right.toInt(), key.bottom.toInt())
        node.setBoundsInParent(bounds)
    }

    override fun onPerformActionForVirtualView(id: Int, action: Int, arguments: Bundle?): Boolean {
        if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
        val key = keys().getOrNull(id) ?: return false
        keyboard.accessibilityTap(key)
        // Shift and the mode keys change what the keys say.
        invalidateRoot()
        return true
    }

    companion object {
        /** What a screen reader says for [key]: the character it types, or what a functional key does. */
        fun spokenName(key: Key, shift: ShiftState): String = when (key.action) {
            KeyAction.SHIFT -> when (shift) {
                ShiftState.OFF -> "Shift"
                ShiftState.ON -> "Shift, on"
                ShiftState.LOCKED -> "Shift, caps lock"
            }
            KeyAction.BACKSPACE -> "Delete"
            KeyAction.ENTER -> "Enter"
            KeyAction.SPACE -> "Space"
            KeyAction.MODE_CODE -> "Code mode"
            KeyAction.MODE_TEXT -> "Letters"
            KeyAction.NONE -> when (key.def.code) {
                "DPAD_LEFT" -> "Left"
                "DPAD_RIGHT" -> "Right"
                "DPAD_UP" -> "Up"
                "DPAD_DOWN" -> "Down"
                null -> if (shift != ShiftState.OFF) key.shiftedLabel else key.label
                else -> key.label
            }
        }
    }
}
