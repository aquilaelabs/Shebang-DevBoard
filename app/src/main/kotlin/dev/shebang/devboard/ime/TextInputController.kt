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
import dev.shebang.devboard.glide.GlideOutcomes
import dev.shebang.devboard.glide.GlideContext
import dev.shebang.devboard.glide.GlideResult
import dev.shebang.devboard.glide.GlideWord
import dev.shebang.devboard.glide.TapModel
import dev.shebang.devboard.input.CharKeyCodes
import dev.shebang.devboard.input.KeyEventPlan
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
         * The strip shows next-word suggestions after a space: no word is in progress, so chips (the paste chip,
         * autofill) keep their row until a word is started (B15).
         */
        fun setPredicting() = setComposing(true)
        /** A letter was typed into a word: a chip offering to paste is no longer wanted. */
        fun wordStarted() = Unit
        /**
         * Space will autocorrect [typed] to [fix]: the strip offers [typed] with a check mark to keep it, the
         * correction marked as the one that will go in, and [other].
         */
        fun showCorrection(typed: String, fix: String, other: String?) = showCandidates(listOfNotNull(typed, fix, other))
        /** Remembered addresses to offer as cards for the address being typed; empty removes them. */
        fun showEmails(addresses: List<String>) = Unit
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
        /** The user kept [word] joined at a full stop ("n.m"): a personal word from now on, not split there. */
        fun keepJoined(word: String) = Unit
        /** How a glided word of [letters] letters ended up ([GlideOutcomes] outcome), once it is final. */
        fun glideOutcome(outcome: Int, letters: Int, reach: Float, durationMs: Long) = Unit

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

    /** Remembered addresses offered in email fields. */
    private val emailOffers = EmailOffers(background)
    /** Addresses entered in email fields, offered there as they are typed again; null when not remembered. */
    var emails: EmailMemory?
        get() = emailOffers.memory
        set(value) {
            emailOffers.memory = value
        }
    /** The keyboard shows code mode: brackets and quotes pair (when the setting is on). */
    var codeMode = false

    /**
     * How well a glide's stroke fits the keys of any letters (mean distance in key pitches, lower is better),
     * for offering identifiers from the text; set by the service for the current key layout.
     */
    var identifierScorer: ((stroke: FloatArray, letters: String) -> Float?)?
        get() = identifiers.scorer
        set(value) {
            identifiers.scorer = value
        }

    /** Whether the user's personal words hold [lower] (a word written with a full stop, "n.m"); set by the service. */
    var knowsPersonalWord: (lower: String) -> Boolean = { false }

    /** The dictionary and model for next-word suggestions; set by the service when the language is ready. */
    var predictionModel: Pair<Dictionary, NgramModel>? = null

    /** The strip's next-word predictions after a space. */
    private val nextWords = NextWords(background, postToMain)
    /** The next-word model for the strip's predictions, this controller's own copy (used on [background]). */
    var nextWordModel: dev.shebang.devboard.dict.NextWordModel?
        get() = nextWords.network
        set(value) {
            nextWords.network = value
        }

    /** Code-like identifiers in the text around the cursor. */
    private val identifiers = TextIdentifiers { clock() }

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
        commitWhole(ic, written)
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
        deleteBefore(ic, last.length)
        lastDictation = null
    }

    /** Whether [typed] begins a code-like identifier in the text around ("max" of "maxRetries"): not a slip. */
    private fun startsAnIdentifier(typed: String): Boolean = identifiers.startedBy(typed)

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
    /** The latest corrections in this field, and the words taken back from autocorrect. */
    private val corrections = Corrections()
    /** The correction backspace walked back to, while it is the reopened word: picking [Corrections.Entry.typed] keeps it. */
    private var reopenedCorrection: Corrections.Entry? = null

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
    /**
     * Punctuation just took the place of the space after a word ([swapWithSpace]): a letter typed next gets
     * that space back in front of it, so "the" space "." "next" reads "the. next".
     */
    private var spaceAfterPunctuation = false
    /**
     * The text before the cursor right after the keyboard wrote a word's space, or swapped it for punctuation: a
     * cursor report that finds it unchanged is that edit's own report arriving late (a slow app), not the user
     * moving the cursor, so the space can still give way to punctuation, and the space owed after the
     * punctuation still goes in front of the next word.
     */
    private var spaceTail: String? = null

    private fun rememberSpaceTail(ic: InputConnection) {
        spaceTail = ic.getTextBeforeCursor(SPACE_TAIL_CHARS, 0)?.toString()
    }

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
        /**
         * A glide split off as a new sentence after a full stop typed straight onto a word: the text as it stands
         * ("test. Neat") and as it was joined ("test.neat"), put back if a full stop follows ([joinAtSecondFullStop]).
         */
        val dotSplit: Pair<String, String>? = null,
    )
    private var lastGlide: GlideCommit? = null

    /** When glided words are learned: the last glide's words are open, then held a while, then learned. */
    private val glides = GlideLearning(learner) { field }

    private var dictionaryInUse: Dictionary? = null

    /** The word the user pointed at, which the next glide or a tapped alternative replaces. */
    private val targets = TargetedWord(object : TargetedWord.Host {
        override fun connection() = this@TextInputController.connection()
        override val ui get() = this@TextInputController.ui
        override val suggester get() = this@TextInputController.suggester
        override val dictionary get() = dictionaryInUse
        override val background get() = this@TextInputController.background
        override fun postToMain(r: Runnable) = this@TextInputController.postToMain(r)
        override fun ownEdit() = this@TextInputController.ownEdit()
        override val isComposing get() = this@TextInputController.isComposing
    })
    private var target: TargetedWord.Target?
        get() = targets.current
        set(value) {
            targets.current = value
        }

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
    /**
     * What the last glide wrote, while the cursor may still stand right after it. Letters following such a
     * cursor were not typed there by the user (the keyboard puts a space between a glide and a word after it):
     * the app completed the word around the cursor, as a browser's address bar fills in "example.com/" after a
     * glided "exam". That word is no target; the next glide goes after the glide, and the app drops its
     * completion when the text no longer continues it.
     */
    private var lastOwnTail: String? = null

    /** Whether the cursor stands right after the last glide's text, with the app's own letters following. */
    private fun appCompletedAfterGlide(ic: InputConnection): Boolean {
        val tail = lastOwnTail ?: return false
        if (tail.isEmpty() || ic.getTextBeforeCursor(tail.length, 0)?.toString() != tail) return false
        val next = ic.getTextAfterCursor(1, 0)?.firstOrNull() ?: return false
        return isLetterInWord(next)
    }

    val isComposing: Boolean get() = word.isNotEmpty()

    /** The targeted word (a glide or a strip pick will replace it), for tests and diagnostics. */
    val targetText: String? get() = target?.text

    fun startInput(field: FieldInfo) {
        // An email field left for another field: its addresses are remembered.
        rememberEmails()
        // What was glided in the last field is learned under that field's rules.
        glides.finish()
        this.field = field
        resetState()
    }

    private fun resetState() {
        word.setLength(0)
        candidates = emptyList()
        candidatesFor = ""
        lastAutocorrect = null
        corrections.clear()
        emailOffers.clear()
        ui.showEmails(emptyList())
        reopenedCorrection = null
        reopened = null
        reopenedGlide = null
        identifiers.reset()
        glideRun = emptyList()
        joinOffer = null
        lastGlide = null
        target = null
        glides.forgetRecent()
        glides.finish()
        lastActionWasSpace = false
        spaceAfterPunctuation = false
        lastOwnTail = null
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
        // The app changed the text under the predictions (cleared it after sending, a hardware keyboard typed,
        // text was selected): they no longer follow from it, and the bar comes back. Checked against the text,
        // not the time, because an app may clear the field right after the keyboard's own edit.
        if (nextWords.offered.isNotEmpty() && !isComposing) {
            val now = connection()?.getTextBeforeCursor(CONTEXT_CHARS, 0)?.toString()
            if (nextWords.stale(now, newSelStart != newSelEnd)) clearCandidates()
        }
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
        if ((lastActionWasSpace || spaceAfterPunctuation) && newSelStart == newSelEnd) {
            val tail = spaceTail
            if (tail != null && connection()?.getTextBeforeCursor(tail.length, 0)?.toString() == tail) return
        }
        // The cursor moved away: a space or punctuation typed now does not follow the last word.
        lastActionWasSpace = false
        spaceAfterPunctuation = false
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
        if (!field.allowsComposing || field.exact || isComposing) return
        val ic = connection() ?: return
        if (selEnd > selStart) {
            val sel = ic.getSelectedText(0)?.toString() ?: return
            if (sel.isEmpty() || !sel.all { isLetterInWord(it) } || !sel.first().isLetter()) return
            targets.set(TargetedWord.Target(sel, selStart, 0, 0, selection = true, glided = recentMatch(sel), underlined = false))
            return
        }
        val before = ic.getTextBeforeCursor(MAX_WORD, 0) ?: return
        val after = ic.getTextAfterCursor(MAX_WORD, 0) ?: ""
        var b = 0
        while (b < before.length && isLetterInWord(before[before.length - 1 - b])) b++
        var a = 0
        while (a < after.length && isLetterInWord(after[a])) a++
        // Only a cursor inside a word targets it: at a word's edge (where a tap between words lands) the user
        // may be adding a word, so nothing is targeted. Nor is a word the app completed after the last glide
        // ([appCompletedAfterGlide]): the next glide adds a word instead of replacing it.
        if (b == 0 || a == 0 || appCompletedAfterGlide(ic)) return
        val text = before.substring(before.length - b) + after.substring(0, a)
        if (!text.first().isLetter()) return
        val underlined = selStart >= 0 && ic.setComposingRegion(selStart - b, selStart + a)
        if (underlined) ownEdit()
        targets.set(TargetedWord.Target(text, selStart, b, a, selection = false, glided = recentMatch(text), underlined = underlined))
    }

    private fun isLetterInWord(c: Char) = c.isLetter() || c == '\'' || c == '’'

    private fun recentMatch(text: String): GlidedWord? = glides.findRecent(text)

    /** The target is no longer wanted: remove its underline (the text stays as it is). */
    private fun dropTarget() = targets.drop()

    /** Replaces the target with [replacement] (its capitals kept); true when it was still there to replace. */
    private fun replaceTarget(t: TargetedWord.Target, replacement: String) = targets.replace(t, replacement)

    /** How [word] is written in place of the target ([TargetedWord.caseFor]). */
    private fun caseFor(t: TargetedWord.Target, word: String) = targets.caseFor(t, word)

    /** Text before the target's first letter (or before the cursor when nothing is targeted), for context. */
    private fun textBeforeTarget(ic: InputConnection) = targets.textBefore(ic)

    // ---- Typing --------------------------------------------------------------------------------------

    fun typeText(text: String, tapX: Float = Float.NaN, tapY: Float = Float.NaN) {
        val ic = connection() ?: return
        dropTarget()
        ownEdit()
        lastAutocorrect = null
        val spaceBefore = lastActionWasSpace
        lastActionWasSpace = false
        // A letter typed where the app completed the last glide continues that word (the browser then keeps or
        // adjusts its completion), so it is not a new word after the glide.
        val continuesCompletion = appCompletedAfterGlide(ic)
        // The letter joins what the keyboard wrote before the completion, so the next glide still sees it.
        lastOwnTail = if (continuesCompletion && isWordChar(text)) lastOwnTail + text else null
        val owedSpace = spaceAfterPunctuation
        spaceAfterPunctuation = false
        val glideBefore = lastGlide
        settle()
        lastGlide = null
        if (glideBefore != null || nextWords.offered.isNotEmpty()) clearCandidates()
        if (text == "." && word.isEmpty() && joinAtSecondFullStop(ic, glideBefore)) return
        if (field.allowsComposing && isWordChar(text)) {
            // A letter right after a glide starts a new word, as a glide right after typing does (when the glide
            // still stands right before the cursor and nothing is selected).
            if (!field.isEmail && glideBefore != null && glideBefore.after.isEmpty() && word.isEmpty() && !continuesCompletion &&
                ic.getSelectedText(0).isNullOrEmpty() &&
                ic.getTextBeforeCursor(glideBefore.text.length, 0)?.toString() == glideBefore.text
            ) {
                typeOutsideWord(ic, " ")
            }
            // The space that punctuation took from the word before goes in front of the next word.
            if (owedSpace && word.isEmpty() && ic.getTextBeforeCursor(1, 0)?.firstOrNull()?.let { it in SWAPS_WITH_SPACE } == true) {
                ic.commitText(" ", 1)
            }
            if (word.isEmpty()) {
                identifiers.refresh(ic)
                resetTaps(0)
                // Typing on at the end of a word that is not composing (the field dropped it, or the cursor was
                // put there): the whole word is composed, so the underline and a strip pick cover all of it.
                recomposeWordBeforeCursor(ic)?.let { text -> recentMatch(text)?.let { abandon(it, GlideOutcomes.EDITED) } }
                reopenedGlide = null
            }
            word.append(text)
            reopenedGlide?.let { g -> if (!reopenedUnchanged) { abandon(g, GlideOutcomes.EDITED); reopenedGlide = null } }
            for (k in text.indices) {
                tapXs += if (text.length == 1) tapX else Float.NaN
                tapYs += if (text.length == 1) tapY else Float.NaN
            }
            ic.setComposingText(word, 1)
            ui.setComposing(true)
            ui.wordStarted()
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
            oweSpaceAfter(ic, text)
            return
        }
        if (pairing && text.length == 1 && typePaired(ic, text[0])) return
        if (spaceBefore && swapWithSpace(ic, text)) return
        typeOutsideWord(ic, text)
        oweSpaceAfter(ic, text)
    }

    /**
     * Punctuation that just went onto the end of a word ("hello" then ",") owes the space after it: it goes in
     * front of the next letter typed (a glide adds its own), and a space typed instead is the only one. A full
     * stop owes nothing yet: it may be part of a name ("node.js"), so the word after it decides
     * ([sentenceAfterFullStop]). Not in code mode, nor in web-address, email and exact fields.
     */
    private fun oweSpaceAfter(ic: InputConnection, text: String) {
        if (text.length != 1 || text[0] !in OWES_SPACE || codeMode || !field.allowsComposing || field.exact || field.isEmail || field.isUrl) return
        val before = ic.getTextBeforeCursor(2, 0) ?: return
        if (before.length < 2 || before.last() != text[0] || !before[0].isLetterOrDigit()) return
        spaceAfterPunctuation = true
        rememberSpaceTail(ic)
    }

    /**
     * Whether [typed], the word just finished, follows a full stop typed straight onto a word ("hello.world")
     * that ends a sentence: the word before is a word the keyboard knows, in a word list or the user's personal
     * words ("hello", not "n" of "n.m" or "www"; of single letters only "a" and "I" count), and the two joined
     * are not a known name ([knownJoined]: "node.js", "example.com", or one the user kept joined).
     */
    private fun sentenceAfterFullStop(ic: InputConnection, typed: String): Boolean {
        if (typed.isEmpty() || !typed[0].isLetter() || codeMode || !field.allowsComposing || field.exact || field.isEmail || field.isUrl) return false
        val before = ic.getTextBeforeCursor(typed.length + MAX_WORD + 1, 0)?.toString() ?: return false
        if (!before.endsWith(typed)) return false
        val rest = before.dropLast(typed.length)
        if (!rest.endsWith(".")) return false
        val prev = rest.dropLast(1).takeLastWhile { !it.isWhitespace() }
        if (prev.isEmpty() || !prev.all { it.isLetter() || it == '\'' || it == '’' }) return false
        if (!knownWord(prev)) return false
        return !knownJoined(prev, typed)
    }

    /** Whether [w] is a word: in a word list or the user's personal words; of single letters only "a" and "I". */
    private fun knownWord(w: String): Boolean {
        val lower = w.lowercase()
        if (lower.length == 1 && lower != "a" && lower != "i") return false
        if (knowsPersonalWord(lower)) return true
        val dictionary = predictionModel?.first ?: return false
        return dictionary.indexOfLower(lower) >= 0
    }

    /**
     * Whether [prev] "." [typed] is a name a dictionary knows: written so in a word list or the user's personal
     * words ("node.js", "e.g." for "e.g"), or with a known ending or lead-in ("example.com", "notes.txt",
     * "www.example": the lists hold ".com", ".txt" and "www.").
     */
    private fun knownJoined(prev: String, typed: String): Boolean {
        val joined = "$prev.$typed".lowercase()
        if (knowsPersonalWord(joined)) return true
        val dictionary = predictionModel?.first ?: return false
        for (i in dictionary.prefixRange(joined)) {
            val w = dictionary.lower[i]
            if (w == joined || w.startsWith("$joined.")) return true
        }
        // A known ending (".com") or lead-in ("www.") makes it a name too.
        return dictionary.indexOfLower(".${typed.lowercase()}") >= 0 || dictionary.indexOfLower("${prev.lowercase()}.") >= 0
    }

    /** Whether the field starts sentences with a capital, and the keyboard is to give it one. */
    private fun capitalisesSentences(): Boolean =
        settings.autoCaps && field.allowsAutoCaps && (field.inputType and android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) != 0

    /** A sentence just ended with a space owed after it: the next letter begins a sentence (for auto-capitals). */
    val sentenceStartOwed: Boolean
        get() {
            if (!spaceAfterPunctuation) return false
            val last = connection()?.getTextBeforeCursor(1, 0)?.firstOrNull() ?: return false
            return last in SENTENCE_ENDS
        }

    /**
     * A character typed outside a word. In an [FieldInfo.exact] field it goes as the key press for it, where the
     * US layout has one: a web terminal acts on a key press at once, but picks typed text up on a timer that a
     * quick Enter outruns, losing the character (B17).
     */
    /**
     * Writes [text] in one piece. In an [FieldInfo.exact] field it goes in as a finished composition, the way a
     * typed word does: text committed outright leaves the cursor at the start of a web terminal's input in Chrome,
     * so the next backspace deletes nothing and the next word lands in front of it (B17).
     */
    private fun commitWhole(ic: InputConnection, text: CharSequence) {
        if (!field.exact) {
            ic.commitText(text, 1)
            return
        }
        ic.setComposingText(text, 1)
        ic.finishComposingText()
    }

    /** Text the service puts in as one piece (a paste, a snippet, code mode's space), written as the field needs. */
    fun insert(text: CharSequence) {
        val ic = connection() ?: return
        if (text.length == 1) typeOutsideWord(ic, text.toString()) else commitWhole(ic, text)
    }

    private fun typeOutsideWord(ic: InputConnection, text: String) {
        val stroke = if (field.exact && text.length == 1) CharKeyCodes.forChar(text[0]) else null
        if (stroke == null) {
            commitWhole(ic, text)
            return
        }
        val meta = if (stroke.shift || text[0].isUpperCase()) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
        KeySender.send(ic, KeyEventPlan(stroke.keyCode, meta))
    }

    /**
     * Punctuation right after the space that ended a word (the space key, autocorrect, a strip pick, a predicted
     * word): it goes against the word instead ("the ." becomes "the."), as other keyboards do, and the space comes
     * back before the next word typed. Not in code mode or terminals, where every character is meant; in a
     * web-address field the space does not come back ("github" picked, then ".com", is "github.com").
     */
    private fun swapWithSpace(ic: InputConnection, text: String): Boolean {
        if (text.length != 1 || text[0] !in SWAPS_WITH_SPACE || codeMode || !field.allowsComposing || field.exact) return false
        if (!endsSentenceWord(ic)) return false
        ic.beginBatchEdit()
        ic.deleteSurroundingText(1, 0)
        ic.commitText(text, 1)
        ic.endBatchEdit()
        spaceAfterPunctuation = !field.isUrl
        rememberSpaceTail(ic)
        return true
    }

    private val pairing: Boolean get() = codeMode && settings.pairBrackets && !this.field.isTerminal && !this.field.exact

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
            // A quote the paragraph already opened is closed by this one, after a space or a full stop too.
            if (opensInParagraph(ic.getTextBeforeCursor(PARAGRAPH_CHARS, 0) ?: "", c)) return false
        }
        val closing = close ?: return false
        ic.beginBatchEdit()
        ic.commitText(c.toString(), 1)
        ic.commitText(closing.toString(), 0)
        ic.endBatchEdit()
        return true
    }

    /**
     * Whether [before] (the text before the cursor) leaves a [quote] open in its last paragraph: an odd number of
     * them since the last line break, not counting escaped ones (\") or apostrophes inside or after a word
     * ("don't", "James'").
     */
    private fun opensInParagraph(before: CharSequence, quote: Char): Boolean {
        val start = before.lastIndexOf('\n') + 1
        var open = false
        for (i in start until before.length) {
            if (before[i] != quote) continue
            val prev = if (i > start) before[i - 1] else null
            if (prev == '\\') continue
            if (quote == '\'' && prev != null && prev.isLetterOrDigit()) continue
            open = !open
        }
        return open
    }

    private fun isWordChar(text: String): Boolean {
        if (text.length != 1) return false
        val c = text[0]
        // Apostrophes and underscores join letters into one word ("don't", "max_retries").
        return c.isLetter() || ((c == '\'' || c == '_') && word.isNotEmpty())
    }

    fun space() {
        val ic = connection() ?: return
        spaceAfterPunctuation = false
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
            rememberSpaceTail(ic)
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
        } else if (settings.doubleSpacePeriod && field.allowsComposing && !field.isEmail && !field.exact && lastActionWasSpace && now - lastSpaceTime < DOUBLE_SPACE_MS && endsSentenceWord(ic)) {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(1, 0)
            ic.commitText(". ", 1)
            ic.endBatchEdit()
            lastActionWasSpace = false
            return
        } else {
            typeOutsideWord(ic, " ")
        }
        lastActionWasSpace = true
        rememberSpaceTail(ic)
        lastSpaceTime = now
        showPredictions(ic)
    }

    /**
     * After a space: the strip offers the three words most likely to come next, given the two before the
     * cursor (the corpus model mixed with the user's own word pairs), capitalised at a sentence start.
     */
    private fun showPredictions(ic: InputConnection) {
        // Not in code mode, where the next word is rarely English.
        // Nor where a page asked for exact typing (a web terminal): the words would take the terminal bar's row.
        if (!settings.nextWord || codeMode || !field.allowsComposing || field.isEmail || field.exact || isComposing || target != null) return
        val model = predictionModel ?: return
        val before = ic.getTextBeforeCursor(CONTEXT_CHARS, 0) ?: return
        nextWords.predict(model, before, sentenceBefore(ic), 3) { words ->
            if (isComposing) return@predict false
            ui.showCandidates(arrangeBestMiddle(words))
            ui.setPredicting()
            true
        }
    }

    /**
     * Space with a word targeted: the cursor goes after the word (and after the space behind it, adding one
     * if there is none), so the next glide adds a word there instead of replacing this one.
     */
    private fun skipPastTarget(ic: InputConnection, t: TargetedWord.Target) {
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

    /**
     * Deletes the [n] characters before the cursor. In an [FieldInfo.exact] field each goes as a backspace key
     * press, one per character (an emoji's surrogate pair is one): a web terminal acts on the key at once, but
     * notices deleted text only by comparing its input on a timer, which a slow phone can miss (B18), and passes a
     * longer deletion on as one backspace.
     */
    private fun deleteBefore(ic: InputConnection, n: Int) {
        if (n <= 0) return
        if (!field.exact) {
            ic.deleteSurroundingText(n, 0)
            return
        }
        val before = ic.getTextBeforeCursor(n, 0)?.toString().orEmpty()
        val presses = if (before.isEmpty()) n else before.codePointCount(0, before.length)
        repeat(presses) { KeySender.sendPlain(ic, KeyEvent.KEYCODE_DEL) }
    }

    /** Replaces the [n] characters before the cursor with [text]: one edit, or step by step in an exact field. */
    private fun replaceBefore(ic: InputConnection, n: Int, text: String) {
        if (field.exact) {
            deleteBefore(ic, n)
            commitWhole(ic, text)
            return
        }
        ic.beginBatchEdit()
        ic.deleteSurroundingText(n, 0)
        ic.commitText(text, 1)
        ic.endBatchEdit()
    }

    fun backspace() {
        val ic = connection() ?: return
        dropTarget()
        ownEdit()
        lastActionWasSpace = false
        spaceAfterPunctuation = false
        lastOwnTail = null
        val ac = lastAutocorrect
        lastAutocorrect = null
        if (ac != null && !isComposing && ic.getTextBeforeCursor(ac.corrected.length + ac.after.length, 0)?.toString() == ac.corrected + ac.after) {
            // Right after an autocorrect: put back what was typed (without the space), and leave it be from now on.
            ic.beginBatchEdit()
            ic.deleteSurroundingText(ac.corrected.length + ac.after.length, 0)
            ic.commitText(ac.typed, 1)
            ic.endBatchEdit()
            corrections.keepAsTyped(ac.typed)
            corrections.forget(ac.typed, ac.corrected)
            // A full stop's space taken back: the joined name ("n.m") is the user's, kept joined from now on.
            if (ac.corrected.startsWith(" ") && field.allowsLearning) {
                val joined = ic.getTextBeforeCursor(MAX_WORD * 2, 0)?.toString()?.takeLastWhile { !it.isWhitespace() }.orEmpty()
                if ('.' in joined) learner.keepJoined(joined.trimEnd('.'))
            }
            return
        }
        val glide = lastGlide
        lastGlide = null
        // Backspace right after a glide removes everything that glide wrote, unlearned: when it still stands
        // right before the cursor (a quick tap elsewhere can outrun the cursor report).
        if (glide != null && ic.getTextBeforeCursor(glide.text.length, 0)?.toString() == glide.text) {
            deleteBefore(ic, glide.text.length)
            glides.deletePending(glide.words)
            lastGlide = null
            clearCandidates()
            return
        }
        settle()
        if (isComposing) {
            // Backing up into a glided word and taking letters off it: it was wrong.
            reopenedGlide?.let { abandon(it, GlideOutcomes.EDITED) }
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
        if (field.exact) {
            deleteBefore(ic, n)
            return
        }
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
        val c = corrections.endingAtCursor(ic, text)
        reopenedCorrection = c
        if (c != null) {
            // Back to a word autocorrect changed: it stays, and the strip offers the words it offered before the
            // correction: what was typed, the correction, and the other suggestion for what was typed. No check
            // mark: space now leaves the word as it is, and the check mark means space would change it.
            reopenedGlide = null
            ui.setComposing(true)
            candidates = emptyList()
            candidatesFor = ""
            suggestGeneration++
            ui.showCandidates(listOf(c.typed, text, c.other?.takeIf { !it.equals(text, ignoreCase = true) }.orEmpty()))
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

    /** The strip's third word beside a correction: the best of [ranked] that is neither [typed] nor [fix]. */
    private fun otherSuggestion(typed: String, fix: String, ranked: List<Suggestion>): String? =
        ranked.map { it.word }.firstOrNull { !it.equals(fix, ignoreCase = true) && !it.equals(typed, ignoreCase = true) }

    /**
     * Makes the word ending at the cursor the composing word again and returns it, or null when there is none:
     * a letter or digit follows the cursor, nothing is selected, the word is glued to digits or symbols before
     * it ("x86"), or it does not start with a letter.
     */
    private fun recomposeWordBeforeCursor(ic: InputConnection): String? {
        if (!field.allowsComposing || field.exact || isComposing) return null
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
    private fun reopenedTarget(ic: InputConnection): TargetedWord.Target {
        val text = word.toString()
        val t = TargetedWord.Target(text, -1, text.length, 0, selection = false, glided = reopenedGlide, underlined = true)
        val before = ic.getTextBeforeCursor(CONTEXT_CHARS + text.length, 0) ?: ""
        t.sentenceStart = GlideText.contextWord(before.subSequence(0, maxOf(0, before.length - text.length))) == GlideText.SENTENCE_START
        return t
    }

    /**
     * Swiping left from backspace: the last [n] words before the cursor (each with the spaces after it) are
     * selected to show what will go; 0 selects nothing. The first call reads the text before the cursor once.
     */
    fun previewDeleteWords(n: Int) {
        // No highlight where the page mirrors edits: the selection would not show there, and deleting it would
        // reach the page as one deletion. The words go one character at a time when the swipe ends.
        if (field.exact) return
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
    /**
     * Backspace held a while: the word before the cursor goes, with the spaces after it. Where the words
     * cannot be seen (password and terminal fields, or nothing readable before the cursor) or text is
     * selected, an ordinary backspace instead.
     */
    fun backspaceWord() {
        val ic = connection() ?: return
        if (field.isTerminal || field.isPassword || !ic.getSelectedText(0).isNullOrEmpty() ||
            ic.getTextBeforeCursor(1, 0).isNullOrEmpty()
        ) {
            backspace()
            return
        }
        dropTarget()
        lastAutocorrect = null
        lastGlide = null
        lastActionWasSpace = false
        spaceAfterPunctuation = false
        deleteWords(1)
    }

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
        deleteBefore(ic, offs[n.coerceIn(0, offs.size - 1)])
        forgetDeletedGlide(n)
    }

    /**
     * [n] words before the cursor were swiped away. The last glide's words are the last ones written, so as
     * many of them go unlearned, as with backspace right after a glide: a wrong glide deleted this way must not
     * teach its word or its stroke. The rest of that glide is final.
     */
    private fun forgetDeletedGlide(n: Int) {
        val fromPending = glides.deletePending(n)
        // Words before the last glide that went too were glided before it, if they were glided at all.
        if (glides.pendingCount == 0) glides.deleteHeld(n - fromPending)
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
        spaceAfterPunctuation = false
        lastAutocorrect = null
        lastGlide = null
        settle()
        if (isComposing) endWord(ic, "", correct = true, deferOk = false)
        clearCandidates()
        when {
            // In an exact web field Enter is the key press: as text, Chrome delivers it as an empty edit and then
            // the key, and a web terminal answers the empty edit with a stray backspace.
            field.enterIsNewline -> if (field.exact) KeySender.sendPlain(ic, KeyEvent.KEYCODE_ENTER) else ic.commitText("\n", 1)
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
        val canCorrect = correct && !untouched && settings.autocorrect && field.allowsComposing && !field.noAutocorrect &&
            !corrections.isKeptAsTyped(typed) && !looksLikeCode(typed) && !startsAnIdentifier(typed) && !gluedToPrevious(ic, typed) &&
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
        // A full stop typed onto the word before ("hello.world"): a sentence's end unless the two joined are a known
        // name, so the space goes in (and the capital, where the field starts sentences with one); a name like
        // "node.js" stays as typed.
        // A full stop typed straight onto this word too ("test.neat.") makes the two parts of a dotted name.
        val lead = if (after != "." && sentenceAfterFullStop(ic, typed)) " " else ""
        // The pronoun I, but not the "i" of a name joined by punctuation ("n.m.i").
        val pronounCased = !field.noAutocorrect && (lead.isNotEmpty() || !gluedToPrevious(ic, typed))
        if (pronounCased) commit = pronounCase(commit)
        if (lead.isNotEmpty() && capitalisesSentences()) commit = commit.replaceFirstChar { it.uppercaseChar() }
        // The strip's third word beside the correction, kept with it before the suggestions are cleared.
        val other = otherSuggestion(typed, commit, if (candidatesFor == typed) candidates else emptyList())
        if (field.exact && commit == typed) {
            // One edit, so a page that mirrors edits (a web terminal) sees the word end once: the word and what
            // follows it committed together, or the word finished where it stands. Never a one-character commit of
            // the word itself: Firefox passes that on to the page as a key press as well, and the terminal gets
            // the letter twice (B17).
            if (after.isNotEmpty()) ic.commitText(typed + after, 1) else ic.finishComposingText()
        } else {
            ic.beginBatchEdit()
            ic.commitText(lead + commit, 1)
            if (after.isNotEmpty()) ic.commitText(after, 1)
            ic.endBatchEdit()
        }
        word.setLength(0)
        clearCandidates()
        if (lead.isNotEmpty() || commit != (if (pronounCased) pronounCase(typed) else typed)) {
            // Backspace right after puts back what was typed, the full stop's space included.
            lastAutocorrect = Autocorrected(typed, lead + commit, after)
            corrections.remember(ic, typed, lead + commit, after.length, other)
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
            val ranked = if (fix != null) s.suggest(typed, Suggester.AUTOCORRECT_CANDIDATES, taps, before) else emptyList()
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
                    corrections.remember(ic2, typed, late, after.length + next.length, otherSuggestion(typed, late, ranked))
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

    private fun looksLikeCode(w: String) = TextIdentifiers.looksLikeCode(w)

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

    /** The last glide can no longer be undone or swapped: its words are held, then learned. */
    private fun settle() = glides.settle()

    /** A glided word the user changed or replaced ([how]): never learned as it was glided. */
    private fun abandon(w: GlidedWord, how: Int) = glides.abandon(w, how)

    // ---- Suggestions ---------------------------------------------------------------------------------

    /**
     * In an email field, offers the remembered addresses that begin with what is being typed (the most used
     * while nothing is), in place of word suggestions. Call after each edit, and with [edited] false when the
     * field starts: only what the user typed there is remembered, not an address the field came with.
     */
    fun refreshEmails(edited: Boolean = true) {
        val offered = emailOffers.offer.isNotEmpty()
        val matches = emailOffers.refresh(field, connection(), edited, predictionModel?.first)
        if (matches == null) {
            if (offered) ui.showEmails(emptyList())
            return
        }
        if (matches.isNotEmpty() || offered) ui.showEmails(matches)
    }

    /** An offered address card was tapped: it replaces what was typed of it. */
    fun pickEmail(address: String) {
        val ic = connection() ?: return
        if (emailOffers.isOffered(address)) pickEmail(ic, address)
    }

    /** The address cards were swiped away: none come back until the next address is begun. */
    fun dismissEmails() {
        emailOffers.dismiss()
        ui.showEmails(emptyList())
    }

    /** Leaving an email field (or the keyboard closing): the addresses it last held are remembered. */
    fun rememberEmails() = emailOffers.remember()

    /** An offered address picked: it replaces what was typed of it. */
    private fun pickEmail(ic: InputConnection, address: String) {
        ownEdit()
        ic.beginBatchEdit()
        if (isComposing) {
            ic.finishComposingText()
            word.setLength(0)
            reopened = null
            reopenedCorrection = null
        }
        emailOffers.fill(ic, address)
        ic.endBatchEdit()
        emailOffers.keep(field, ic)
        ui.showEmails(emptyList())
        clearCandidates()
    }

    private fun clearCandidates() {
        candidates = emptyList()
        suggestGeneration++
        nextWords.clear()
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
        val correctable = settings.autocorrect && field.allowsComposing && !field.noAutocorrect && !reopenedUnchanged &&
            !corrections.isKeptAsTyped(typed) && !looksLikeCode(typed) && !startsAnIdentifier(typed) &&
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
                    val other = otherSuggestion(typed, fix, result)
                    ui.showCorrection(typed, fix, other)
                    return@postToMain
                }
                // An identifier from the text that starts with what was typed leads.
                val ids = listOfNotNull(identifiers.completing(typed))
                val ranked = (ids + result.map { it.word }).distinctBy { it.lowercase() }.take(3)
                ui.showCandidates(arrangeForStrip(typed, ranked))
            }
        }
    }

    /** Picks a strip word: replaces the targeted word, the composing word, or the last glide's last word. */
    fun pickCandidate(chosen: String) {
        val ic = connection() ?: return
        if (emailOffers.isOffered(chosen)) {
            pickEmail(ic, chosen)
            return
        }
        val t = target
        if (t != null) {
            if (chosen.equals(t.text, ignoreCase = true)) {
                dropTarget()
                return
            }
            val dictionary = dictionaryInUse
            val prev = previousOf(textBeforeTarget(ic))
            // A word being redone is not learned as it was; the rest of the last glide is final now.
            t.glided?.let { abandon(it, GlideOutcomes.STRIP) }
            settle()
            if (replaceTarget(t, chosen)) {
                val idx = dictionary?.indexOfLower(chosen.lowercase()) ?: -1
                if (dictionary != null && idx >= 0) learner.correction(t.glided?.stroke, idx, dictionary)
                t.glided?.let { glides.forgetRecent(it) }
                if (field.allowsLearning) learner.learnWord(chosen, prev.first, prev.second)
            }
            clearCandidates()
            return
        }
        ownEdit()
        if (!isComposing && lastGlide == null && nextWords.isOffered(chosen)) {
            // A predicted word goes in with a space, and the next ones are offered.
            val context = learningContext(ic)
            ic.commitText("$chosen ", 1)
            learnAs(chosen, context)
            clearCandidates()
            lastActionWasSpace = true
            rememberSpaceTail(ic)
            lastSpaceTime = clock()
            showPredictions(ic)
            return
        }
        val join = joinOffer
        if (join != null && join.glide === lastGlide && (chosen == join.camel || chosen == join.snake)) {
            // The run of glided words becomes one name.
            if (ic.getTextBeforeCursor(join.runText.length, 0)?.toString() == join.runText) {
                replaceBefore(ic, join.runText.length, chosen)
                glides.dropPending()
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
            replaceBefore(ic, tail.length, replacement)
            glide.text = glide.text.dropLast(tail.length) + replacement
            glide.lastWord = chosen
            // A word picked by hand is a correction; it is learned as picked, and its stroke not trusted.
            val idx = glide.alternatives.indexOf(chosen)
            val last = glides.lastPending()
            if (last != null && idx >= 0) {
                val dictionary = dictionaryInUse
                if (dictionary != null && glide.alternativeWords[idx] != last.word.word) {
                    learner.correction(last.stroke, glide.alternativeWords[idx], dictionary)
                }
                last.text = chosen
                last.fixedBy = GlideOutcomes.STRIP
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
                abandon(g, GlideOutcomes.STRIP)
            }
            reopened = null
            // Picking the word exactly as typed (the strip's check mark), or as first typed before autocorrect
            // changed it, keeps it from autocorrect from now on.
            if (chosen == word.toString()) corrections.keepAsTyped(chosen)
            reopenedCorrection?.let { if (reopenedUnchanged && chosen == it.typed) corrections.keepAsTyped(chosen) }
            reopenedCorrection = null
            learnTyped(ic, chosen)
            // An email address goes on as typed: no space after the word.
            ic.commitText(if (field.isEmail) chosen else "$chosen ", 1)
            word.setLength(0)
            clearCandidates()
            lastActionWasSpace = !field.isEmail
            rememberSpaceTail(ic)
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
        if (!settings.fixPreviousGlide || !field.allowsComposing || field.exact || target != null || isComposing) return null
        val g = lastGlide ?: return null
        val last = glides.lastPending() ?: return null
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
                target = TargetedWord.Target(sel, -1, 0, 0, selection = true, glided = recentMatch(sel), underlined = false).also {
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
        // A word the app completed after the last glide is not one the user pointed into.
        val text = if (b > 0 && a > 0 && !appCompletedAfterGlide(ic)) before.substring(before.length - b) + after.substring(0, a) else ""
        val t = target
        if (t != null && !t.selection && t.before == b && t.after == a && t.text == text) return
        dropTarget()
        if (text.isNotEmpty() && text.first().isLetter()) {
            target = TargetedWord.Target(text, -1, b, a, selection = false, glided = recentMatch(text), underlined = false).also {
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

    private fun newWords(result: GlideResult, dictionary: Dictionary, capitalize: Boolean, previous: Pair<String?, Boolean>, durationMs: Long): List<GlidedWord> {
        var prev = previous.first
        var sentenceStart = previous.second
        return result.words.mapIndexed { i, w ->
            val text = caseNew(dictionary.words[w], i == 0 && capitalize)
            GlidedWord(text, result.entries[i], prev, sentenceStart, result.observations.getOrNull(i), result.strokes.getOrNull(i), durationMs = durationMs).also {
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
     * space before it after a word, and a space after it before one. [durationMs] is the stroke's, touch-down to
     * lift, for the diagnostics; -1 when not known.
     */
    fun commitGlide(result: GlideResult, dictionary: Dictionary, capitalize: Boolean, trailingSpace: Boolean, durationMs: Long = -1L) {
        if (result.words.isEmpty()) return
        val ic = connection() ?: return
        dictionaryInUse = dictionary
        val previousGlide = lastGlide
        identifiers.refresh(ic)
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
        spaceAfterPunctuation = false
        reviseBefore(ic, result, dictionary)
        // A word being redone is not learned as it was; the rest of the glide before this one is final now.
        target?.glided?.let { abandon(it, GlideOutcomes.REDONE) }
        settle()
        ownEdit()
        val t = target
        if (t != null) {
            val fresh = newWords(result, dictionary, false, previousOf(textBeforeTarget(ic)), durationMs)
            fresh.first().text = caseFor(t, fresh.first().text)
            val text = fresh.joinToString(" ") { it.text }
            if (replaceTarget(t, text)) {
                lastOwnTail = text
                val first = fresh.first()
                if (!first.text.equals(t.text, ignoreCase = true)) learner.correction(t.glided?.stroke, first.word.word, dictionary)
                t.glided?.let { glides.forgetRecent(it) }
                glides.add(fresh)
                val alternatives = result.alternatives.map { caseFor(t, dictionary.words[it]) }
                lastGlide = GlideCommit(text, fresh.last().text, alternatives, result.alternatives, fresh.size, "")
                ui.showCandidates(arrangeBestMiddle(alternatives))
                ui.setComposing(true)
                return
            }
        }
        val fresh = newWords(result, dictionary, capitalize, previousOf(ic.getTextBeforeCursor(CONTEXT_CHARS, 0) ?: ""), durationMs)
        // Gliding in front of a word keeps them apart; a dip into the space bar adds one anyway. Letters the app
        // completed after the last glide are about to go, so no space is kept for them.
        val after = if (trailingSpace || (needsTrailingSpace(ic) && !appCompletedAfterGlide(ic))) " " else ""
        val ownText = fresh.joinToString(" ") { it.text } + after
        val sb = StringBuilder()
        if (needsLeadingSpace(ic)) sb.append(' ')
        sb.append(ownText)
        commitWhole(ic, sb)
        lastOwnTail = ownText
        // A phrase glide that lifted in the space bar ended its word with a space, as the space key does.
        lastActionWasSpace = trailingSpace
        glides.add(fresh)
        val alternatives = result.alternatives.map { caseNew(dictionary.words[it], fresh.size == 1 && capitalize) }
        val dotSplit = if (sb.startsWith(" ") && fresh.size == 1 && after.isEmpty()) dotSplitBefore(ic, sb.length, dictionary.words[result.words[0]]) else null
        val glide = GlideCommit(ownText, fresh.last().text, alternatives, result.alternatives, fresh.size, after, dotSplit)
        lastGlide = glide
        // Words glided one after another make a run, which in code-like text can be joined into one name.
        val chained = previousGlide != null && previousGlide.after.isEmpty() && sb.startsWith(" ")
        glideRun = (if (chained) glideRun else emptyList()) + fresh.map { it.text }
        var strip = arrangeBestMiddle(alternatives)
        joinOffer = null
        if (glideRun.size >= 2 && !identifiers.isEmpty() && after.isEmpty()) {
            val parts = glideRun.map { it.lowercase() }
            val camel = parts[0] + parts.drop(1).joinToString("") { p -> p.replaceFirstChar { it.uppercaseChar() } }
            val snake = parts.joinToString("_")
            joinOffer = JoinOffer(glideRun.joinToString(" "), camel, snake, glide)
            strip = listOf(camel, fresh.last().text, snake)
        } else if (fresh.size == 1) {
            identifiers.alternative(result, fresh.last().text)?.let { id -> strip = listOf(id) + strip.drop(1) }
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
        r.word.fixedBy = GlideOutcomes.NEXT_GLIDE
        r.word.word = r.word.word.revisedTo(w)
        r.word.observations = null
        lastGlide?.let { g ->
            g.text = g.text.substring(0, g.text.length - r.tail.length) + text + after
            g.lastWord = text
        }
    }

    /** The language was rebuilt: dictionary indices changed, so remembered glides and the target go. */
    fun onLanguageChanged() {
        dropTarget()
        glides.finish()
        lastGlide = null
        glides.forgetRecent()
    }

    /** No space at the field start, after whitespace or a newline, or after an opening bracket. */
    private fun needsLeadingSpace(ic: InputConnection): Boolean {
        if (field.isEmail) return false
        val before = ic.getTextBeforeCursor(1, 0)
        if (before.isNullOrEmpty()) return false
        // In a web-address field a glide right after an address's punctuation is part of it ("github." "com").
        if (field.isUrl && before[0] in URL_JOINERS) return false
        // After a dotted name's full stop ("test.neat.") the glide is its next part.
        if (before[0] == '.' && afterDottedName(ic)) return false
        return !GlideText.atWordStart(before, 1)
    }

    /**
     * The glide just written ([length] characters, its space first) went in after a full stop typed straight onto a
     * word ("test." then "neat"): the text as it now stands and as it reads joined, with the glided word as the
     * dictionary writes it ([word]) rather than with a sentence's capital. Null when what is before is not that.
     */
    private fun dotSplitBefore(ic: InputConnection, length: Int, word: String): Pair<String, String>? {
        val before = ic.getTextBeforeCursor(length + MAX_WORD + 1, 0)?.toString() ?: return null
        if (before.length <= length) return null
        val written = before.takeLast(length)
        val rest = before.dropLast(length)
        if (!rest.endsWith(".")) return null
        val prev = rest.dropLast(1).takeLastWhile { !it.isWhitespace() }
        if (prev.isEmpty() || !prev.all { it.isLetterOrDigit() }) return null
        return "$prev.$written" to "$prev.$word"
    }

    /**
     * A full stop typed straight onto a glided word that was split off after a full stop ("test. Neat" then "."):
     * the two are parts of a dotted name, a host name like "test.neat.fish", so they go back together with this
     * full stop after them. Backspace right after a split did this before; a second full stop now does it too.
     */
    private fun joinAtSecondFullStop(ic: InputConnection, glide: GlideCommit?): Boolean {
        val (tail, joined) = glide?.dotSplit ?: return false
        val before = ic.getTextBeforeCursor(tail.length + 1, 0)?.toString() ?: return false
        if (!before.endsWith(tail) || (before.length > tail.length && !before[0].isWhitespace())) return false
        ic.beginBatchEdit()
        ic.deleteSurroundingText(tail.length, 0)
        ic.commitText("$joined.", 1)
        ic.endBatchEdit()
        return true
    }

    /**
     * Whether the text before the cursor ends in a dotted name and its full stop ("test.neat."): two or more parts
     * of at least two letters or digits, so abbreviations ("e.g.", "a.m.", "Ph.D.") and numbers ("3.5.") are not.
     */
    private fun afterDottedName(ic: InputConnection): Boolean {
        val token = ic.getTextBeforeCursor(2 * MAX_WORD, 0)?.takeLastWhile { !it.isWhitespace() }?.toString() ?: return false
        if (!token.endsWith(".")) return false
        val parts = token.dropLast(1).split('.')
        return parts.size >= 2 && parts.all { p -> p.length >= 2 && p.all { it.isLetterOrDigit() || it == '-' } }
    }

    /** A space after the glide when a word follows the cursor directly. */
    private fun needsTrailingSpace(ic: InputConnection): Boolean {
        if (field.isEmail) return false
        val after = ic.getTextAfterCursor(1, 0)
        return !after.isNullOrEmpty() && after[0].isLetterOrDigit()
    }

    companion object {
        private const val DOUBLE_SPACE_MS = 600L
        /** Code mode's pairs: opening to closing. */
        private val PAIRS = mapOf('(' to ')', '[' to ']', '{' to '}', '"' to '"', '\'' to '\'', '`' to '`')
        private const val CLOSERS = ")]}"
        private const val QUOTES = "\"'`"
        /** Text read back to find the paragraph's open quotes. */
        private const val PARAGRAPH_CHARS = 2000
        /** Punctuation that takes the place of the space after a word ([swapWithSpace]). Quotes are left out: one may open a quotation. */
        private const val SWAPS_WITH_SPACE = ".,!?;:)"
        /** Punctuation that, typed onto the end of a word, owes the space after it ([oweSpaceAfter]); a full stop decides later. */
        private const val OWES_SPACE = ",!?;:"
        /** Punctuation that ends a sentence. */
        private const val SENTENCE_ENDS = ".!?"
        /** In a web-address field, punctuation a glided word attaches to without a space. */
        private const val URL_JOINERS = "./:@-_#?=&~"
        /** Punctuation that ends a word the way space does, so autocorrect applies before it. */
        private const val SENTENCE_PUNCTUATION = ".,!?;:)\"'"
        /** Punctuation that joins a word to the one before it when no space follows ([gluedToPrevious]). */
        private const val JOINERS = "-/.@:_+#~\\=&"
        private val PRONOUN_I = setOf("i", "i'm", "i'd", "i'll", "i've")
        /** Selection reports this soon after the keyboard's own edit are taken as its own. */
        const val OWN_EDIT_MS = 600L
        /** Text kept from before the cursor to recognise a late report of the keyboard's own space ([spaceTail]). */
        private const val SPACE_TAIL_CHARS = 48
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
