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
 * Glides and typing go straight into the field. To redo a word, the user taps it: when the user (not the
 * keyboard) puts the cursor inside a word, or selects one word (double tap), that word is the target. It is
 * underlined, the strip shows it with its alternatives, and the next glide or a tapped alternative replaces
 * it, keeping its capitals; that counts as a correction. Space while a word is targeted moves past it so the
 * next glide adds a word instead. A cursor at a word's edge (where a tap between words lands) targets
 * nothing, so a glide there adds a word, with spaces around it as needed. Typing letters is ordinary typing.
 * After the keyboard's own edits nothing is targeted, so gliding on never replaces anything by surprise.
 * Nothing else in the field is rewritten.
 *
 * Words are learned once they are final: typed words when committed, a glide when the next thing happens
 * (backspace right after it, or a swap from the strip, happen first). Everything field-aware goes through
 * [field]; terminals and password fields never compose, never glide and never teach the keyboard anything.
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
    }

    /** What the keyboard learns from; implementations apply the user's settings. */
    interface Learner {
        /** A final word, typed or glided, after [previous] (null at a sentence start or after a non-word). */
        fun learnWord(word: String, previous: String?, sentenceStart: Boolean)
        /** Where a kept glide passed its letters ([GlideResult.observations]). */
        fun learnGlide(observations: FloatArray)
        /**
         * A word was corrected to [word] (dictionary index of [dictionary]) by gliding over it or picking an
         * alternative; [stroke] is its own earlier stroke when it was glided recently, for re-aligning.
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

    /** Milliseconds since boot; replaceable in tests. */
    var clock: () -> Long = { SystemClock.uptimeMillis() }

    private val word = StringBuilder(32)
    private var candidates: List<Suggestion> = emptyList()
    private var suggestGeneration = 0
    private var lastSpaceTime = 0L
    private var lastActionWasSpace = false

    /** The most recent glide, while it is the last thing typed: backspace removes it, the strip swaps its last word. */
    private class GlideCommit(
        /** The glide's own text as inserted, without the leading space, with any space after it. */
        var text: String,
        var lastWord: String,
        val alternatives: List<String>,
        val alternativeWords: IntArray,
        val words: Int,
        /** Text after the last word that belongs to the glide (a space), which a strip swap keeps. */
        val after: String,
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

    /** The last glide, not yet learned: backspace can still remove it and the strip can still swap its last word. */
    private val pending = ArrayList<GlidedWord>()

    /** Words glided in this field lately, newest last: a tapped word found here has its runners-up and stroke. */
    private val recent = ArrayDeque<GlidedWord>()
    private var dictionaryInUse: Dictionary? = null

    /**
     * The word the user pointed at. [at] is the cursor (or selection start) where it was found, -1 when not
     * known; [before] of its letters lie before the cursor and [after] after it, or it is the selection
     * ([selection]). [glided] is the same word from [recent], when there is one.
     */
    private class Target(
        val text: String,
        val at: Int,
        val before: Int,
        val after: Int,
        val selection: Boolean,
        val glided: GlidedWord?,
        val underlined: Boolean,
    )
    private var target: Target? = null
    private var targetGeneration = 0

    /** When the keyboard last changed the field: selection reports soon after are its own, not the user's. */
    private var lastOwnEdit = Long.MIN_VALUE / 2

    val isComposing: Boolean get() = word.isNotEmpty()

    /** The targeted word (a glide or a strip pick will replace it), for tests and diagnostics. */
    val targetText: String? get() = target?.text

    fun startInput(field: FieldInfo) {
        this.field = field
        resetState()
    }

    private fun resetState() {
        word.setLength(0)
        candidates = emptyList()
        lastGlide = null
        target = null
        recent.clear()
        settle()
        lastActionWasSpace = false
        ui.showCandidates(emptyList())
        ui.setComposing(false)
    }

    private fun ownEdit() {
        lastOwnEdit = clock()
    }

    /**
     * The selection changed. Reports right after the keyboard's own edits are its own. Anything else is the
     * user (or the app) moving the cursor: composing stops, and a word the cursor is now inside, or a single
     * selected word, becomes the target. The last glide stays unlearned until the next edit, which may redo it.
     */
    fun onSelectionChanged(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        if (isComposing) {
            val insideComposing = newSelStart == newSelEnd && newSelStart == candidatesEnd && candidatesStart >= 0
            // A report can lag the keyboard's own edits: ask the field whether the word still ends at the cursor.
            if (!insideComposing && !(newSelStart == newSelEnd && composingAtCursor())) {
                connection()?.finishComposingText()
                word.setLength(0)
                clearCandidates()
            }
        }
        if (clock() - lastOwnEdit < OWN_EDIT_MS) return
        if (newSelStart == oldSelStart && newSelEnd == oldSelEnd) return
        if (lastGlide != null) {
            lastGlide = null
            clearCandidates()
        }
        // The last glide is not settled yet: the user may be pointing at it to redo it.
        dropTarget()
        findTarget(newSelStart, newSelEnd)
    }

    private fun composingAtCursor(): Boolean {
        val ic = connection() ?: return false
        if (ic.getSelectedText(0)?.isNotEmpty() == true) return false
        return ic.getTextBeforeCursor(word.length, 0)?.toString() == word.toString()
    }

    // ---- The targeted word ---------------------------------------------------------------------------

    private fun findTarget(selStart: Int, selEnd: Int) {
        if (!field.allowsComposing || isComposing) return
        val ic = connection() ?: return
        if (selEnd > selStart) {
            val sel = ic.getSelectedText(0)?.toString() ?: return
            if (sel.isEmpty() || !sel.all { isLetterInWord(it) } || !sel.first().isLetter()) return
            setTarget(Target(sel, selStart, 0, 0, selection = true, glided = recentMatch(sel), underlined = false))
            return
        }
        val before = ic.getTextBeforeCursor(MAX_WORD, 0) ?: return
        val after = ic.getTextAfterCursor(MAX_WORD, 0) ?: ""
        var b = 0
        while (b < before.length && isLetterInWord(before[before.length - 1 - b])) b++
        var a = 0
        while (a < after.length && isLetterInWord(after[a])) a++
        // Only a cursor inside a word targets it: at a word's edge (where a tap between words lands) the user
        // may be adding a word, so nothing is targeted.
        if (b == 0 || a == 0) return
        val text = before.substring(before.length - b) + after.substring(0, a)
        if (!text.first().isLetter()) return
        val underlined = selStart >= 0 && ic.setComposingRegion(selStart - b, selStart + a)
        if (underlined) ownEdit()
        setTarget(Target(text, selStart, b, a, selection = false, glided = recentMatch(text), underlined = underlined))
    }

    private fun isLetterInWord(c: Char) = c.isLetter() || c == '\'' || c == '’'

    private fun recentMatch(text: String): GlidedWord? = recent.lastOrNull { it.text.equals(text, ignoreCase = true) }

    /** Shows the target in the middle of the strip with its alternatives: its own runners-up if it was glided, else suggestions. */
    private fun setTarget(t: Target) {
        target = t
        val gen = ++targetGeneration
        val dictionary = dictionaryInUse
        val glided = t.glided
        if (glided != null && dictionary != null) {
            val alts = glided.word.candidates.filter { it >= 0 && it != glided.word.word }.take(2).map { GlideText.matchCase(t.text, dictionary.words[it]) }
            showTarget(t, alts)
            return
        }
        showTarget(t, emptyList())
        val s = suggester ?: return
        background.execute {
            val found = s.suggest(t.text.lowercase(), 3).map { Suggester.matchCase(t.text, it.word) }.filter { !it.equals(t.text, ignoreCase = true) }.take(2)
            main.post { if (gen == targetGeneration && target === t) showTarget(t, found) }
        }
    }

    private fun showTarget(t: Target, alternatives: List<String>) {
        ui.showCandidates(arrangeBestMiddle(listOf(t.text) + alternatives))
        // The strip shows words (not the terminal bar) while a word is targeted.
        ui.setComposing(true)
    }

    /** The target is no longer wanted: remove its underline (the text stays as it is). */
    private fun dropTarget() {
        val t = target ?: return
        target = null
        targetGeneration++
        if (t.underlined) {
            connection()?.finishComposingText()
            ownEdit()
        }
        ui.showCandidates(emptyList())
        ui.setComposing(isComposing)
    }

    /** Replaces the target with [replacement] (its capitals kept); true when it was still there to replace. */
    private fun replaceTarget(t: Target, replacement: String): Boolean {
        val ic = connection() ?: return false
        ownEdit()
        target = null
        targetGeneration++
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
            ic.commitText(GlideText.matchCase(t.text, replacement), 1)
        }
        ic.endBatchEdit()
        return ok
    }

    /** Text before the target's first letter (or before the cursor when nothing is targeted), for context. */
    private fun textBeforeTarget(ic: InputConnection): CharSequence {
        val t = target
        val before = ic.getTextBeforeCursor(CONTEXT_CHARS + (t?.before ?: 0), 0) ?: ""
        return if (t != null && !t.selection) before.subSequence(0, maxOf(0, before.length - t.before)) else before
    }

    // ---- Typing --------------------------------------------------------------------------------------

    fun typeText(text: String) {
        val ic = connection() ?: return
        dropTarget()
        ownEdit()
        lastActionWasSpace = false
        val glideBefore = lastGlide
        settle()
        lastGlide = null
        if (glideBefore != null) clearCandidates()
        if (field.allowsComposing && isWordChar(text)) {
            // A letter right after a glide starts a new word, as a glide right after typing does.
            if (glideBefore != null && glideBefore.after.isEmpty() && word.isEmpty()) ic.commitText(" ", 1)
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
        val t = target
        if (t != null) {
            skipPastTarget(ic, t)
            return
        }
        ownEdit()
        lastGlide?.let { lastGlide = null; clearCandidates() }
        settle()
        val now = clock()
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

    /**
     * Space with a word targeted: the cursor goes after the word (and after the space behind it, adding one
     * if there is none), so the next glide adds a word there instead of replacing this one.
     */
    private fun skipPastTarget(ic: InputConnection, t: Target) {
        dropTarget()
        ownEdit()
        val toEnd = if (t.selection) t.text.length else t.after
        val base = t.at
        val after = ic.getTextAfterCursor(t.after + 1, 0)?.toString() ?: ""
        val spaceFollows = if (t.selection) {
            val beyond = ic.getTextAfterCursor(1, 0)?.toString() ?: ""
            beyond.startsWith(" ")
        } else {
            after.length > t.after && after[t.after] == ' '
        }
        val dest = toEnd + if (spaceFollows) 1 else 0
        if (base >= 0) {
            ic.setSelection(base + dest, base + dest)
        } else {
            repeat(dest) { KeySender.sendPlain(ic, KeyEvent.KEYCODE_DPAD_RIGHT) }
        }
        if (!spaceFollows) ic.commitText(" ", 1)
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
        dropTarget()
        ownEdit()
        lastActionWasSpace = false
        val glide = lastGlide
        if (glide != null) {
            // Backspace right after a glide removes everything that glide wrote, unlearned.
            ic.deleteSurroundingText(glide.text.length, 0)
            repeat(minOf(glide.words, pending.size)) {
                val w = pending.removeAt(pending.size - 1)
                recent.remove(w)
            }
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
        dropTarget()
        ownEdit()
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

    /** Commits the composing word so a KeyEvent or snippet lands after it; drops the target. */
    fun finishComposing() {
        val ic = connection() ?: return
        dropTarget()
        ownEdit()
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

    /** The last glide can no longer be undone or swapped: learn its words. */
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

    /** Picks a strip word: replaces the targeted word, the composing word, or the last glide's last word. */
    fun pickCandidate(chosen: String) {
        val ic = connection() ?: return
        val t = target
        if (t != null) {
            if (chosen.equals(t.text, ignoreCase = true)) {
                dropTarget()
                return
            }
            val dictionary = dictionaryInUse
            val prev = previousOf(textBeforeTarget(ic))
            // A word being redone is not learned as it was; the rest of the last glide is final now.
            t.glided?.let { pending.remove(it) }
            settle()
            if (replaceTarget(t, chosen)) {
                val idx = dictionary?.indexOfLower(chosen.lowercase()) ?: -1
                if (dictionary != null && idx >= 0) learner.correction(t.glided?.stroke, idx, dictionary)
                t.glided?.let { recent.remove(it) }
                if (field.allowsLearning) learner.learnWord(GlideText.matchCase(t.text, chosen), prev.first, prev.second)
            }
            clearCandidates()
            return
        }
        ownEdit()
        val glide = lastGlide
        if (glide != null) {
            val tail = glide.lastWord + glide.after
            val replacement = chosen + glide.after
            ic.beginBatchEdit()
            ic.deleteSurroundingText(tail.length, 0)
            ic.commitText(replacement, 1)
            ic.endBatchEdit()
            glide.text = glide.text.dropLast(tail.length) + replacement
            glide.lastWord = chosen
            // A word picked by hand is a correction; it is learned as picked, and its stroke not trusted.
            val idx = glide.alternatives.indexOf(chosen)
            val last = pending.lastOrNull()
            if (last != null && idx >= 0) {
                val dictionary = dictionaryInUse
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
            lastSpaceTime = clock()
        }
    }

    // ---- Glide ---------------------------------------------------------------------------------------

    /**
     * Context for a glide that is starting: the word before it (before the targeted word, when one is
     * targeted), so the decoder weighs what is likely to come next, the base word pairs mixed with the user's
     * own, against the stroke. One InputConnection read.
     */
    fun glideContext(dictionary: Dictionary, lm: NgramModel): GlideContext {
        val ic = connection() ?: return GlideContext(NgramModel.SENTENCE_START)
        return GlideContext(GlideText.contextId(GlideText.contextWord(textBeforeTarget(ic)), dictionary, lm))
    }

    private fun caseNew(word: String, capitalize: Boolean) = if (capitalize) word.replaceFirstChar { it.uppercaseChar() } else word

    /** Words the strip shows while the finger is still gliding. */
    fun previewText(result: GlideResult, dictionary: Dictionary, capitalize: Boolean): String {
        val t = target
        val sb = StringBuilder()
        for ((i, w) in result.words.withIndex()) {
            if (i > 0) sb.append(' ')
            val text = dictionary.words[w]
            sb.append(if (i == 0 && t != null) GlideText.matchCase(t.text, text) else caseNew(text, i == 0 && capitalize))
        }
        return sb.toString()
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

    private fun previousOf(before: CharSequence): Pair<String?, Boolean> = when (val w = GlideText.contextWord(before)) {
        GlideText.SENTENCE_START -> null to true
        "" -> null to false
        else -> w to false
    }

    /**
     * Takes a decoded glide: it replaces the targeted word (a correction), or goes in at the cursor with a
     * space before it after a word, and a space after it before one.
     */
    fun commitGlide(result: GlideResult, dictionary: Dictionary, capitalize: Boolean, trailingSpace: Boolean) {
        if (result.words.isEmpty()) return
        val ic = connection() ?: return
        dictionaryInUse = dictionary
        if (isComposing) learnTyped(ic, word.toString())
        finishComposing(ic)
        lastActionWasSpace = false
        // A word being redone is not learned as it was; the rest of the glide before this one is final now.
        target?.glided?.let { pending.remove(it) }
        settle()
        ownEdit()
        val t = target
        if (t != null) {
            val fresh = newWords(result, dictionary, false, previousOf(textBeforeTarget(ic)))
            fresh.first().text = GlideText.matchCase(t.text, fresh.first().text)
            val text = fresh.joinToString(" ") { it.text }
            if (replaceTarget(t, text)) {
                val first = fresh.first()
                if (!first.text.equals(t.text, ignoreCase = true)) learner.correction(t.glided?.stroke, first.word.word, dictionary)
                t.glided?.let { recent.remove(it) }
                remember(fresh)
                pending.addAll(fresh)
                val alternatives = result.alternatives.map { GlideText.matchCase(t.text, dictionary.words[it]) }
                lastGlide = GlideCommit(text, fresh.last().text, alternatives, result.alternatives, fresh.size, "")
                ui.showCandidates(arrangeBestMiddle(alternatives))
                ui.setComposing(true)
                return
            }
        }
        val fresh = newWords(result, dictionary, capitalize, previousOf(ic.getTextBeforeCursor(CONTEXT_CHARS, 0) ?: ""))
        // Gliding in front of a word keeps them apart; a dip into the space bar adds one anyway.
        val after = if (trailingSpace || needsTrailingSpace(ic)) " " else ""
        val ownText = fresh.joinToString(" ") { it.text } + after
        val sb = StringBuilder()
        if (needsLeadingSpace(ic)) sb.append(' ')
        sb.append(ownText)
        ic.commitText(sb, 1)
        remember(fresh)
        pending.addAll(fresh)
        val alternatives = result.alternatives.map { caseNew(dictionary.words[it], fresh.size == 1 && capitalize) }
        lastGlide = GlideCommit(ownText, fresh.last().text, alternatives, result.alternatives, fresh.size, after)
        ui.showCandidates(arrangeBestMiddle(alternatives))
        ui.setComposing(true)
    }

    private fun remember(words: List<GlidedWord>) {
        for (w in words) {
            recent.addLast(w)
            if (recent.size > MAX_RECENT) recent.removeFirst()
        }
    }

    /** The language was rebuilt: dictionary indices changed, so remembered glides and the target go. */
    fun onLanguageChanged() {
        dropTarget()
        settle()
        lastGlide = null
        recent.clear()
    }

    /** No space at the field start, after whitespace or a newline, or after an opening bracket. */
    private fun needsLeadingSpace(ic: InputConnection): Boolean {
        val before = ic.getTextBeforeCursor(1, 0)
        if (before.isNullOrEmpty()) return false
        return !GlideText.atWordStart(before, 1)
    }

    /** A space after the glide when a word follows the cursor directly. */
    private fun needsTrailingSpace(ic: InputConnection): Boolean {
        val after = ic.getTextAfterCursor(1, 0)
        return !after.isNullOrEmpty() && after[0].isLetterOrDigit()
    }

    companion object {
        private const val DOUBLE_SPACE_MS = 600L
        /** Selection reports this soon after the keyboard's own edit are taken as its own. */
        const val OWN_EDIT_MS = 600L
        /** Characters read before the cursor for the word before it. */
        private const val CONTEXT_CHARS = 64
        /** Longest word looked at around the cursor. */
        private const val MAX_WORD = 48
        /** Glided words remembered for redoing. */
        private const val MAX_RECENT = 32

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
