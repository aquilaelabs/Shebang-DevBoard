package dev.shebang.devboard.ime

import android.os.Handler
import android.os.SystemClock
import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import dev.shebang.devboard.dict.Dictionary
import dev.shebang.devboard.dict.LetterPrior
import dev.shebang.devboard.dict.NgramModel
import dev.shebang.devboard.dict.SlipCost
import dev.shebang.devboard.dict.Suggester
import dev.shebang.devboard.dict.Suggestion
import dev.shebang.devboard.glide.GlideContext
import dev.shebang.devboard.glide.GlideResult
import dev.shebang.devboard.glide.GlideWord
import dev.shebang.devboard.glide.TapModel
import dev.shebang.devboard.input.KeySender
import dev.shebang.devboard.layout.FieldVariant
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
    /** Runs work on the main thread; the Handler by default, replaceable where no Looper runs (tests). */
    private val postToMain: (Runnable) -> Unit = { main.post(it) },
) {
    interface Ui {
        /** Words for the strip, best first; empty clears it. */
        fun showCandidates(words: List<String>)
        fun setComposing(composing: Boolean)
        /**
         * Space will autocorrect [typed] to [fix]: the strip offers [typed] with a check mark to keep it, the
         * correction marked as the one that will go in, and [other].
         */
        fun showCorrection(typed: String, fix: String, other: String?) = showCandidates(listOfNotNull(typed, fix, other))
        /** How likely each letter a..z is to be typed next ([LetterPrior]), or null for no opinion. */
        fun setLetterPrior(prior: FloatArray?) = Unit
        /** Whether a letter tap just above the space bar may be taken as a space (plain text fields only). */
        fun setSpaceFromLetters(on: Boolean) = Unit
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
        /** Where a word typed right was tapped: triples (letter, du, dv) from [TapModel.observation]. */
        fun learnTaps(observations: FloatArray) = Unit

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
    /** The keyboard shows code mode: brackets and quotes pair (when the setting is on). */
    var codeMode = false

    /**
     * How well a glide's stroke fits the keys of any letters (mean distance in key pitches, lower is better),
     * for offering identifiers from the text; set by the service for the current key layout.
     */
    var identifierScorer: ((stroke: FloatArray, letters: String) -> Float?)? = null

    /** The dictionary and model for next-word suggestions; set by the service when the language is ready. */
    var predictionModel: Pair<Dictionary, NgramModel>? = null

    /** The next-word model for the strip's predictions, this controller's own copy (used on [background]). */
    var nextWordModel: dev.shebang.devboard.dict.NextWordModel? = null
    /** Next words the strip offers now (after a space); empty when it offers something else. */
    private var predictions: List<String> = emptyList()
    private var predictGeneration = 0

    /** Code-like identifiers in the text around the cursor, most used first, and when they were read. */
    private var identifiers: List<String> = emptyList()
    private var identifiersReadAt = Long.MIN_VALUE / 2

    /** Words glided one after another, while nothing else has been done; and the joins offered for them. */
    private var glideRun: List<String> = emptyList()
    private class JoinOffer(val runText: String, val camel: String, val snake: String, val glide: Any)
    private var joinOffer: JoinOffer? = null

    /**
     * The glided word the glide now starting may fix: the last word of the glide just before, still exactly
     * as it went in right before the cursor ([tail] is it with the space after it). Set by [glideContext].
     */
    private class Revisable(val word: GlidedWord, val tail: String)

    /** Where this user's taps land on the keys, so slips are weighed by where each letter's tap came down. */
    var tapModel: TapModel? = null
    /** Touch-down points of the composing word's letters (NaN for a letter not tapped), in step with [word]. */
    private val tapXs = ArrayList<Float>()
    private val tapYs = ArrayList<Float>()

    private fun resetTaps(unknown: Int) {
        tapXs.clear()
        tapYs.clear()
        repeat(unknown) {
            tapXs += Float.NaN
            tapYs += Float.NaN
        }
    }

    private var priorGeneration = 0

    /**
     * Works out, in the background, how likely each letter is next given the word typed so far and the words
     * before, for the keyboard to weigh taps between keys. Clears the last odds at once, so a tap never uses
     * odds for another prefix. Only where dictionary words are typed: not in code mode, nor in password,
     * email, URL, number or terminal fields.
     */
    fun refreshLetterPrior() {
        val gen = ++priorGeneration
        ui.setLetterPrior(null)
        val plainText = !codeMode && field.allowsComposing && field.variant == FieldVariant.PLAIN
        ui.setSpaceFromLetters(plainText)
        if (!plainText) return
        val (dictionary, lm) = predictionModel ?: return
        val ic = connection() ?: return
        if (!ic.getSelectedText(0).isNullOrEmpty()) return
        val prefix = if (isComposing) word.toString().lowercase() else ""
        if (prefix.any { it !in 'a'..'z' }) return
        // Mid-word in text the keyboard is not composing (after a tap inside a word): no opinion.
        if (!isComposing) {
            val before = ic.getTextBeforeCursor(1, 0)
            if (!before.isNullOrEmpty() && isLetterInWord(before[0])) return
        }
        val context = currentContext(ic) ?: return
        val users = suggester
        background.execute {
            val prior = LetterPrior(dictionary) { i ->
                kotlin.math.exp(-(Suggester.CONTEXT_WEIGHT * lm.cost3(i, context.context2, context.context1) + (1 - Suggester.CONTEXT_WEIGHT) * lm.unigramCost(i)).toDouble())
            }.next(prefix)
            postToMain { if (gen == priorGeneration && users === suggester) ui.setLetterPrior(prior) }
        }
    }

    /**
     * Writes a piece of dictation at the cursor: the word being typed is ended first, and a space goes before
     * it after a word, as for a glide. Whisper's own capitals and punctuation are kept.
     */
    fun insertDictation(text: String) {
        val ic = connection() ?: return
        val t = text.trim()
        if (t.isEmpty()) return
        if (isComposing) endWord(ic, "", correct = false, deferOk = false)
        settle()
        lastGlide = null
        dropTarget()
        clearCandidates()
        ownEdit()
        val written = if (needsLeadingSpace(ic)) " $t" else t
        ic.commitText(written, 1)
        lastDictation = written
        lastActionWasSpace = false
    }

    /** The last piece of dictation as written, for "scratch that" to take back. */
    private var lastDictation: String? = null

    /** Takes back the last piece of dictation, if it still stands right before the cursor. */
    fun dropLastDictation() {
        val ic = connection() ?: return
        val last = lastDictation ?: return
        if (ic.getTextBeforeCursor(last.length, 0)?.toString() != last) return
        ownEdit()
        ic.deleteSurroundingText(last.length, 0)
        lastDictation = null
    }

    /** Whether [typed] begins a code-like identifier in the text around ("max" of "maxRetries"): not a slip. */
    private fun startsAnIdentifier(typed: String): Boolean =
        typed.isNotEmpty() && identifiers.any { it.length > typed.length && it.startsWith(typed, ignoreCase = true) }

    /** The two words before the composing word, for ranking its candidates; null without a language model. */
    private fun currentContext(ic: InputConnection): Suggester.Context? {
        val (dictionary, lm) = predictionModel ?: return null
        val n = word.length
        val text = ic.getTextBeforeCursor(CONTEXT_CHARS + n, 0) ?: return null
        if (text.length < n) return null
        val before = text.subSequence(0, text.length - n)
        val w1 = GlideText.contextWord(before)
        val c1 = GlideText.contextId(w1, dictionary, lm)
        val w2 = GlideText.contextWord2(before)
        val c2 = if (w1 == GlideText.SENTENCE_START || w2.isEmpty()) NgramModel.UNKNOWN else GlideText.contextId(w2, dictionary, lm)
        return Suggester.Context(c2, c1)
    }

    /** The composing word's taps for [SlipCost], or null when they are not all in step with it. */
    private fun currentTaps(): SlipCost.Taps? {
        val m = tapModel ?: return null
        if (tapXs.size != word.length || tapXs.all { it.isNaN() }) return null
        val xs = tapXs.toFloatArray()
        val ys = tapYs.toFloatArray()
        return SlipCost.Taps { i, a, b -> if (i >= xs.size || xs[i].isNaN()) null else m.cost(xs[i], ys[i], a, b) }
    }

    /** Where each letter of [typed] was tapped relative to where this user aims, for learning; null when unknown. */
    private fun tapObservations(typed: String): FloatArray? {
        val m = tapModel ?: return null
        if (tapXs.size != typed.length) return null
        val out = ArrayList<Float>()
        for ((i, c) in typed.lowercase().withIndex()) {
            if (tapXs[i].isNaN()) continue
            m.observation(tapXs[i], tapYs[i], c)?.let { o -> o.forEach { out += it } }
        }
        return if (out.isEmpty()) null else out.toFloatArray()
    }

    private var revisable: Revisable? = null

    /** Milliseconds since boot; replaceable in tests. */
    var clock: () -> Long = { SystemClock.uptimeMillis() }

    private val word = StringBuilder(32)
    private var candidates: List<Suggestion> = emptyList()
    /** The typed word [candidates] were computed for: autocorrect trusts them only for that word. */
    private var candidatesFor = ""

    /** The last word autocorrect changed, while it is the last thing typed: backspace puts back [typed]. */
    private class Autocorrected(val typed: String, val corrected: String, val after: String)
    private var lastAutocorrect: Autocorrected? = null
    /** Words the user took back from autocorrect in this field (lowercase): typed again, they stay as typed. */
    private val keptAsTyped = HashSet<String>()

    /**
     * A word autocorrect changed, found again by the text before it: [upTo] is the text up to and including
     * [corrected] as it stood after the correction ([atStart]: all of the text before it, which was short).
     */
    private class Correction(val typed: String, val corrected: String, val upTo: String, val atStart: Boolean)
    /** The latest corrections in this field, oldest first: backspace back to one offers what was typed. */
    private val corrections = ArrayDeque<Correction>()
    /** The correction backspace walked back to, while it is the reopened word: picking [Correction.typed] keeps it. */
    private var reopenedCorrection: Correction? = null

    /**
     * The word backspace walked back into and reopened as the composing word, while it stands unchanged: a
     * glide or a strip pick replaces it (a correction), space leaves it as it was. [reopenedGlide] is the same
     * word from the glides remembered, with its runners-up and stroke.
     */
    private var reopened: String? = null
    private var reopenedGlide: GlidedWord? = null
    private val reopenedUnchanged: Boolean get() = reopened != null && word.toString() == reopened
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

    /**
     * Glided words that are final but not learned yet, newest last: the last [HELD_WORDS]. A wrong glide is
     * often noticed a few words later and fixed by backing up to it; one changed or replaced while held is
     * dropped unlearned ([abandon]), so the mistake does not teach its word, its pairs or its stroke. The rest
     * are learned as newer ones push them out, and all of them when the field changes.
     */
    private val held = ArrayDeque<GlidedWord>()

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
    ) {
        /** Whether the word begins a sentence, set when the target is found. */
        var sentenceStart = false
    }
    private var target: Target? = null
    private var targetGeneration = 0

    /** The selection as the field last reported it (-1 before any report). */
    private var selStart = -1
    private var selEnd = -1

    /** A swipe from backspace in progress: the cursor it started at, and where each word before it begins. */
    private var deleteAnchor = -1
    private var deleteBefore = ""
    private var deleteOffsets = IntArray(0)
    private var deletePreview = ""

    /** When the keyboard last changed the field: selection reports soon after are its own, not the user's. */
    private var lastOwnEdit = Long.MIN_VALUE / 2

    val isComposing: Boolean get() = word.isNotEmpty()

    /** The targeted word (a glide or a strip pick will replace it), for tests and diagnostics. */
    val targetText: String? get() = target?.text

    fun startInput(field: FieldInfo) {
        // What was glided in the last field is learned under that field's rules.
        settle()
        flushHeld()
        this.field = field
        resetState()
    }

    private fun resetState() {
        word.setLength(0)
        candidates = emptyList()
        candidatesFor = ""
        lastAutocorrect = null
        keptAsTyped.clear()
        corrections.clear()
        reopenedCorrection = null
        reopened = null
        reopenedGlide = null
        identifiers = emptyList()
        identifiersReadAt = Long.MIN_VALUE / 2
        glideRun = emptyList()
        joinOffer = null
        lastGlide = null
        target = null
        recent.clear()
        settle()
        flushHeld()
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
        selStart = newSelStart
        selEnd = newSelEnd
        if (deleteAnchor >= 0) return
        if (isComposing) {
            val insideComposing = newSelStart == newSelEnd && newSelStart == candidatesEnd && candidatesStart >= 0
            // A report can lag the keyboard's own edits: ask the field whether the word still ends at the cursor.
            if (!insideComposing && !(newSelStart == newSelEnd && composingAtCursor())) {
                connection()?.finishComposingText()
                word.setLength(0)
                reopened = null
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
        connection()?.let { t.sentenceStart = GlideText.contextWord(textBeforeTarget(it)) == GlideText.SENTENCE_START }
        val gen = ++targetGeneration
        val dictionary = dictionaryInUse
        val glided = t.glided
        if (glided != null && dictionary != null) {
            val alts = glided.word.candidates.filter { it >= 0 && it != glided.word.word }.take(2).map { caseFor(t, dictionary.words[it]) }
            showTarget(t, alts)
            return
        }
        showTarget(t, emptyList())
        val s = suggester ?: return
        background.execute {
            val found = s.suggest(t.text.lowercase(), 3).map { caseFor(t, it.word) }.filter { !it.equals(t.text, ignoreCase = true) }.take(2)
            postToMain { if (gen == targetGeneration && target === t) showTarget(t, found) }
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
    private fun caseFor(t: Target, word: String): String {
        val old = t.text
        if (old.isEmpty() || word.isEmpty()) return word
        val dictionary = dictionaryInUse
        val oldForm = dictionary?.indexOfLower(old.lowercase())?.takeIf { it >= 0 }?.let { dictionary.words[it] }
        val allCaps = old.length > 1 && old.all { !it.isLetter() || it.isUpperCase() }
        if (allCaps && (oldForm == null || !oldForm.all { !it.isLetter() || it.isUpperCase() })) return word.uppercase()
        if (!old[0].isUpperCase()) return word
        val ownCapital = oldForm != null && oldForm[0].isUpperCase()
        return if (!ownCapital || t.sentenceStart) word.replaceFirstChar { it.uppercaseChar() } else word
    }

    /** Text before the target's first letter (or before the cursor when nothing is targeted), for context. */
    private fun textBeforeTarget(ic: InputConnection): CharSequence {
        val t = target
        val before = ic.getTextBeforeCursor(CONTEXT_CHARS + (t?.before ?: 0), 0) ?: ""
        return if (t != null && !t.selection) before.subSequence(0, maxOf(0, before.length - t.before)) else before
    }

    // ---- Typing --------------------------------------------------------------------------------------

    fun typeText(text: String, tapX: Float = Float.NaN, tapY: Float = Float.NaN) {
        val ic = connection() ?: return
        dropTarget()
        ownEdit()
        lastAutocorrect = null
        lastActionWasSpace = false
        val glideBefore = lastGlide
        settle()
        lastGlide = null
        if (glideBefore != null || predictions.isNotEmpty()) clearCandidates()
        if (field.allowsComposing && isWordChar(text)) {
            // A letter right after a glide starts a new word, as a glide right after typing does (when the glide
            // still stands right before the cursor and nothing is selected).
            if (glideBefore != null && glideBefore.after.isEmpty() && word.isEmpty() &&
                ic.getSelectedText(0).isNullOrEmpty() &&
                ic.getTextBeforeCursor(glideBefore.text.length, 0)?.toString() == glideBefore.text
            ) {
                ic.commitText(" ", 1)
            }
            if (word.isEmpty()) {
                refreshIdentifiers(ic)
                resetTaps(0)
                // Typing on at the end of a word that is not composing (the field dropped it, or the cursor was
                // put there): the whole word is composed, so the underline and a strip pick cover all of it.
                recomposeWordBeforeCursor(ic)?.let { text -> recentMatch(text)?.let { abandon(it) } }
                reopenedGlide = null
            }
            word.append(text)
            reopenedGlide?.let { g -> if (!reopenedUnchanged) { abandon(g); reopenedGlide = null } }
            for (k in text.indices) {
                tapXs += if (text.length == 1) tapX else Float.NaN
                tapYs += if (text.length == 1) tapY else Float.NaN
            }
            ic.setComposingText(word, 1)
            ui.setComposing(true)
            requestSuggestions()
            return
        }
        if (isComposing) {
            if (pairing && text.length == 1 && (text[0] in PAIRS || text[0] in CLOSERS)) {
                // In code mode a bracket or quote ends the word, then pairs.
                endWord(ic, "", correct = false, deferOk = false)
                if (!typePaired(ic, text[0])) ic.commitText(text, 1)
                return
            }
            // Sentence punctuation ends a word as space does; anything else (a digit, a symbol) just follows it.
            endWord(ic, text, correct = text.length == 1 && text[0] in SENTENCE_PUNCTUATION, deferOk = true)
            return
        }
        if (pairing && text.length == 1 && typePaired(ic, text[0])) return
        ic.commitText(text, 1)
    }

    private val pairing: Boolean get() = codeMode && settings.pairBrackets && !this.field.isTerminal

    /**
     * Code mode's pairs: an opening bracket brings its closing one with the cursor between; a closing
     * bracket or quote typed just before the same character steps over it; a quote pairs when it starts
     * something (not after a letter or digit, where it is an apostrophe or closes a string). True when done.
     */
    private fun typePaired(ic: InputConnection, c: Char): Boolean {
        val next = ic.getTextAfterCursor(1, 0)?.firstOrNull()
        val close = PAIRS[c]
        if (c in CLOSERS || c in QUOTES) {
            if (next == c) {
                // Step over the closing character already there.
                ic.beginBatchEdit()
                ic.deleteSurroundingText(0, 1)
                ic.commitText(c.toString(), 1)
                ic.endBatchEdit()
                return true
            }
            if (c in CLOSERS) return false
        }
        if (c in QUOTES) {
            val prev = ic.getTextBeforeCursor(1, 0)?.firstOrNull()
            if (prev != null && (prev.isLetterOrDigit() || prev == '_')) return false
            // Before a word the quote is an opening one without a partner.
            if (next != null && (next.isLetterOrDigit() || next == '_')) return false
        }
        val closing = close ?: return false
        ic.beginBatchEdit()
        ic.commitText(c.toString(), 1)
        ic.commitText(closing.toString(), 0)
        ic.endBatchEdit()
        return true
    }

    private fun isWordChar(text: String): Boolean {
        if (text.length != 1) return false
        val c = text[0]
        // Apostrophes and underscores join letters into one word ("don't", "max_retries").
        return c.isLetter() || ((c == '\'' || c == '_') && word.isNotEmpty())
    }

    fun space() {
        val ic = connection() ?: return
        val t = target
        if (t != null) {
            // A selected word: space moves past it (typing a space would replace it).
            if (t.selection) {
                skipPastTarget(ic, t)
                return
            }
            // A cursor inside a word: the space goes in right there, splitting it ("twowords" -> "two words").
            // Holding space moves past the word instead ([spaceHeld]).
            dropTarget()
            ownEdit()
            lastAutocorrect = null
            ic.commitText(" ", 1)
            lastActionWasSpace = true
            lastSpaceTime = clock()
            return
        }
        ownEdit()
        lastAutocorrect = null
        lastGlide?.let { lastGlide = null; clearCandidates() }
        settle()
        val now = clock()
        if (isComposing) {
            endWord(ic, " ", correct = true, deferOk = true)
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
        showPredictions(ic)
    }

    /**
     * After a space: the strip offers the three words most likely to come next, given the two before the
     * cursor (the corpus model mixed with the user's own word pairs), capitalised at a sentence start.
     */
    private fun showPredictions(ic: InputConnection) {
        // Not in code mode, where the next word is rarely English.
        if (!settings.nextWord || codeMode || !field.allowsComposing || isComposing || target != null) return
        val model = predictionModel ?: return
        val before = ic.getTextBeforeCursor(CONTEXT_CHARS, 0) ?: return
        val w1 = GlideText.contextWord(before)
        val w2 = GlideText.contextWord2(before)
        val sentence = sentenceBefore(ic)
        val nextWord = nextWordModel
        val gen = ++predictGeneration
        background.execute {
            val (dictionary, lm) = model
            val c1 = GlideText.contextId(w1, dictionary, lm)
            val c2 = if (w2.isEmpty()) NgramModel.UNKNOWN else GlideText.contextId(w2, dictionary, lm)
            val start = w1 == GlideText.SENTENCE_START
            val words = dev.shebang.devboard.dict.WordPredictions.predict(dictionary, lm, nextWord, sentence, c2, c1, 3)
                .map { dictionary.words[it] }.map { if (start) it.replaceFirstChar { c -> c.uppercaseChar() } else it }
            postToMain {
                if (gen != predictGeneration || isComposing || words.isEmpty()) return@postToMain
                predictions = words
                ui.showCandidates(arrangeBestMiddle(words))
                ui.setComposing(true)
            }
        }
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

    /**
     * Space held down: with a word pointed at, the cursor moves past it (and a space follows), so a word can be
     * added after it. False when no word is pointed at, so the hold does what it does elsewhere.
     */
    fun spaceHeld(): Boolean {
        val ic = connection() ?: return false
        val t = target ?: return false
        skipPastTarget(ic, t)
        return true
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
        val ac = lastAutocorrect
        lastAutocorrect = null
        if (ac != null && !isComposing && ic.getTextBeforeCursor(ac.corrected.length + ac.after.length, 0)?.toString() == ac.corrected + ac.after) {
            // Right after an autocorrect: put back what was typed (without the space), and leave it be from now on.
            ic.beginBatchEdit()
            ic.deleteSurroundingText(ac.corrected.length + ac.after.length, 0)
            ic.commitText(ac.typed, 1)
            ic.endBatchEdit()
            keptAsTyped += ac.typed.lowercase()
            corrections.removeAll { it.typed == ac.typed && it.corrected == ac.corrected }
            return
        }
        val glide = lastGlide
        lastGlide = null
        // Backspace right after a glide removes everything that glide wrote, unlearned: when it still stands
        // right before the cursor (a quick tap elsewhere can outrun the cursor report).
        if (glide != null && ic.getTextBeforeCursor(glide.text.length, 0)?.toString() == glide.text) {
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
            // Backing up into a glided word and taking letters off it: it was wrong.
            reopenedGlide?.let { abandon(it) }
            reopenedGlide = null
            if (tapXs.size == word.length) {
                tapXs.removeAt(tapXs.size - 1)
                tapYs.removeAt(tapYs.size - 1)
            }
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
        if (pairing) {
            // Between an empty pair, backspace takes both.
            val prev = ic.getTextBeforeCursor(1, 0)?.firstOrNull()
            val next = ic.getTextAfterCursor(1, 0)?.firstOrNull()
            if (prev != null && next != null && PAIRS[prev] == next && ic.getSelectedText(0).isNullOrEmpty()) {
                ic.deleteSurroundingText(1, 1)
                return
            }
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
        reopenWordBeforeCursor(ic)
    }

    /**
     * Backspace walked back to the end of a word (it removed the space or punctuation after it): the word
     * becomes the composing word again, and the strip offers what else it could be (its runners-up if it was
     * glided lately, suggestions for it otherwise). Typing goes on with it, backspace deletes its letters, and
     * a glide or a strip pick replaces it.
     */
    private fun reopenWordBeforeCursor(ic: InputConnection) {
        val text = recomposeWordBeforeCursor(ic) ?: return
        val c = correctionEndingAtCursor(ic, text)
        reopenedCorrection = c
        if (c != null) {
            // Back to a word autocorrect changed: it stays, and what was typed is first on the strip.
            reopenedGlide = null
            ui.setComposing(true)
            candidates = emptyList()
            candidatesFor = ""
            suggestGeneration++
            ui.showCandidates(listOf(c.typed, text, ""))
            return
        }
        reopenedGlide = recentMatch(text)
        ui.setComposing(true)
        val g = reopenedGlide
        val dictionary = dictionaryInUse
        if (g != null && dictionary != null) {
            val t = reopenedTarget(ic)
            val alts = g.word.candidates.filter { it >= 0 && it != g.word.word }.take(2).map { caseFor(t, dictionary.words[it]) }
            candidates = emptyList()
            candidatesFor = ""
            suggestGeneration++
            ui.showCandidates(arrangeBestMiddle(listOf(text) + alts))
        } else {
            requestSuggestions()
        }
    }

    /** Remembers that autocorrect wrote [corrected] for [typed], with [trailing] characters after it before the cursor. */
    private fun rememberCorrection(ic: InputConnection, typed: String, corrected: String, trailing: Int) {
        val n = CORRECTION_CONTEXT + corrected.length + trailing
        val before = ic.getTextBeforeCursor(n, 0)?.toString() ?: return
        val upTo = before.dropLast(trailing)
        if (before.length < trailing || !upTo.endsWith(corrected)) return
        corrections.addLast(Correction(typed, corrected, upTo, atStart = before.length < n))
        if (corrections.size > MAX_CORRECTIONS) corrections.removeFirst()
    }

    /** The remembered correction that [text], the word just before the cursor, still is, in the same place. */
    private fun correctionEndingAtCursor(ic: InputConnection, text: String): Correction? {
        for (c in corrections.asReversed()) {
            if (c.corrected != text) continue
            val before = ic.getTextBeforeCursor(c.upTo.length + 1, 0)?.toString() ?: return null
            if (if (c.atStart) before == c.upTo else before.length > c.upTo.length && before.endsWith(c.upTo)) return c
        }
        return null
    }

    /**
     * Makes the word ending at the cursor the composing word again and returns it, or null when there is none:
     * a letter or digit follows the cursor, nothing is selected, the word is glued to digits or symbols before
     * it ("x86"), or it does not start with a letter.
     */
    private fun recomposeWordBeforeCursor(ic: InputConnection): String? {
        if (!field.allowsComposing || isComposing) return null
        if (!ic.getSelectedText(0).isNullOrEmpty()) return null
        val after = ic.getTextAfterCursor(1, 0)
        if (!after.isNullOrEmpty() && (after[0].isLetterOrDigit() || isLetterInWord(after[0]))) return null
        val before = ic.getTextBeforeCursor(MAX_WORD, 0) ?: return null
        var b = 0
        while (b < before.length && isLetterInWord(before[before.length - 1 - b])) b++
        if (b == 0 || b == MAX_WORD) return null
        if (b < before.length && before[before.length - 1 - b].isLetterOrDigit()) return null
        val text = before.substring(before.length - b)
        if (!text.first().isLetter()) return null
        ownEdit()
        ic.beginBatchEdit()
        ic.deleteSurroundingText(b, 0)
        ic.setComposingText(text, 1)
        ic.endBatchEdit()
        word.setLength(0)
        word.append(text)
        resetTaps(text.length)
        reopened = text
        return text
    }

    /** The reopened composing word as a target, so a glide replaces it the way it replaces a tapped word. */
    private fun reopenedTarget(ic: InputConnection): Target {
        val text = word.toString()
        val t = Target(text, -1, text.length, 0, selection = false, glided = reopenedGlide, underlined = true)
        val before = ic.getTextBeforeCursor(CONTEXT_CHARS + text.length, 0) ?: ""
        t.sentenceStart = GlideText.contextWord(before.subSequence(0, maxOf(0, before.length - text.length))) == GlideText.SENTENCE_START
        return t
    }

    /**
     * Swiping left from backspace: the last [n] words before the cursor (each with the spaces after it) are
     * selected to show what will go; 0 selects nothing. The first call reads the text before the cursor once.
     */
    fun previewDeleteWords(n: Int) {
        val ic = connection() ?: return
        if (deleteAnchor < 0) {
            dropTarget()
            if (isComposing) endWord(ic, "", correct = false, deferOk = false)
            lastGlide = null
            lastAutocorrect = null
            // The last glide stays unsettled: its words may be about to go ([deleteWords]).
            if (selEnd < 0 || selStart != selEnd) return
            deleteAnchor = selEnd
            deleteBefore = ic.getTextBeforeCursor(DELETE_CHARS, 0)?.toString() ?: ""
            deleteOffsets = wordOffsets(deleteBefore)
        }
        val k = n.coerceIn(0, deleteOffsets.size - 1)
        val len = deleteOffsets[k]
        ownEdit()
        ic.setSelection(deleteAnchor - len, deleteAnchor)
        deletePreview = deleteBefore.takeLast(len)
    }

    /**
     * The swipe from backspace ended: the [n] words go (the previewed selection, when it still is exactly
     * that; otherwise the words before the cursor), or with 0 the cursor is put back.
     */
    fun deleteWords(n: Int) {
        val ic = connection() ?: return
        val anchor = deleteAnchor
        deleteAnchor = -1
        ownEdit()
        // Whatever the strip offered was for text that is going.
        clearCandidates()
        if (n <= 0) {
            if (anchor >= 0) ic.setSelection(anchor, anchor)
            return
        }
        val sel = ic.getSelectedText(0)?.toString()
        if (anchor >= 0 && sel != null && sel.isNotEmpty()) {
            if (sel == deletePreview) {
                ic.commitText("", 1)
                forgetDeletedGlide(n)
            } else {
                ic.setSelection(anchor, anchor)
            }
            return
        }
        // No preview (the cursor position was not known): delete the words before the cursor.
        if (isComposing) endWord(ic, "", correct = false, deferOk = false)
        val before = ic.getTextBeforeCursor(DELETE_CHARS, 0)?.toString() ?: return
        val offs = wordOffsets(before)
        ic.deleteSurroundingText(offs[n.coerceIn(0, offs.size - 1)], 0)
        forgetDeletedGlide(n)
    }

    /**
     * [n] words before the cursor were swiped away. The last glide's words are the last ones written, so as
     * many of them go unlearned, as with backspace right after a glide: a wrong glide deleted this way must not
     * teach its word or its stroke. The rest of that glide is final.
     */
    private fun forgetDeletedGlide(n: Int) {
        val fromPending = minOf(n, pending.size)
        repeat(fromPending) {
            val w = pending.removeAt(pending.size - 1)
            recent.remove(w)
        }
        // Words before the last glide that went too were glided before it, if they were glided at all.
        if (pending.isEmpty()) repeat(minOf(n - fromPending, held.size)) { abandon(held.last()) }
        settle()
    }

    /** offsets[k]: how many characters before the cursor the last k words take, each with the spaces after it. */
    private fun wordOffsets(before: String): IntArray {
        val out = ArrayList<Int>()
        out += 0
        var i = before.length
        while (i > 0 && out.size <= MAX_DELETE_WORDS) {
            while (i > 0 && before[i - 1].isWhitespace() && before[i - 1] != '\n') i--
            if (i > 0 && before[i - 1] == '\n') {
                // A line break is a stop of its own.
                i--
            } else if (i > 0 && isDeleteWordChar(before[i - 1])) {
                while (i > 0 && isDeleteWordChar(before[i - 1])) i--
            } else {
                while (i > 0 && !before[i - 1].isWhitespace() && !isDeleteWordChar(before[i - 1])) i--
            }
            if (before.length - i == out.last()) break
            out += before.length - i
        }
        return out.toIntArray()
    }

    private fun isDeleteWordChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '\'' || c == '’'

    fun enter() {
        val ic = connection() ?: return
        dropTarget()
        ownEdit()
        lastActionWasSpace = false
        lastAutocorrect = null
        lastGlide = null
        settle()
        if (isComposing) endWord(ic, "", correct = true, deferOk = false)
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
        lastAutocorrect = null
        if (isComposing) endWord(ic, "", correct = false, deferOk = false)
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

    /**
     * Ends the word being typed, followed by [after] (a space, punctuation, or nothing). With [correct],
     * autocorrect (when on) applies; the pronoun I is capitalised when auto-capitalisation is on; the word is
     * learned as it ends up. When the strip's suggestions are not for this word yet (a quick space), with
     * [deferOk] the correction is worked out in the background and applied if the word and what follows still
     * stand as typed.
     */
    private fun endWord(ic: InputConnection, after: String, correct: Boolean, deferOk: Boolean) {
        val typed = word.toString()
        val taps = currentTaps()
        val before = currentContext(ic)
        val tapsSeen = tapObservations(typed)
        val context = learningContext(ic)
        // A word backspace reopened and left as it was stays as it was, and is not learned twice.
        val untouched = reopenedUnchanged
        reopened = null
        val canCorrect = correct && !untouched && settings.autocorrect && field.allowsComposing &&
            typed.lowercase() !in keptAsTyped && !looksLikeCode(typed) && !startsAnIdentifier(typed) && !gluedToPrevious(ic, typed) &&
            !capitalisedOnPurpose(ic, typed)
        var commit = typed
        var defer = false
        if (canCorrect) {
            if (candidatesFor == typed) suggester?.autocorrectFrom(typed, candidates, taps, before)?.takeIf { keepsPunctuation(typed, it) }?.let { commit = it }
            else defer = deferOk && suggester != null
            // A word that stands elsewhere in the text as typed is a name the user means (an identifier,
            // a handle), not a slip.
            if ((commit != typed || defer) && appearsInText(ic, typed)) {
                commit = typed
                defer = false
            }
        }
        commit = pronounCase(commit)
        ic.beginBatchEdit()
        ic.commitText(commit, 1)
        if (after.isNotEmpty()) ic.commitText(after, 1)
        ic.endBatchEdit()
        word.setLength(0)
        clearCandidates()
        if (commit != pronounCase(typed)) {
            lastAutocorrect = Autocorrected(typed, commit, after)
            rememberCorrection(ic, typed, commit, after.length)
        }
        if (!defer) {
            if (!untouched) learnAs(commit, context)
            // A word the dictionary knows, typed and kept as it is, shows where this user's taps land.
            if (!untouched && commit == typed && tapsSeen != null && field.allowsLearning && suggester?.knows(typed) == true) {
                learner.learnTaps(tapsSeen)
            }
            return
        }
        val s = suggester ?: return
        background.execute {
            val fix = s.autocorrect(typed, taps, before)?.takeIf { keepsPunctuation(typed, it) }
            postToMain {
                val ic2 = connection()
                val late = fix?.let { pronounCase(it) }
                // The next word may already be under way: the fix goes in front of it, which stays composing.
                val next = if (isComposing) word.toString() else ""
                val expected = typed + after + next
                if (late != null && ic2 != null && ic2.getSelectedText(0).isNullOrEmpty() &&
                    ic2.getTextBeforeCursor(expected.length, 0)?.toString() == expected
                ) {
                    ownEdit()
                    ic2.beginBatchEdit()
                    if (next.isNotEmpty()) ic2.finishComposingText()
                    ic2.deleteSurroundingText(expected.length, 0)
                    ic2.commitText(late + after, 1)
                    if (next.isNotEmpty()) ic2.setComposingText(next, 1)
                    ic2.endBatchEdit()
                    // Backspace's undo of a correction only applies while it is the last thing typed.
                    if (next.isEmpty()) lastAutocorrect = Autocorrected(typed, late, after)
                    rememberCorrection(ic2, typed, late, after.length + next.length)
                    learnAs(late, context)
                } else {
                    learnAs(commit, context)
                }
            }
        }
    }

    /**
     * Words autocorrect leaves alone because they look like code: a capital after the first letter
     * ("getUser", but not "NASA" style capitals throughout, which are left alone anyway), a digit or an
     * underscore.
     */
    /**
     * Whether autocorrect may turn [typed] into [fix] as far as punctuation goes. An apostrophe typed into a word
     * is on purpose: the letters stay, and only the apostrophe may move ("ca'nt" -> "can't", but "y'all" stays).
     */
    private fun keepsPunctuation(typed: String, fix: String): Boolean {
        if (typed.none { it == '\'' || it == '’' }) return true
        fun letters(w: String) = w.filter { it != '\'' && it != '’' }.lowercase()
        return letters(typed) == letters(fix)
    }

    /**
     * Whether the word being typed is joined to what comes before by punctuation, with no space ("f-droid",
     * "node.js", "and/or", "user@host"): part of a name or an address, which autocorrect leaves alone.
     */
    /**
     * A word the dictionary lacks, typed in capitals ("NASA", "GPU"), or with a capital in the middle of a
     * sentence where the keyboard would not give one ("Kaito"): a name or an acronym meant as typed.
     */
    private fun capitalisedOnPurpose(ic: InputConnection, typed: String): Boolean {
        if (typed.isEmpty() || !typed[0].isUpperCase()) return false
        if (suggester?.knows(typed) != false) return false
        if (typed.length >= 2 && typed.all { !it.isLetter() || it.isUpperCase() }) return true
        val before = ic.getTextBeforeCursor(CONTEXT_CHARS, 0) ?: return false
        val rest = if (before.endsWith(typed)) before.subSequence(0, before.length - typed.length) else before
        return GlideText.contextWord(rest) != GlideText.SENTENCE_START
    }

    private fun gluedToPrevious(ic: InputConnection, typed: String): Boolean {
        val before = ic.getTextBeforeCursor(typed.length + 2, 0) ?: return false
        if (before.length < typed.length + 2) return false
        val joiner = before[before.length - typed.length - 1]
        val prev = before[before.length - typed.length - 2]
        return joiner in JOINERS && !prev.isWhitespace()
    }

    private fun looksLikeCode(w: String): Boolean {
        if (w.any { it.isDigit() || it == '_' }) return true
        val inner = w.drop(1)
        return inner.any { it.isUpperCase() } && inner.any { it.isLowerCase() }
    }

    /** Whether [w] stands as a whole word elsewhere in the text around the cursor (one read each side). */
    private fun appearsInText(ic: InputConnection, w: String): Boolean {
        val before = ic.getTextBeforeCursor(AROUND_CHARS, 0)?.toString() ?: ""
        val after = ic.getTextAfterCursor(AROUND_CHARS, 0)?.toString() ?: ""
        // The word being typed is the end of [before]: look at what precedes it.
        val rest = if (before.endsWith(w)) before.dropLast(w.length) else before
        return containsWord(rest, w) || containsWord(after, w)
    }

    private fun containsWord(text: String, w: String): Boolean {
        var i = text.indexOf(w)
        while (i >= 0) {
            val beforeOk = i == 0 || !(text[i - 1].isLetterOrDigit() || text[i - 1] == '_')
            val end = i + w.length
            val afterOk = end == text.length || !(text[end].isLetterOrDigit() || text[end] == '_')
            if (beforeOk && afterOk) return true
            i = text.indexOf(w, i + 1)
        }
        return false
    }

    /** "i", "i'm", "i'd", "i'll" and "i've" with a capital, when auto-capitalisation is on. */
    private fun pronounCase(w: String): String {
        if (!settings.autoCaps || !field.allowsAutoCaps) return w
        return if (w.lowercase().replace('’', '\'') in PRONOUN_I) w.replaceFirstChar { it.uppercaseChar() } else w
    }

    /** The word before the composing word (lowercase, or null) and whether it begins a sentence. */
    private fun learningContext(ic: InputConnection): Pair<String?, Boolean> {
        val before = ic.getTextBeforeCursor(CONTEXT_CHARS, 0) ?: return null to false
        val rest = if (word.isNotEmpty() && before.endsWith(word)) before.subSequence(0, before.length - word.length) else before
        val prev = GlideText.contextWord(rest)
        return prev.takeIf { it.isNotEmpty() && it != GlideText.SENTENCE_START } to (prev == GlideText.SENTENCE_START)
    }

    private fun learnAs(w: String, context: Pair<String?, Boolean>) {
        if (field.allowsLearning) learner.learnWord(w, context.first, context.second)
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

    /** The last glide can no longer be undone or swapped: its words are held, then learned ([held]). */
    private fun settle() {
        for (w in pending) {
            held.addLast(w)
            while (held.size > HELD_WORDS) retire(held.removeFirst())
        }
        pending.clear()
    }

    private fun flushHeld() {
        while (held.isNotEmpty()) retire(held.removeFirst())
    }

    /** A glided word the user changed or replaced: never learned as it was glided. */
    private fun abandon(w: GlidedWord) {
        pending.remove(w)
        held.remove(w)
        recent.remove(w)
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
        predictGeneration++
        predictions = emptyList()
        ui.showCandidates(emptyList())
        ui.setComposing(isComposing)
    }

    private fun requestSuggestions() {
        val s = suggester ?: return
        val typed = word.toString()
        val gen = ++suggestGeneration
        val taps = currentTaps()
        val ic = connection()
        val context = ic?.let { currentContext(it) }
        // Whether space would autocorrect this word, as endWord decides it (the text check is done here,
        // on the main thread).
        val correctable = settings.autocorrect && field.allowsComposing && !reopenedUnchanged &&
            typed.lowercase() !in keptAsTyped && !looksLikeCode(typed) && !startsAnIdentifier(typed) &&
            (ic == null || (!gluedToPrevious(ic, typed) && !capitalisedOnPurpose(ic, typed))) &&
            (ic == null || !appearsInText(ic, typed))
        background.execute {
            val result = s.suggest(typed, Suggester.AUTOCORRECT_CANDIDATES, taps, context)
            val fix = if (correctable) s.autocorrectFrom(typed, result, taps, context)?.takeIf { it != typed && keepsPunctuation(typed, it) } else null
            postToMain {
                if (gen != suggestGeneration || !isComposing) return@postToMain
                candidates = result
                candidatesFor = typed
                if (fix != null) {
                    val other = result.map { it.word }.firstOrNull { !it.equals(fix, ignoreCase = true) && !it.equals(typed, ignoreCase = true) }
                    ui.showCorrection(typed, fix, other)
                    return@postToMain
                }
                // An identifier from the text that starts with what was typed leads.
                val ids = identifiers.filter { it.length > typed.length && it.startsWith(typed, ignoreCase = true) }.take(1)
                val ranked = (ids + result.map { it.word }).distinctBy { it.lowercase() }.take(3)
                ui.showCandidates(arrangeForStrip(typed, ranked))
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
            t.glided?.let { abandon(it) }
            settle()
            if (replaceTarget(t, chosen)) {
                val idx = dictionary?.indexOfLower(chosen.lowercase()) ?: -1
                if (dictionary != null && idx >= 0) learner.correction(t.glided?.stroke, idx, dictionary)
                t.glided?.let { recent.remove(it) }
                if (field.allowsLearning) learner.learnWord(chosen, prev.first, prev.second)
            }
            clearCandidates()
            return
        }
        ownEdit()
        if (!isComposing && lastGlide == null && chosen in predictions) {
            // A predicted word goes in with a space, and the next ones are offered.
            val context = learningContext(ic)
            ic.commitText("$chosen ", 1)
            learnAs(chosen, context)
            clearCandidates()
            lastActionWasSpace = true
            lastSpaceTime = clock()
            showPredictions(ic)
            return
        }
        val join = joinOffer
        if (join != null && join.glide === lastGlide && (chosen == join.camel || chosen == join.snake)) {
            // The run of glided words becomes one name.
            if (ic.getTextBeforeCursor(join.runText.length, 0)?.toString() == join.runText) {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(join.runText.length, 0)
                ic.commitText(chosen, 1)
                ic.endBatchEdit()
                pending.clear()
                if (field.allowsLearning) learner.learnWord(chosen, null, false)
            }
            joinOffer = null
            lastGlide = null
            glideRun = emptyList()
            clearCandidates()
            return
        }
        val glide = lastGlide?.takeIf { g ->
            ic.getTextBeforeCursor(g.lastWord.length + g.after.length, 0)?.toString() == g.lastWord + g.after
        }
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
            val g = reopenedGlide
            val dictionary = dictionaryInUse
            if (reopenedUnchanged && g != null && dictionary != null && !chosen.equals(word.toString(), ignoreCase = true)) {
                // Another reading of a glided word picked after backspacing to it: a correction.
                val idx = dictionary.indexOfLower(chosen.lowercase())
                if (idx >= 0) learner.correction(g.stroke, idx, dictionary)
                abandon(g)
            }
            reopened = null
            // Picking the word exactly as typed (the strip's check mark), or as first typed before autocorrect
            // changed it, keeps it from autocorrect from now on.
            if (chosen == word.toString()) keptAsTyped += chosen.lowercase()
            reopenedCorrection?.let { if (reopenedUnchanged && chosen == it.typed) keptAsTyped += chosen.lowercase() }
            reopenedCorrection = null
            learnTyped(ic, chosen)
            ic.commitText("$chosen ", 1)
            word.setLength(0)
            clearCandidates()
            lastActionWasSpace = true
            lastSpaceTime = clock()
            showPredictions(ic)
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
        resolveTarget(ic)
        revisable = null
        revisableBefore(ic)?.let { r ->
            // The glide just before may be fixed by this one: the decoder reads the two together, from the
            // context before the earlier word.
            revisable = r
            val head = (ic.getTextBeforeCursor(CONTEXT_CHARS + r.tail.length, 0) ?: "").let { it.subSequence(0, it.length - r.tail.length) }
            val w2 = GlideText.contextWord2(head)
            val context2 = if (w2.isEmpty()) NgramModel.UNKNOWN else GlideText.contextId(w2, dictionary, lm)
            return GlideContext(GlideText.contextId(GlideText.contextWord(head), dictionary, lm), history = listOf(r.word.word), context2 = context2, sentence = sentenceBefore(ic))
        }
        val before = textBeforeTarget(ic)
        val w2 = GlideText.contextWord2(before)
        val context2 = if (w2.isEmpty()) NgramModel.UNKNOWN else GlideText.contextId(w2, dictionary, lm)
        return GlideContext(GlideText.contextId(GlideText.contextWord(before), dictionary, lm), context2 = context2, sentence = sentenceBefore(ic))
    }

    /** The sentence before where a word would go (before the targeted word, if any), for the next-word model. */
    private fun sentenceBefore(ic: InputConnection): List<String?> {
        val t = target
        val skip = if (t != null && !t.selection) t.before else 0
        val before = ic.getTextBeforeCursor(SENTENCE_CHARS + skip, 0) ?: return emptyList()
        return GlideText.sentenceWords(before.subSequence(0, maxOf(0, before.length - skip)))
    }

    /**
     * The last glided word, when a glide starting now may fix it: the setting is on, nothing is targeted or
     * being typed, the glide before is still the last thing typed, its last word was not picked from the
     * strip, and it stands exactly as it went in right before the cursor.
     */
    private fun revisableBefore(ic: InputConnection): Revisable? {
        if (!settings.fixPreviousGlide || !field.allowsComposing || target != null || isComposing) return null
        val g = lastGlide ?: return null
        val last = pending.lastOrNull() ?: return null
        if (last.word.locked || last.text != g.lastWord) return null
        if (!ic.getSelectedText(0).isNullOrEmpty()) return null
        val tail = last.text + g.after
        val before = ic.getTextBeforeCursor(tail.length + 1, 0)?.toString() ?: return null
        if (!before.endsWith(tail)) return null
        if (before.length > tail.length && isLetterInWord(before[0])) return null
        return Revisable(last, tail)
    }

    /**
     * Decides what a glide starting now replaces, from the field as it is rather than from selection
     * reports, which a quick tap can outrun: a cursor strictly inside a word (the keyboard's own edits never
     * leave it there, so the user put it there) or one selected word. Anything else replaces nothing, and a
     * stale underline goes first so the glide cannot land in it.
     */
    private fun resolveTarget(ic: InputConnection) {
        if (isComposing && reopenedUnchanged && field.allowsComposing) {
            // A word backspace reopened: the glide redoes it.
            target = reopenedTarget(ic)
            return
        }
        if (!field.allowsComposing || isComposing) {
            dropTarget()
            return
        }
        val sel = ic.getSelectedText(0)?.toString()
        if (!sel.isNullOrEmpty()) {
            val t = target
            if (t != null && t.selection && t.text == sel) return
            dropTarget()
            if (sel.all { isLetterInWord(it) } && sel.first().isLetter()) {
                target = Target(sel, -1, 0, 0, selection = true, glided = recentMatch(sel), underlined = false).also {
                    it.sentenceStart = GlideText.contextWord(ic.getTextBeforeCursor(CONTEXT_CHARS, 0) ?: "") == GlideText.SENTENCE_START
                }
            }
            return
        }
        val before = ic.getTextBeforeCursor(MAX_WORD, 0) ?: ""
        val after = ic.getTextAfterCursor(MAX_WORD, 0) ?: ""
        var b = 0
        while (b < before.length && isLetterInWord(before[before.length - 1 - b])) b++
        var a = 0
        while (a < after.length && isLetterInWord(after[a])) a++
        val text = if (b > 0 && a > 0) before.substring(before.length - b) + after.substring(0, a) else ""
        val t = target
        if (t != null && !t.selection && t.before == b && t.after == a && t.text == text) return
        dropTarget()
        if (text.isNotEmpty() && text.first().isLetter()) {
            target = Target(text, -1, b, a, selection = false, glided = recentMatch(text), underlined = false).also {
                it.sentenceStart = GlideText.contextWord(before.subSequence(0, before.length - b)) == GlideText.SENTENCE_START
            }
        }
    }

    private fun caseNew(word: String, capitalize: Boolean) = if (capitalize) word.replaceFirstChar { it.uppercaseChar() } else word

    /** Words the strip shows while the finger is still gliding. */
    fun previewText(result: GlideResult, dictionary: Dictionary, capitalize: Boolean): String {
        val t = target
        val sb = StringBuilder()
        for ((i, w) in result.words.withIndex()) {
            if (i > 0) sb.append(' ')
            val text = dictionary.words[w]
            sb.append(if (i == 0 && t != null) caseFor(t, text) else caseNew(text, i == 0 && capitalize))
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
        val previousGlide = lastGlide
        refreshIdentifiers(ic)
        lastAutocorrect = null
        if (isComposing) {
            if (target != null && reopenedUnchanged) {
                // The reopened word is the target: it is replaced below, not ended.
                word.setLength(0)
                reopened = null
            } else {
                endWord(ic, "", correct = true, deferOk = false)
            }
        }
        lastActionWasSpace = false
        reviseBefore(ic, result, dictionary)
        // A word being redone is not learned as it was; the rest of the glide before this one is final now.
        target?.glided?.let { abandon(it) }
        settle()
        ownEdit()
        val t = target
        if (t != null) {
            val fresh = newWords(result, dictionary, false, previousOf(textBeforeTarget(ic)))
            fresh.first().text = caseFor(t, fresh.first().text)
            val text = fresh.joinToString(" ") { it.text }
            if (replaceTarget(t, text)) {
                val first = fresh.first()
                if (!first.text.equals(t.text, ignoreCase = true)) learner.correction(t.glided?.stroke, first.word.word, dictionary)
                t.glided?.let { recent.remove(it) }
                remember(fresh)
                pending.addAll(fresh)
                val alternatives = result.alternatives.map { caseFor(t, dictionary.words[it]) }
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
        val glide = GlideCommit(ownText, fresh.last().text, alternatives, result.alternatives, fresh.size, after)
        lastGlide = glide
        // Words glided one after another make a run, which in code-like text can be joined into one name.
        val chained = previousGlide != null && previousGlide.after.isEmpty() && sb.startsWith(" ")
        glideRun = (if (chained) glideRun else emptyList()) + fresh.map { it.text }
        var strip = arrangeBestMiddle(alternatives)
        joinOffer = null
        if (glideRun.size >= 2 && identifiers.isNotEmpty() && after.isEmpty()) {
            val parts = glideRun.map { it.lowercase() }
            val camel = parts[0] + parts.drop(1).joinToString("") { p -> p.replaceFirstChar { it.uppercaseChar() } }
            val snake = parts.joinToString("_")
            joinOffer = JoinOffer(glideRun.joinToString(" "), camel, snake, glide)
            strip = listOf(camel, fresh.last().text, snake)
        } else if (fresh.size == 1) {
            identifierAlternative(result, fresh.last().text)?.let { id -> strip = listOf(id) + strip.drop(1) }
        }
        ui.showCandidates(strip)
        ui.setComposing(true)
    }

    /**
     * The decoder read the glide before this one differently now that it has the next word: that word is
     * rewritten in place, keeping its capitals, before this glide goes in. It is learned as it now reads,
     * without its stroke offsets (they were measured against the old word). Tapping it offers the old word
     * back among its alternatives.
     */
    private fun reviseBefore(ic: InputConnection, result: GlideResult, dictionary: Dictionary) {
        val r = revisable ?: return
        revisable = null
        if (target != null || result.firstRevised != 0 || result.history.size != 1) return
        val w = result.history[0]
        if (w < 0 || w == r.word.word.word || result.words.isEmpty()) return
        // Only when the new pair is one people write: after a rare word nothing is known about what follows,
        // so any next word looks no less likely there than anywhere, which would favour rare words ("to work
        // pretty" became "to dirk pretty").
        val lm = predictionModel?.second ?: return
        if (!lm.seenPair(w, result.words[0])) return
        val text = GlideText.matchCase(r.word.text, dictionary.words[w])
        if (ic.getTextBeforeCursor(r.tail.length, 0)?.toString() != r.tail) return
        val after = r.tail.substring(r.word.text.length)
        ic.beginBatchEdit()
        ic.deleteSurroundingText(r.tail.length, 0)
        ic.commitText(text + after, 1)
        ic.endBatchEdit()
        r.word.text = text
        r.word.word = r.word.word.revisedTo(w)
        r.word.observations = null
        lastGlide?.let { g ->
            g.text = g.text.substring(0, g.text.length - r.tail.length) + text + after
            g.lastWord = text
        }
    }

    /**
     * An identifier from the text that fits the glide's stroke about as well as the word chosen (within
     * [IDENTIFIER_MARGIN] of a key on average), for the strip; null when none does or no scorer is set.
     */
    private fun identifierAlternative(result: GlideResult, chosen: String): String? {
        val score = identifierScorer ?: return null
        val stroke = result.strokes.lastOrNull() ?: return null
        if (identifiers.isEmpty()) return null
        val own = score(stroke, chosen) ?: return null
        var best: String? = null
        var bestCost = own + IDENTIFIER_MARGIN
        for (id in identifiers.take(MAX_IDENTIFIERS_SCORED)) {
            val letters = id.filter { it.isLetter() }
            if (letters.equals(chosen, ignoreCase = true)) continue
            if (letters.length < chosen.length * 0.6 || letters.length > chosen.length * 1.8 + 2) continue
            val c = score(stroke, letters) ?: continue
            if (c < bestCost) {
                bestCost = c
                best = id
            }
        }
        return best
    }

    /** Reads the code-like identifiers around the cursor, at most every few seconds. */
    private fun refreshIdentifiers(ic: InputConnection) {
        val now = clock()
        if (now - identifiersReadAt < IDENTIFIERS_TTL_MS) return
        identifiersReadAt = now
        val text = (ic.getTextBeforeCursor(AROUND_CHARS, 0)?.toString() ?: "") + " " + (ic.getTextAfterCursor(AROUND_CHARS / 4, 0)?.toString() ?: "")
        val counts = HashMap<String, Int>()
        for (m in IDENTIFIER.findAll(text)) {
            val t = m.value
            if (looksLikeCode(t) && t.any { it.isLetter() }) counts[t] = (counts[t] ?: 0) + 1
        }
        identifiers = counts.entries.sortedByDescending { it.value }.map { it.key }.take(MAX_IDENTIFIERS)
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
        flushHeld()
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
        /** Identifiers: letters, digits and underscores, starting with a letter or underscore. */
        private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]{2,}")
        private const val MAX_IDENTIFIERS = 200
        private const val MAX_IDENTIFIERS_SCORED = 60
        private const val IDENTIFIERS_TTL_MS = 3000L
        /** How much worse (mean key pitches) an identifier may fit a glide than the word chosen and still be offered. */
        private const val IDENTIFIER_MARGIN = 0.1f
        /** Code mode's pairs: opening to closing. */
        private val PAIRS = mapOf('(' to ')', '[' to ']', '{' to '}', '"' to '"', '\'' to '\'', '`' to '`')
        private const val CLOSERS = ")]}"
        private const val QUOTES = "\"'`"
        /** Punctuation that ends a word the way space does, so autocorrect applies before it. */
        private const val SENTENCE_PUNCTUATION = ".,!?;:)\"'"
        /** Punctuation that joins a word to the one before it when no space follows ([gluedToPrevious]). */
        private const val JOINERS = "-/.@:_+#~\\=&"
        private val PRONOUN_I = setOf("i", "i'm", "i'd", "i'll", "i've")
        /** Selection reports this soon after the keyboard's own edit are taken as its own. */
        const val OWN_EDIT_MS = 600L
        /** Characters read before the cursor for deleting words by swiping from backspace. */
        private const val DELETE_CHARS = 2000
        private const val MAX_DELETE_WORDS = 40
        /** Characters read each side of the cursor to see whether a word is used elsewhere in the text. */
        private const val AROUND_CHARS = 4000
        /** Characters read before the cursor for the word before it. */
        private const val CONTEXT_CHARS = 64
        /** Text read before the cursor for the next-word model's sentence. */
        private const val SENTENCE_CHARS = 200
        /** Longest word looked at around the cursor. */
        private const val MAX_WORD = 48
        /** Characters before a corrected word that find it again: enough to tell two of the same word apart. */
        private const val CORRECTION_CONTEXT = 32
        /** Corrections remembered per field for backspace to take back. */
        private const val MAX_CORRECTIONS = 16
        /** Glided words remembered for redoing. */
        private const val MAX_RECENT = 32
        /** Glided words held back from learning, in case one turns out wrong a few words on ([held]). */
        private const val HELD_WORDS = 8

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
