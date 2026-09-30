package dev.shebang.devboard.ime

import android.os.Bundle
import android.os.Handler
import android.view.KeyEvent
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo

/** A text field in memory: enough of InputConnection for the text controller's logic. */
class FakeInputConnection(initial: String = "") : InputConnection {
    val text = StringBuilder(initial)
    var cursor = initial.length
    var composingStart = -1
        private set
    private var composingEnd = -1
    /** End of the selection, which starts at [cursor]; -1 when nothing is selected. */
    var selectionEnd = -1
    val keyEvents = ArrayList<KeyEvent>()
    var editorActions = 0

    override fun toString() = text.toString()

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = text.substring(maxOf(0, cursor - n), cursor)
    private val afterSelection get() = if (selectionEnd > cursor) selectionEnd else cursor
    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = text.substring(afterSelection, minOf(text.length, afterSelection + n))
    override fun getSelectedText(flags: Int): CharSequence? = if (selectionEnd > cursor) text.substring(cursor, selectionEnd) else null
    override fun getCursorCapsMode(reqModes: Int): Int = 0
    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? = null

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        val start = maxOf(0, cursor - beforeLength)
        val end = minOf(text.length, cursor + afterLength)
        text.delete(start, end)
        cursor = start
        selectionEnd = -1
        composingStart = -1
        composingEnd = -1
        return true
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int) = deleteSurroundingText(beforeLength, afterLength)

    override fun setComposingText(t: CharSequence, newCursorPosition: Int): Boolean {
        replaceComposing(t)
        composingStart = cursor - t.length
        composingEnd = cursor
        return true
    }

    private fun replaceComposing(t: CharSequence) {
        if (composingStart >= 0) {
            text.replace(composingStart, composingEnd, t.toString())
            cursor = composingStart + t.length
        } else {
            if (selectionEnd > cursor) text.delete(cursor, selectionEnd)
            text.insert(cursor, t)
            cursor += t.length
        }
        selectionEnd = -1
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        composingStart = start
        composingEnd = end
        return true
    }

    override fun finishComposingText(): Boolean {
        composingStart = -1
        composingEnd = -1
        return true
    }

    override fun commitText(t: CharSequence, newCursorPosition: Int): Boolean {
        replaceComposing(t)
        composingStart = -1
        composingEnd = -1
        // As Android: 1 puts the cursor after the text, 0 or less relative to its start.
        val start = cursor - t.length
        cursor = if (newCursorPosition > 0) (cursor + newCursorPosition - 1) else (start + newCursorPosition)
        cursor = cursor.coerceIn(0, text.length)
        return true
    }

    override fun commitCompletion(text: CompletionInfo?): Boolean = false
    override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean = false
    override fun setSelection(start: Int, end: Int): Boolean {
        cursor = start
        selectionEnd = if (end > start) end else -1
        return true
    }

    override fun performEditorAction(editorAction: Int): Boolean {
        editorActions++
        return true
    }

    override fun performContextMenuAction(id: Int): Boolean = false
    override fun beginBatchEdit(): Boolean = true
    override fun endBatchEdit(): Boolean = true
    override fun sendKeyEvent(event: KeyEvent): Boolean {
        keyEvents.add(event)
        return true
    }

    override fun clearMetaKeyStates(states: Int): Boolean = true
    override fun reportFullscreenMode(enabled: Boolean): Boolean = true
    override fun performPrivateCommand(action: String?, data: Bundle?): Boolean = false
    override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = false
    override fun getHandler(): Handler? = null
    override fun closeConnection() = Unit
    override fun commitContent(inputContentInfo: InputContentInfo, flags: Int, opts: Bundle?): Boolean = false
}
