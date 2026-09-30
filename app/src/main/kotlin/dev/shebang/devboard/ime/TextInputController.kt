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
 * Text-mode editing: composing words, suggestions, autocorrect, double-space period, and everything glided.
 *
 * Glided words first wait in a preview row above the keys ("staged") for [STAGING_MS] after the last glide,
 * or until anything else is typed; there a word can be tapped and glided again, or swapped for an
 * alternative, or retyped letter by letter, and a later glide may re-read the staged words when the phrase as a whole reads better. Each
 * glide is decoded with the word before it as context. Once words are in the field they are final: nothing
 * rewrites text the user can already see in the field. Words are learned once they are final: typed words
 * when committed, glided words when they reach the field (the [Learner] decides what it keeps).
 *
 * Everything field-aware goes through [field]; terminals and password fields never compose, never glide
 * and never teach the keyboard anything.
 */
class TextInputController(
    private val connection: () -> InputConnection?,
    private val ui: Ui,
    private val background: Executor,
    private val main: Handler,
    private val learner: Learner = Learner.NONE,
) {
    interface Ui {
        /** Words for the strip, best first; empty clears it. */
        fun showCandidates(words: List<String>)
        fun setComposing(composing: Boolean)

        /**
         * The preview row: staged [words], the [selected] one highlighted (or -1), the words from
         * [previewStart] for [previewCount] drawn as a glide still in progress, and [alternatives] for the
         * selected word. Empty [words] hides the row.
         */
        fun showStaging(words: List<String>, selected: Int, previewStart: Int, previewCount: Int, alternatives: List<String>) = Unit
    }

    /** What the keyboard learns from; implementations apply the user's settings. */
    interface Learner {
        /** A final word, typed or glided, after [previous] (null at a sentence start or after a non-word). */
        fun learnWord(word: String, previous: String?, sentenceStart: Boolean)
        /** Where a kept glide passed its letters ([GlideResult.observations]). */
        fun learnGlide(observations: FloatArray)
        /**
         * A glided word was corrected to [word] (dictionary index of [dictionary], or -1 for a retyped word it
         * lacks), by re-gliding, picking an alternative or retyping it; [stroke] is the original stroke.
         */
        fun correction(stroke: FloatArray?, word: Int, dictionary: Dictionary)

        companion object {
            val NONE = object : Learner {
                override fun learnWord(word: String, previous: String?, sentenceStart: Boolean) = Unit
                override fun learnGlide(observations: FloatArray) = Unit
                override fun correction(stroke: FloatArray?, word: Int, dictionary: Dictionary) = Unit
            }
        }
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

    /** The most recent glide, while it is the last thing typed: backspace removes it, the strip swaps its last word. */
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
     * A glided word with what learning needs: the word before it, whether it began a sentence, where its
     * stroke passed each letter, and the stroke itself (for re-aligning after a correction).
     */
    private class GlidedWord(
        var text: String,
        var word: GlideWord,
        var previous: String?,
        var sentenceStart: Boolean,
        var observations: FloatArray?,
        val stroke: FloatArray?,
    )

    /**
     * The last glide committed straight to the field (preview row off), not yet learned: backspace can still
     * remove it and the strip can still swap its last word. Anything else settles it. Never rewritten.
     */
    private val pending = ArrayList<GlidedWord>()

    /** Glided words not yet in the field, shown in the preview row. */
    private val staged = ArrayList<GlidedWord>()
    private var selected = -1
    private var stagedTrailingSpace = false
    private var stagingDictionary: Dictionary? = null
    /** Letters typed while a staged word is selected: its replacement, shown in its place until confirmed. */
    private val retype = StringBuilder()
    private var retypeCandidates: List<Suggestion> = emptyList()
    private var retypeGeneration = 0
    private val flushRunnable = Runnable { flushStaging() }

    val isComposing: Boolean get() = word.isNotEmpty()
    val hasStaging: Boolean get() = staged.isNotEmpty()

    fun startInput(field: FieldInfo) {
        this.field = field
        resetState()
    }

    private fun resetState() {
        word.setLength(0)
        candidates = emptyList()
        lastGlide = null
        staged.clear()
        selected = -1
        main.removeCallbacks(flushRunnable)
        settle()
        lastActionWasSpace = false
        ui.showCandidates(emptyList())
        ui.showStaging(emptyList(), -1, 0, 0, emptyList())
        ui.setComposing(false)
    }

    /** Cursor moved (by the user or another app): stop composing but leave the text as it is. */
    fun onSelectionChanged(newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        if (isComposing) {
            val insideComposing = newSelStart == newSelEnd && newSelStart == candidatesEnd && candidatesStart >= 0
            // A report can lag our own edits: the preview row goes in and a word starts composing in one go,
            // and the insert's report arrives after. Ask the field whether the word still ends at the cursor.
            if (!insideComposing && !(newSelStart == newSelEnd && composingAtCursor())) {
                connection()?.finishComposingText()
                word.setLength(0)
                clearCandidates()
            }
        } else if (lastGlide != null && newSelStart != newSelEnd) {
            lastGlide = null
            clearCandidates()
        }
    }

    private fun composingAtCursor(): Boolean {
        val ic = connection() ?: return false
        if (ic.getSelectedText(0)?.isNotEmpty() == true) return false
        return ic.getTextBeforeCursor(word.length, 0)?.toString() == word.toString()
    }

    // ---- Typing --------------------------------------------------------------------------------------

    fun typeText(text: String) {
        val ic = connection() ?: return
        if (selected in staged.indices && isRetypeChar(text)) {
            retypeStaged(text)
            return
        }
        flushStaging()
        lastActionWasSpace = false
        settle()
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
        if (isComposing) learnTyped(ic, word.toString())
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
        // Space after retyping a staged word confirms the replacement; the row stays up.
        if (retype.isNotEmpty() && selected in staged.indices) {
            confirmRetype()
            return
        }
        flushStaging()
        lastGlide?.let { lastGlide = null; clearCandidates() }
        settle()
        val now = SystemClock.uptimeMillis()
        if (isComposing) {
            val typed = word.toString()
            var commit = typed
            // Uses the candidates the background thread already produced for this word; nothing is scanned here.
            if (settings.autocorrect) suggester?.autocorrectFrom(typed, candidates)?.let { commit = Suggester.matchCase(typed, it) }
            learnTyped(ic, commit)
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
        if (retype.isNotEmpty() && selected in staged.indices) {
            // Retyping a staged word: delete the last letter typed; with none left the word shows again.
            retype.setLength(retype.length - 1)
            requestRetypeSuggestions()
            renderStaging()
            return
        }
        if (staged.isNotEmpty()) {
            // In the preview row: remove the selected word, or the last one. Nothing reaches the field.
            val i = if (selected >= 0) selected else staged.size - 1
            staged.removeAt(i)
            relink(i)
            selected = -1
            if (staged.isEmpty()) stagedTrailingSpace = false
            scheduleFlush()
            renderStaging()
            return
        }
        val glide = lastGlide
        if (glide != null) {
            // Backspace right after a glide removes everything that glide wrote.
            ic.deleteSurroundingText(glide.text.length, 0)
            repeat(minOf(glide.words, pending.size)) { pending.removeAt(pending.size - 1) }
            lastGlide = null
            clearCandidates()
            return
        }
        settle()
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
        val selectedText = ic.getSelectedText(0)
        if (!selectedText.isNullOrEmpty()) {
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
        flushStaging()
        lastActionWasSpace = false
        lastGlide = null
        settle()
        if (isComposing) learnTyped(ic, word.toString())
        finishComposing(ic)
        clearCandidates()
        when {
            field.enterIsNewline -> ic.commitText("\n", 1)
            field.enterIsKeyEvent -> KeySender.sendPlain(ic, KeyEvent.KEYCODE_ENTER)
            else -> ic.performEditorAction(field.editorAction)
        }
    }

    /** Commits the composing word and any staged glide so a KeyEvent or snippet lands after them. */
    fun finishComposing() {
        val ic = connection() ?: return
        flushStaging()
        if (isComposing) learnTyped(ic, word.toString())
        finishComposing(ic)
        lastGlide = null
        settle()
        clearCandidates()
    }

    private fun finishComposing(ic: InputConnection) {
        if (isComposing) {
            ic.finishComposingText()
            word.setLength(0)
            clearCandidates()
        }
    }

    /** Learns a typed word as it is committed, with the word before it. One InputConnection read. */
    private fun learnTyped(ic: InputConnection, typed: String) {
        if (!field.allowsLearning) return
        val before = ic.getTextBeforeCursor(CONTEXT_CHARS, 0) ?: return
        // The composing word is part of the text before the cursor: the context is what precedes it.
        val rest = if (word.isNotEmpty() && before.endsWith(word)) before.subSequence(0, before.length - word.length) else before
        val prev = GlideText.contextWord(rest)
        learner.learnWord(typed, prev.takeIf { it.isNotEmpty() && it != GlideText.SENTENCE_START }, prev == GlideText.SENTENCE_START)
    }

    // ---- Learning glided words ------------------------------------------------------------------------

    /** The last direct glide can no longer be undone or swapped: learn its words. */
    private fun settle() {
        for (w in pending) retire(w)
        pending.clear()
    }

    /** A glided word is final: learn it, and where its stroke passed its letters. */
    private fun retire(w: GlidedWord) {
        if (!field.allowsLearning) return
        learner.learnWord(w.text, w.previous, w.sentenceStart)
        w.observations?.let { learner.learnGlide(it) }
    }

    // ---- Suggestions ---------------------------------------------------------------------------------

    private fun clearCandidates() {
        candidates = emptyList()
        suggestGeneration++
        ui.showCandidates(emptyList())
        ui.setComposing(isComposing || staged.isNotEmpty())
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
            val last = pending.lastOrNull()
            if (last != null && idx >= 0) {
                val dictionary = stagingDictionary
                if (dictionary != null && glide.alternativeWords[idx] != last.word.word) {
                    learner.correction(last.stroke, glide.alternativeWords[idx], dictionary)
                }
                last.text = chosen
                last.word = last.word.lockedAs(glide.alternativeWords[idx])
                last.observations = null
            }
            return
        }
        if (isComposing) {
            learnTyped(ic, chosen)
            ic.commitText("$chosen ", 1)
            word.setLength(0)
            clearCandidates()
            lastActionWasSpace = true
            lastSpaceTime = SystemClock.uptimeMillis()
        }
    }

    // ---- Glide: context ------------------------------------------------------------------------------

    /**
     * Context for a glide that is starting: the word before it, read from the field or the preview row, so the
     * decoder weighs what is likely to come next (the base word pairs mixed with the user's own) against the
     * stroke. When a staged word is selected, the glide replaces it and its context is the word before it.
     * Otherwise, with [revise], the staged words go along too: they are not in the field yet, so a later glide
     * may re-read them. Words in the field are never passed for revision. One InputConnection read.
     */
    fun glideContext(dictionary: Dictionary, lm: NgramModel, revise: Boolean): GlideContext {
        val ic = connection() ?: return GlideContext(NgramModel.SENTENCE_START)
        val before = ic.getTextBeforeCursor(CONTEXT_CHARS, 0) ?: ""
        val fieldWord = GlideText.contextWord(before)
        if (selected in staged.indices) {
            val prev = if (selected > 0) staged[selected - 1].text.lowercase() else fieldWord
            return GlideContext(GlideText.contextId(prev, dictionary, lm))
        }
        if (!revise || staged.isEmpty()) {
            val last = staged.lastOrNull()?.text?.lowercase() ?: fieldWord
            return GlideContext(GlideText.contextId(last, dictionary, lm))
        }
        // A retyped word the dictionary lacks cannot be re-read: the run starts after it, with unknown context.
        val cut = staged.indexOfLast { it.word.word < 0 }
        if (cut == staged.size - 1) return GlideContext(NgramModel.UNKNOWN)
        val base = if (cut >= 0) NgramModel.UNKNOWN else GlideText.contextId(fieldWord, dictionary, lm)
        return GlideContext(base, staged.subList(cut + 1, staged.size).map { it.word })
    }

    private fun caseNew(word: String, capitalize: Boolean) = if (capitalize) word.replaceFirstChar { it.uppercaseChar() } else word

    // ---- Glide: preview while the finger moves -------------------------------------------------------

    /** Words the strip shows while the finger is still gliding, when the preview row is off. */
    fun previewText(result: GlideResult, dictionary: Dictionary, capitalize: Boolean): String {
        val sb = StringBuilder()
        for ((i, w) in result.words.withIndex()) {
            if (i > 0) sb.append(' ')
            sb.append(caseNew(dictionary.words[w], i == 0 && capitalize))
        }
        return sb.toString()
    }

    /**
     * Shows a glide in progress in the preview row: the staged words (with any re-reading it would make), and
     * its words in place of the selected word or after the others. False when the preview row is off.
     */
    fun previewStaging(result: GlideResult, dictionary: Dictionary, capitalize: Boolean): Boolean {
        if (!settings.glidePreview) return false
        val texts = staged.map { it.text }.toMutableList()
        val sel = selected
        if (sel < 0) {
            // Re-readings the decode would make to staged words (the decoder saw the last of them).
            if (result.firstRevised >= 0 && result.history.size <= staged.size) {
                val off = staged.size - result.history.size
                for (h in result.firstRevised until result.history.size) {
                    texts[off + h] = GlideText.matchCase(texts[off + h], dictionary.words[result.history[h]])
                }
            }
        }
        val words = result.words.mapIndexed { i, w -> caseNew(dictionary.words[w], i == 0 && capitalize && sel < 0) }
        val start: Int
        if (sel in texts.indices) {
            val old = texts.removeAt(sel)
            texts.addAll(sel, words.mapIndexed { i, w -> if (i == 0) GlideText.matchCase(old, w) else w })
            start = sel
        } else {
            start = texts.size
            texts.addAll(words)
        }
        ui.showStaging(texts, -1, start, words.size, emptyList())
        return true
    }

    // ---- Glide: commit -------------------------------------------------------------------------------

    /**
     * Takes a decoded glide. With the preview row on, its words are staged (replacing the selected word, if
     * one is selected: a correction), and staged words the decoder re-read change in the row; otherwise they
     * go straight into the field. Words already in the field are never rewritten.
     */
    fun commitGlide(result: GlideResult, dictionary: Dictionary, capitalize: Boolean, trailingSpace: Boolean) {
        if (result.words.isEmpty()) return
        stagingDictionary = dictionary
        if (settings.glidePreview) stageGlide(result, dictionary, capitalize, trailingSpace) else commitGlideToField(result, dictionary, capitalize, trailingSpace)
    }

    /** The word before the glide's first new word, for learning, and whether that word begins a sentence. */
    private fun firstPrevious(): Pair<String?, Boolean> {
        val prev = staged.lastOrNull()?.text ?: return fieldPrevious()
        return prev.lowercase() to false
    }

    /** The word before the cursor in the field, for learning, and whether the cursor is at a sentence start. */
    private fun fieldPrevious(): Pair<String?, Boolean> {
        val before = connection()?.getTextBeforeCursor(CONTEXT_CHARS, 0) ?: return null to true
        return when (val w = GlideText.contextWord(before)) {
            GlideText.SENTENCE_START -> null to true
            "" -> null to false
            else -> w to false
        }
    }

    private fun newWords(result: GlideResult, dictionary: Dictionary, capitalize: Boolean, previous: Pair<String?, Boolean>): List<GlidedWord> {
        var prev = previous.first
        var sentenceStart = previous.second
        return result.words.mapIndexed { i, w ->
            val text = caseNew(dictionary.words[w], i == 0 && capitalize)
            GlidedWord(text, result.entries[i], prev, sentenceStart, result.observations.getOrNull(i), result.strokes.getOrNull(i)).also {
                prev = text.lowercase()
                sentenceStart = false
            }
        }
    }

    private fun stageGlide(result: GlideResult, dictionary: Dictionary, capitalize: Boolean, trailingSpace: Boolean) {
        finishComposingForGlide()
        lastActionWasSpace = false
        val sel = selected
        if (sel in staged.indices) {
            // A correction: the selected word is replaced by what was glided now.
            val old = staged[sel]
            val prev = if (sel > 0) staged[sel - 1].text.lowercase() to false else fieldPrevious()
            val fresh = newWords(result, dictionary, false, prev)
            if (fresh.first().word.word != old.word.word) learner.correction(old.stroke, fresh.first().word.word, dictionary)
            // Keep the casing the replaced word had (a capital at a sentence start stays).
            fresh.first().text = GlideText.matchCase(old.text, fresh.first().text)
            staged.removeAt(sel)
            staged.addAll(sel, fresh)
            relink(sel + fresh.size)
            selected = -1
            clearRetype()
        } else {
            reviseStaged(result, dictionary)
            staged.addAll(newWords(result, dictionary, capitalize, firstPrevious()))
            stagedTrailingSpace = trailingSpace
        }
        scheduleFlush()
        renderStaging()
    }

    private fun finishComposingForGlide() {
        val ic = connection() ?: return
        if (isComposing) {
            learnTyped(ic, word.toString())
        }
        finishComposing(ic)
    }

    /** Staged words the decoder re-read (it saw the last of them, oldest first) change in the row. */
    private fun reviseStaged(result: GlideResult, dictionary: Dictionary) {
        if (result.firstRevised < 0 || result.history.size > staged.size) return
        val off = staged.size - result.history.size
        for (h in result.firstRevised until result.history.size) {
            val e = staged[off + h]
            val w = result.history[h]
            if (w != e.word.word) {
                e.text = GlideText.matchCase(e.text, dictionary.words[w])
                e.word = e.word.revisedTo(w)
                e.observations = null
            }
        }
    }

    /** Commits a decoded glide straight into the field (preview row off). */
    private fun commitGlideToField(result: GlideResult, dictionary: Dictionary, capitalize: Boolean, trailingSpace: Boolean) {
        val ic = connection() ?: return
        finishComposingForGlide()
        lastActionWasSpace = false
        // The glide before this one is final now.
        settle()
        val fresh = newWords(result, dictionary, capitalize, firstPrevious())
        val ownText = fresh.joinToString(" ") { it.text } + if (trailingSpace) " " else ""
        val sb = StringBuilder()
        if (needsLeadingSpace(ic)) sb.append(' ')
        sb.append(ownText)
        ic.commitText(sb, 1)
        pending.addAll(fresh)
        val alternatives = result.alternatives.map { caseNew(dictionary.words[it], fresh.size == 1 && capitalize) }
        lastGlide = GlideCommit(ownText, fresh.last().text, alternatives, result.alternatives, fresh.size, trailingSpace)
        ui.showCandidates(arrangeBestMiddle(alternatives))
        ui.setComposing(true)
    }

    // ---- Glide: the preview row ----------------------------------------------------------------------

    private fun scheduleFlush() {
        main.removeCallbacks(flushRunnable)
        // While a word is selected for correction, nothing is inserted.
        if (selected < 0 && staged.isNotEmpty()) main.postDelayed(flushRunnable, STAGING_MS)
    }

    private fun stagedAlternatives(e: GlidedWord): List<Int> = e.word.candidates.filter { it != e.word.word }.take(3)

    private fun renderStaging() {
        val dictionary = stagingDictionary
        val retyping = retype.isNotEmpty() && selected in staged.indices
        val alternatives = when {
            retyping -> retypeChips()
            selected in staged.indices && dictionary != null -> {
                val e = staged[selected]
                stagedAlternatives(e).map { GlideText.matchCase(e.text, dictionary.words[it]) }
            }
            else -> emptyList()
        }
        val texts = staged.map { it.text }.toMutableList()
        if (retyping) texts[selected] = retype.toString()
        ui.showStaging(texts, selected, 0, 0, alternatives)
        ui.setComposing(staged.isNotEmpty() || isComposing)
    }

    /**
     * Tapping a staged word selects it (the next glide, or letters typed, replace it); tapping it again
     * deselects it, or confirms the letters typed for it.
     */
    fun selectStaged(index: Int) {
        if (index !in staged.indices) return
        if (retype.isNotEmpty() && selected in staged.indices) {
            val was = selected
            confirmRetype()
            if (index == was) return
        }
        clearRetype()
        selected = if (selected == index) -1 else index
        scheduleFlush()
        renderStaging()
    }

    /** Tapping one of the selected word's alternatives replaces it: a correction. */
    fun pickStagedAlternative(index: Int) {
        val dictionary = stagingDictionary ?: return
        if (selected !in staged.indices) return
        if (retype.isNotEmpty()) {
            retypeChips().getOrNull(index)?.let { replaceStaged(selected, it) }
            return
        }
        val e = staged[selected]
        val w = stagedAlternatives(e).getOrNull(index) ?: return
        learner.correction(e.stroke, w, dictionary)
        e.text = GlideText.matchCase(e.text, dictionary.words[w])
        e.word = e.word.lockedAs(w)
        e.observations = null
        selected = -1
        scheduleFlush()
        renderStaging()
    }

    // ---- Glide: retyping a staged word ---------------------------------------------------------------

    private fun isRetypeChar(text: String): Boolean {
        if (text.length != 1 || !field.allowsComposing) return false
        val c = text[0]
        return c.isLetter() || (c == '\'' && retype.isNotEmpty())
    }

    private fun retypeStaged(text: String) {
        retype.append(text)
        main.removeCallbacks(flushRunnable)
        requestRetypeSuggestions()
        renderStaging()
    }

    private fun requestRetypeSuggestions() {
        val gen = ++retypeGeneration
        retypeCandidates = emptyList()
        val s = suggester ?: return
        val typed = retype.toString()
        if (typed.isEmpty()) return
        background.execute {
            val result = s.suggest(typed, 3)
            main.post {
                if (gen != retypeGeneration) return@post
                retypeCandidates = result
                renderStaging()
            }
        }
    }

    /** Suggestions for the letters typed so far, cased like the word they replace; the typed letters are the word itself. */
    private fun retypeChips(): List<String> {
        val typed = retype.toString()
        return retypeCandidates.map { Suggester.matchCase(typed, it.word) }.filter { it != typed }.take(3)
    }

    private fun clearRetype() {
        retype.setLength(0)
        retypeCandidates = emptyList()
        retypeGeneration++
    }

    /** Space or a second tap: the letters typed replace the selected word (autocorrected like typing). */
    private fun confirmRetype() {
        if (retype.isEmpty() || selected !in staged.indices) {
            clearRetype()
            return
        }
        var typed = retype.toString()
        if (settings.autocorrect) suggester?.autocorrectFrom(typed, retypeCandidates)?.let { typed = Suggester.matchCase(typed, it) }
        replaceStaged(selected, typed)
    }

    /** Replaces staged word [i] with a typed word: a correction, settled so later glides never re-read it. */
    private fun replaceStaged(i: Int, typed: String) {
        val old = staged[i]
        val text = if (old.text.firstOrNull()?.isUpperCase() == true && typed.first().isLowerCase()) typed.replaceFirstChar { it.uppercaseChar() } else typed
        val dictionary = stagingDictionary
        val idx = dictionary?.indexOfLower(text.lowercase()) ?: -1
        if (dictionary != null && idx != old.word.word) learner.correction(old.stroke, idx, dictionary)
        val word = GlideWord(idx, intArrayOf(idx), floatArrayOf(0f), locked = true)
        staged[i] = GlidedWord(text, word, old.previous, old.sentenceStart, null, old.stroke)
        relink(i + 1)
        clearRetype()
        selected = -1
        scheduleFlush()
        renderStaging()
    }

    /** The word before staged word [i] changed (replaced or removed): learn it after the word now before it. */
    private fun relink(i: Int) {
        if (i !in staged.indices) return
        val (prev, start) = if (i == 0) fieldPrevious() else staged[i - 1].text.lowercase() to false
        staged[i].previous = prev
        staged[i].sentenceStart = start
    }

    /** Glided words currently in the preview row, for tests and diagnostics. */
    val stagedWords: List<String> get() = staged.map { it.text }

    /**
     * Puts the staged words into the field, where they are final and are learned. Runs when the preview time
     * is up and before anything else is typed.
     */
    fun flushStaging() {
        main.removeCallbacks(flushRunnable)
        // Letters typed for a selected word count: they replace it before the row goes into the field.
        if (retype.isNotEmpty()) confirmRetype()
        clearRetype()
        if (staged.isEmpty()) return
        val ic = connection()
        if (ic != null) {
            if (isComposing) learnTyped(ic, word.toString())
            finishComposing(ic)
            settle()
            val sb = StringBuilder()
            if (needsLeadingSpace(ic)) sb.append(' ')
            sb.append(staged.joinToString(" ") { it.text })
            if (stagedTrailingSpace) sb.append(' ')
            ic.commitText(sb, 1)
            for (w in staged) retire(w)
        }
        staged.clear()
        selected = -1
        stagedTrailingSpace = false
        lastGlide = null
        ui.showStaging(emptyList(), -1, 0, 0, emptyList())
        ui.showCandidates(emptyList())
        ui.setComposing(isComposing)
    }

    /** The language was rebuilt: dictionary indices changed, so staged and pending words are settled. */
    fun onLanguageChanged() {
        flushStaging()
        settle()
        lastGlide = null
    }

    /** No space at the field start, after whitespace or a newline, or after an opening bracket. */
    private fun needsLeadingSpace(ic: InputConnection): Boolean {
        val before = ic.getTextBeforeCursor(1, 0)
        if (before.isNullOrEmpty()) return false
        return !GlideText.atWordStart(before, 1)
    }

    companion object {
        private const val DOUBLE_SPACE_MS = 600L
        /** Glided words wait this long in the preview row after the last glide. */
        const val STAGING_MS = 2000L
        /** Characters read before the cursor for the word before it. */
        private const val CONTEXT_CHARS = 64

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
