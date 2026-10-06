package dev.shebang.devboard.ime

import android.view.inputmethod.InputConnection

/**
 * What autocorrect did in this field, for taking it back: the latest corrections, found again by the text
 * before them so backspace to one offers what was typed, and the words the user took back, which then stay as
 * typed. A collaborator of [TextInputController] (R27); everything is forgotten when the field changes.
 */
internal class Corrections {
    /**
     * A word autocorrect changed, found again by the text before it: [upTo] is the text up to and including
     * [corrected] as it stood after the correction ([atStart]: all of the text before it, which was short).
     * [other] is the third word the strip offered for [typed] beside the correction, so going back to the word
     * shows the strip as it was before the correction.
     */
    class Entry(val typed: String, val corrected: String, val upTo: String, val atStart: Boolean, val other: String?)

    /** The latest corrections, oldest first. */
    private val entries = ArrayDeque<Entry>()
    /** Words the user took back from autocorrect (lowercase): typed again, they stay as typed. */
    private val keptAsTyped = HashSet<String>()

    fun clear() {
        entries.clear()
        keptAsTyped.clear()
    }

    /** The user took [word] back from autocorrect, or chose it as typed: it is not corrected again here. */
    fun keepAsTyped(word: String) {
        keptAsTyped += word.lowercase()
    }

    fun isKeptAsTyped(word: String) = word.lowercase() in keptAsTyped

    /** A correction taken back: it is no longer offered for undoing. */
    fun forget(typed: String, corrected: String) {
        entries.removeAll { it.typed == typed && it.corrected == corrected }
    }

    /** Remembers that autocorrect wrote [corrected] for [typed], with [trailing] characters after it before the cursor. */
    fun remember(ic: InputConnection, typed: String, corrected: String, trailing: Int, other: String?) {
        val n = CONTEXT + corrected.length + trailing
        val before = ic.getTextBeforeCursor(n, 0)?.toString() ?: return
        val upTo = before.dropLast(trailing)
        if (before.length < trailing || !upTo.endsWith(corrected)) return
        entries.addLast(Entry(typed, corrected, upTo, atStart = before.length < n, other))
        if (entries.size > MAX) entries.removeFirst()
    }

    /** The remembered correction that [text], the word just before the cursor, still is, in the same place. */
    fun endingAtCursor(ic: InputConnection, text: String): Entry? {
        for (c in entries.asReversed()) {
            if (c.corrected != text) continue
            val before = ic.getTextBeforeCursor(c.upTo.length + 1, 0)?.toString() ?: return null
            if (if (c.atStart) before == c.upTo else before.length > c.upTo.length && before.endsWith(c.upTo)) return c
        }
        return null
    }

    private companion object {
        /** Characters before a corrected word that find it again: enough to tell two of the same word apart. */
        const val CONTEXT = 32
        /** Corrections remembered per field for backspace to take back. */
        const val MAX = 16
    }
}
