package dev.shebang.devboard.ime

import android.view.inputmethod.InputConnection
import dev.shebang.devboard.dict.Dictionary
import dev.shebang.devboard.dict.Suggester
import java.util.concurrent.Executor

/**
 * The word the user pointed at, which the next glide or a tapped alternative replaces: shown in the middle of
 * the strip with its alternatives, underlined while it is the target, dropped when something else happens, and
 * replaced keeping its capitals. A collaborator of [TextInputController] (R27), which decides what becomes the
 * target ([TextInputController.findTarget]) and when it goes.
 */
internal class TargetedWord(private val host: Host) {
    /** What the targeted word needs from the controller around it. */
    interface Host {
        fun connection(): InputConnection?
        val ui: TextInputController.Ui
        val suggester: Suggester?
        /** The dictionary the remembered glides' word indices belong to. */
        val dictionary: Dictionary?
        val background: Executor
        fun postToMain(r: Runnable)
        /** The keyboard is about to change the field (selection reports soon after are its own). */
        fun ownEdit()
        val isComposing: Boolean
    }

    /**
     * The word the user pointed at. [at] is the cursor (or selection start) where it was found, -1 when not
     * known; [before] of its letters lie before the cursor and [after] after it, or it is the selection
     * ([selection]). [glided] is the same word as glided lately, when it was.
     */
    class Target(
        val text: String,
        val at: Int,
        val before: Int,
        val after: Int,
        val selection: Boolean,
        val glided: GlidedWord?,
        val underlined: Boolean,
    ) {
        /** Whether the word begins a sentence, set when the target is found. */
        var sentenceStart = false
    }

    /** The targeted word, or null when nothing is targeted. */
    var current: Target? = null

    /** Counts targets, so alternatives worked out for an older one are not shown. */
    private var generation = 0

    /** [t] is the target: shown in the middle of the strip with its own runners-up if it was glided, else suggestions. */
    fun set(t: Target) {
        current = t
        host.connection()?.let { t.sentenceStart = GlideText.contextWord(textBefore(it)) == GlideText.SENTENCE_START }
        val gen = ++generation
        val dictionary = host.dictionary
        val glided = t.glided
        if (glided != null && dictionary != null) {
            val alts = glided.word.candidates.filter { it >= 0 && it != glided.word.word }.take(2).map { caseFor(t, dictionary.words[it]) }
            show(t, alts)
            return
        }
        show(t, emptyList())
        val s = host.suggester ?: return
        host.background.execute {
            val found = s.suggest(t.text.lowercase(), 3).map { caseFor(t, it.word) }.filter { !it.equals(t.text, ignoreCase = true) }.take(2)
            host.postToMain { if (gen == generation && current === t) show(t, found) }
        }
    }

    /** The target is no longer wanted: remove its underline (the text stays as it is). */
    fun drop() {
        val t = current ?: return
        current = null
        generation++
        if (t.underlined) {
            host.connection()?.finishComposingText()
            host.ownEdit()
        }
        host.ui.showCandidates(emptyList())
        host.ui.setComposing(host.isComposing)
    }

    /** Replaces [t] with [replacement] (its capitals kept); true when it was still there to replace. */
    fun replace(t: Target, replacement: String): Boolean {
        val ic = host.connection() ?: return false
        host.ownEdit()
        current = null
        generation++
        ic.beginBatchEdit()
        if (t.underlined) ic.finishComposingText()
        val ok = if (t.selection) {
            ic.getSelectedText(0)?.toString() == t.text
        } else {
            val before = ic.getTextBeforeCursor(t.before, 0)?.toString() ?: ""
            val after = ic.getTextAfterCursor(t.after, 0)?.toString() ?: ""
            before + after == t.text
        }
        if (ok) {
            if (!t.selection) ic.deleteSurroundingText(t.before, t.after)
            ic.commitText(replacement, 1)
        }
        ic.endBatchEdit()
        return ok
    }

    /**
     * How [word] (in its dictionary casing) is written in place of the target: capitalised when the old word's
     * capital was the user's (a sentence start, or a normally lowercase word they shifted), in capitals when
     * they wrote it in capitals, and as the dictionary writes it otherwise. A capital that belongs to the old
     * word itself ("I", "I'd", a name) says nothing about the new one.
     */
    fun caseFor(t: Target, word: String): String {
        val old = t.text
        if (old.isEmpty() || word.isEmpty()) return word
        val dictionary = host.dictionary
        val oldForm = dictionary?.indexOfLower(old.lowercase())?.takeIf { it >= 0 }?.let { dictionary.words[it] }
        val allCaps = old.length > 1 && old.all { !it.isLetter() || it.isUpperCase() }
        if (allCaps && (oldForm == null || !oldForm.all { !it.isLetter() || it.isUpperCase() })) return word.uppercase()
        if (!old[0].isUpperCase()) return word
        val ownCapital = oldForm != null && oldForm[0].isUpperCase()
        return if (!ownCapital || t.sentenceStart) word.replaceFirstChar { it.uppercaseChar() } else word
    }

    /** Text before the target's first letter (or before the cursor when nothing is targeted), for context. */
    fun textBefore(ic: InputConnection): CharSequence {
        val t = current
        val before = ic.getTextBeforeCursor(CONTEXT_CHARS + (t?.before ?: 0), 0) ?: ""
        return if (t != null && !t.selection) before.subSequence(0, maxOf(0, before.length - t.before)) else before
    }

    private fun show(t: Target, alternatives: List<String>) {
        host.ui.showCandidates(TextInputController.arrangeBestMiddle(listOf(t.text) + alternatives))
        // The strip shows words (not the terminal bar) while a word is targeted.
        host.ui.setComposing(true)
    }

    private companion object {
        /** Characters read before the target for the word before it. */
        const val CONTEXT_CHARS = 64
    }
}
