package dev.shebang.devboard.ime

import android.os.Handler
import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import dev.shebang.devboard.dict.Suggester
import dev.shebang.devboard.dict.Suggestion
import dev.shebang.devboard.glide.GlideCandidate
import dev.shebang.devboard.input.KeySender
import dev.shebang.devboard.settings.Settings
import java.util.concurrent.Executor

/**
 * Text-mode editing: composing words, suggestions, autocorrect, smart spacing after a glide, double-space
 * period, backspace-after-glide. Everything field-aware goes through [field]; terminals and password fields
 * never compose and never keep typed text.
 */
class TextInputController(
    private val connection: () -> InputConnection?,
    private val ui: Ui,
    private val background: Executor,
    private val main: Handler,
) {
    interface Ui {
        /** Words for the strip, best first; empty clears it. */
        fun showCandidates(words: List<String>)
        fun setComposing(composing: Boolean)
    }

    var field: FieldInfo = FieldInfo.from(null)
        private set
    var settings: Settings = Settings()
    var suggester: Suggester? = null

    private val word = StringBuilder(32)
    private var candidates: List<Suggestion> = emptyList()
    private var suggestGeneration = 0
    private var lastSpaceTime = 0L
    private var lastActionWasSpace = false

    /** The most recent glide commit, while it is still the last thing typed. */
    private class GlideCommit(var word: String, val alternatives: List<String>)
    private var lastGlide: GlideCommit? = null

    val isComposing: Boolean get() = word.isNotEmpty()

    fun startInput(field: FieldInfo) {
        this.field = field
        resetState()
    }

    private fun resetState() {
        word.setLength(0)
        candidates = emptyList()
        lastGlide = null
        lastActionWasSpace = false
        ui.showCandidates(emptyList())
        ui.setComposing(false)
    }

    /** Cursor moved (by the user or another app): stop composing but leave the text as it is. */
    fun onSelectionChanged(newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        if (isComposing) {
            val insideComposing = newSelStart == newSelEnd && newSelStart == candidatesEnd && candidatesStart >= 0
            if (!insideComposing) {
                connection()?.finishComposingText()
                word.setLength(0)
                clearCandidates()
            }
        } else if (lastGlide != null && newSelStart != newSelEnd) {
            lastGlide = null
            clearCandidates()
        }
    }

    // ---- Typing --------------------------------------------------------------------------------------

    fun typeText(text: String) {
        val ic = connection() ?: return
        lastActionWasSpace = false
        val glideBefore = lastGlide
        lastGlide = null
        if (glideBefore != null) clearCandidates()
        if (field.allowsComposing && isWordChar(text)) {
            word.append(text)
            ic.setComposingText(word, 1)
            ui.setComposing(true)
            requestSuggestions()
            return
        }
        finishComposing(ic)
        ic.commitText(text, 1)
    }

    private fun isWordChar(text: String): Boolean {
        if (text.length != 1) return false
        val c = text[0]
        return c.isLetter() || (c == '\'' && word.isNotEmpty())
    }

    fun space() {
        val ic = connection() ?: return
        lastGlide?.let { lastGlide = null; clearCandidates() }
        val now = SystemClock.uptimeMillis()
        if (isComposing) {
            val typed = word.toString()
            var commit = typed
            if (settings.autocorrect) suggester?.autocorrect(typed)?.let { commit = Suggester.matchCase(typed, it) }
            ic.commitText(commit, 1)
            word.setLength(0)
            clearCandidates()
            ic.commitText(" ", 1)
        } else if (settings.doubleSpacePeriod && field.allowsComposing && lastActionWasSpace && now - lastSpaceTime < DOUBLE_SPACE_MS && endsSentenceWord(ic)) {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(1, 0)
            ic.commitText(". ", 1)
            ic.endBatchEdit()
            lastActionWasSpace = false
            return
        } else {
            ic.commitText(" ", 1)
        }
        lastActionWasSpace = true
        lastSpaceTime = now
    }

    /** True when the two characters before the cursor are a word character then a space. */
    private fun endsSentenceWord(ic: InputConnection): Boolean {
        val before = ic.getTextBeforeCursor(2, 0) ?: return false
        if (before.length < 2 || before[1] != ' ') return false
        val c = before[0]
        return c.isLetterOrDigit() || c == ')' || c == ']' || c == '"' || c == '\''
    }

    fun backspace() {
        val ic = connection() ?: return
        lastActionWasSpace = false
        val glide = lastGlide
        if (glide != null) {
            // Backspace right after a glide removes the whole word.
            ic.deleteSurroundingText(glide.word.length, 0)
            lastGlide = null
            clearCandidates()
            return
        }
        if (isComposing) {
            word.setLength(word.length - 1)
            if (word.isEmpty()) {
                ic.setComposingText("", 1)
                ic.finishComposingText()
                clearCandidates()
            } else {
                ic.setComposingText(word, 1)
                requestSuggestions()
            }
            return
        }
        if (field.isTerminal) {
            KeySender.sendPlain(ic, KeyEvent.KEYCODE_DEL)
            return
        }
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) {
            ic.commitText("", 1)
            return
        }
        // Delete a whole surrogate pair (emoji) at once.
        val before = ic.getTextBeforeCursor(2, 0)
        val n = if (before != null && before.length == 2 && Character.isSurrogatePair(before[0], before[1])) 2 else 1
        ic.deleteSurroundingText(n, 0)
    }

    fun enter() {
        val ic = connection() ?: return
        lastActionWasSpace = false
        lastGlide = null
        finishComposing(ic)
        clearCandidates()
        when {
            field.enterIsNewline -> ic.commitText("\n", 1)
            field.enterIsKeyEvent -> KeySender.sendPlain(ic, KeyEvent.KEYCODE_ENTER)
            else -> ic.performEditorAction(field.editorAction)
        }
    }

    /** Commits the composing word (if any) so a KeyEvent or snippet lands after it. */
    fun finishComposing() {
        val ic = connection() ?: return
        finishComposing(ic)
        lastGlide = null
        clearCandidates()
    }

    private fun finishComposing(ic: InputConnection) {
        if (isComposing) {
            ic.finishComposingText()
            word.setLength(0)
            clearCandidates()
        }
    }

    // ---- Suggestions ---------------------------------------------------------------------------------

    private fun clearCandidates() {
        candidates = emptyList()
        suggestGeneration++
        ui.showCandidates(emptyList())
        ui.setComposing(isComposing)
    }

    private fun requestSuggestions() {
        val s = suggester ?: return
        val typed = word.toString()
        val gen = ++suggestGeneration
        background.execute {
            val result = s.suggest(typed, 3)
            main.post {
                if (gen != suggestGeneration || !isComposing) return@post
                candidates = result
                ui.showCandidates(arrangeForStrip(typed, result.map { it.word }))
            }
        }
    }

    /** Picks a strip candidate: replaces the composing word, or swaps the glided word. */
    fun pickCandidate(chosen: String) {
        val ic = connection() ?: return
        val glide = lastGlide
        if (glide != null) {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(glide.word.length, 0)
            ic.commitText(chosen, 1)
            ic.endBatchEdit()
            glide.word = chosen
            return
        }
        if (isComposing) {
            ic.commitText("$chosen ", 1)
            word.setLength(0)
            clearCandidates()
            lastActionWasSpace = true
            lastSpaceTime = SystemClock.uptimeMillis()
        }
    }

    // ---- Glide ---------------------------------------------------------------------------------------

    fun commitGlide(results: List<GlideCandidate>, capitalize: Boolean) {
        val ic = connection() ?: return
        if (results.isEmpty()) return
        finishComposing(ic)
        lastActionWasSpace = false
        val words = results.map { if (capitalize) it.word.replaceFirstChar { c -> c.uppercaseChar() } else it.word }
        val best = words[0]
        val lead = if (needsLeadingSpace(ic)) " " else ""
        ic.commitText(lead + best, 1)
        lastGlide = GlideCommit(best, words)
        ui.showCandidates(arrangeBestMiddle(words))
        ui.setComposing(true)
    }

    /** No space at the field start, after whitespace or a newline, or after an opening bracket. */
    private fun needsLeadingSpace(ic: InputConnection): Boolean {
        val before = ic.getTextBeforeCursor(1, 0)
        if (before.isNullOrEmpty()) return false
        val c = before[0]
        return !(c.isWhitespace() || c == '(' || c == '[' || c == '{' || c == '<')
    }

    companion object {
        private const val DOUBLE_SPACE_MS = 600L

        /** Strip order for glide alternatives: runner-up left, best in the middle, third right. */
        fun arrangeBestMiddle(ranked: List<String>): List<String> = when (ranked.size) {
            0 -> emptyList()
            1 -> listOf("", ranked[0], "")
            2 -> listOf(ranked[1], ranked[0], "")
            else -> listOf(ranked[1], ranked[0], ranked[2])
        }

        /**
         * Strip order: the typed word on the left when it differs from every suggestion, the best suggestion
         * in the middle, the runner-up on the right.
         */
        fun arrangeForStrip(typed: String, ranked: List<String>): List<String> {
            if (ranked.isEmpty()) return listOf(typed)
            val typedShown = ranked.none { it.equals(typed, ignoreCase = true) }
            return if (typedShown) listOf(typed, ranked[0], ranked.getOrNull(1) ?: "").filter { it.isNotEmpty() }
            else listOf(ranked.getOrNull(1) ?: "", ranked[0], ranked.getOrNull(2) ?: "").filter { it.isNotEmpty() }
        }
    }
}
