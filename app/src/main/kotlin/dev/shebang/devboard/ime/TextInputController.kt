package dev.shebang.devboard.ime

import android.os.Handler
import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import dev.shebang.devboard.dict.Dictionary
import dev.shebang.devboard.dict.NgramModel
import dev.shebang.devboard.dict.Suggester
import dev.shebang.devboard.dict.Suggestion
import dev.shebang.devboard.glide.GlideContext
import dev.shebang.devboard.glide.GlideResult
import dev.shebang.devboard.glide.GlideWord
import dev.shebang.devboard.input.KeySender
import dev.shebang.devboard.settings.Settings
import java.util.concurrent.Executor

/**
 * Text-mode editing: composing words, suggestions, autocorrect, smart spacing after a glide, double-space
 * period, backspace-after-glide, and rewriting recent glided words when a later glide makes another reading
 * more likely. Everything field-aware goes through [field]; terminals and password fields never compose,
 * never glide and never keep typed text.
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

    /** The most recent glide, while it is still the last thing typed: backspace removes it, the strip swaps its last word. */
    private class GlideCommit(
        /** The glide's own text as inserted, without the leading space, with the trailing space if any. */
        var text: String,
        var lastWord: String,
        val alternatives: List<String>,
        val alternativeWords: IntArray,
        val words: Int,
        val trailingSpace: Boolean,
    )
    private var lastGlide: GlideCommit? = null

    /**
     * Recent glided words as they stand right before the cursor, oldest first, separated by single spaces
     * (plus one trailing space when the user tapped space). A new glide may rewrite them; anything else typed,
     * or a cursor move, ends the run.
     */
    private class HistoryEntry(var text: String, var word: GlideWord)
    private val history = ArrayList<HistoryEntry>()
    private var historyTrailingSpace = false

    val isComposing: Boolean get() = word.isNotEmpty()

    fun startInput(field: FieldInfo) {
        this.field = field
        resetState()
    }

    private fun resetState() {
        word.setLength(0)
        candidates = emptyList()
        lastGlide = null
        clearHistory()
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
        // Typed text ends the run of revisable glided words.
        clearHistory()
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
        // One space after glided words keeps them revisable; a second (or a double-space period) ends the run.
        if (history.isNotEmpty()) {
            if (historyTrailingSpace || isComposing) clearHistory() else historyTrailingSpace = true
        }
        val now = SystemClock.uptimeMillis()
        if (isComposing) {
            val typed = word.toString()
            var commit = typed
            // Uses the candidates the background thread already produced for this word; nothing is scanned here.
            if (settings.autocorrect) suggester?.autocorrectFrom(typed, candidates)?.let { commit = Suggester.matchCase(typed, it) }
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
            // Backspace right after a glide removes everything that glide wrote.
            ic.deleteSurroundingText(glide.text.length, 0)
            repeat(minOf(glide.words, history.size)) { history.removeAt(history.size - 1) }
            historyTrailingSpace = false
            lastGlide = null
            clearCandidates()
            return
        }
        clearHistory()
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
        clearHistory()
        finishComposing(ic)
        clearCandidates()
        when {
            field.enterIsNewline -> ic.commitText("\n", 1)
            field.enterIsKeyEvent -> KeySender.sendPlain(ic, KeyEvent.KEYCODE_ENTER)
            else -> ic.performEditorAction(field.editorAction)
        }
    }

    /** Commits the composing word (if any) so a KeyEvent or snippet lands after it. Ends the glide run. */
    fun finishComposing() {
        val ic = connection() ?: return
        finishComposing(ic)
        lastGlide = null
        clearHistory()
        clearCandidates()
    }

    private fun clearHistory() {
        history.clear()
        historyTrailingSpace = false
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

    /** Picks a strip candidate: replaces the composing word, or swaps the glide's last word. */
    fun pickCandidate(chosen: String) {
        val ic = connection() ?: return
        val glide = lastGlide
        if (glide != null) {
            val tail = glide.lastWord + if (glide.trailingSpace) " " else ""
            val replacement = chosen + if (glide.trailingSpace) " " else ""
            ic.beginBatchEdit()
            ic.deleteSurroundingText(tail.length, 0)
            ic.commitText(replacement, 1)
            ic.endBatchEdit()
            glide.text = glide.text.dropLast(tail.length) + replacement
            glide.lastWord = chosen
            // A word picked by hand is settled: later glides use it as context but never rewrite it.
            val idx = glide.alternatives.indexOf(chosen)
            val last = history.lastOrNull()
            if (last != null && idx >= 0) {
                last.text = chosen
                last.word = last.word.lockedAs(glide.alternativeWords[idx])
            }
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

    /**
     * Context for a glide that is starting: the recent glided words, when they still stand right before the
     * cursor and [revise] is on, and the word before them. One InputConnection read.
     */
    fun glideContext(dictionary: Dictionary, lm: NgramModel, revise: Boolean): GlideContext {
        val ic = connection() ?: return GlideContext(NgramModel.SENTENCE_START)
        val before = ic.getTextBeforeCursor(CONTEXT_CHARS, 0) ?: ""
        if (revise && history.isNotEmpty() && !isComposing) {
            val run = historyText(0)
            val start = before.length - run.length
            if (start >= 0 && before.endsWith(run) && GlideText.atWordStart(before, start)) {
                val anchor = GlideText.contextWord(before.subSequence(0, start))
                return GlideContext(GlideText.contextId(anchor, dictionary, lm), history.map { it.word })
            }
        }
        // The run was broken (typing elsewhere, a cursor move) or revision is off.
        clearHistory()
        return GlideContext(GlideText.contextId(GlideText.contextWord(before), dictionary, lm))
    }

    /** Text of the history from entry [from] on, as it stands in the field. */
    private fun historyText(from: Int): String {
        val sb = StringBuilder()
        for (i in from until history.size) {
            if (i > from) sb.append(' ')
            sb.append(history[i].text)
        }
        if (historyTrailingSpace && from < history.size) sb.append(' ')
        return sb.toString()
    }

    /** Words the strip shows while the finger is still gliding: any rewritten recent words, then the new ones. */
    fun previewText(result: GlideResult, dictionary: Dictionary, capitalize: Boolean): String {
        val sb = StringBuilder()
        if (result.firstRevised >= 0 && result.history.size <= history.size) {
            val off = history.size - result.history.size
            for (h in result.firstRevised until result.history.size) {
                sb.append(GlideText.matchCase(history[off + h].text, dictionary.words[result.history[h]])).append(' ')
            }
        }
        for ((i, w) in result.words.withIndex()) {
            if (i > 0) sb.append(' ')
            sb.append(caseNew(dictionary.words[w], i == 0 && capitalize))
        }
        return sb.toString()
    }

    private fun caseNew(word: String, capitalize: Boolean) = if (capitalize) word.replaceFirstChar { it.uppercaseChar() } else word

    /**
     * Commits a decoded glide: rewrites recent glided words the decoder revised (only if they still stand as
     * written), then inserts the new words with a smart leading space and, after a dip into the space bar,
     * a trailing one.
     */
    fun commitGlide(result: GlideResult, dictionary: Dictionary, capitalize: Boolean, trailingSpace: Boolean) {
        val ic = connection() ?: return
        if (result.words.isEmpty()) return
        finishComposing(ic)
        lastActionWasSpace = false

        // Rewrite recent words, if the decoder changed any and they are still exactly what was written.
        var rewriteFrom = -1
        var oldTail = ""
        if (result.firstRevised >= 0 && result.history.size <= history.size) {
            val off = history.size - result.history.size
            val from = off + result.firstRevised
            oldTail = historyText(from)
            val before = ic.getTextBeforeCursor(oldTail.length, 0)
            if (before != null && before.toString() == oldTail) {
                for (h in result.firstRevised until result.history.size) {
                    val e = history[off + h]
                    val w = result.history[h]
                    if (w != e.word.word) {
                        e.text = GlideText.matchCase(e.text, dictionary.words[w])
                        e.word = e.word.revisedTo(w)
                    }
                }
                rewriteFrom = from
            }
        }

        val newWords = result.words.mapIndexed { i, w -> caseNew(dictionary.words[w], i == 0 && capitalize) }
        val ownText = newWords.joinToString(" ") + if (trailingSpace) " " else ""
        val sb = StringBuilder()
        ic.beginBatchEdit()
        if (rewriteFrom >= 0) {
            ic.deleteSurroundingText(oldTail.length, 0)
            for (i in rewriteFrom until history.size) sb.append(history[i].text).append(' ')
        } else if (needsLeadingSpace(ic)) {
            sb.append(' ')
        }
        sb.append(ownText)
        ic.commitText(sb, 1)
        ic.endBatchEdit()

        for ((i, entry) in result.entries.withIndex()) history.add(HistoryEntry(newWords[i], entry))
        while (history.size > MAX_HISTORY_KEPT) history.removeAt(0)
        historyTrailingSpace = trailingSpace

        val alternatives = result.alternatives.map { caseNew(dictionary.words[it], newWords.size == 1 && capitalize) }
        lastGlide = GlideCommit(ownText, newWords.last(), alternatives, result.alternatives, newWords.size, trailingSpace)
        ui.showCandidates(arrangeBestMiddle(alternatives))
        ui.setComposing(true)
    }

    /** No space at the field start, after whitespace or a newline, or after an opening bracket. */
    private fun needsLeadingSpace(ic: InputConnection): Boolean {
        val before = ic.getTextBeforeCursor(1, 0)
        if (before.isNullOrEmpty()) return false
        return !GlideText.atWordStart(before, 1)
    }

    companion object {
        private const val DOUBLE_SPACE_MS = 600L
        /** Characters read before the cursor for glide context: the recent run plus the word before it. */
        private const val CONTEXT_CHARS = 200
        /** Glided words remembered; the decoder revises the last few and uses the one before as context. */
        private const val MAX_HISTORY_KEPT = 8

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
