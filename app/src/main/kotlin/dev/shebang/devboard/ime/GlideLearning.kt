package dev.shebang.devboard.ime

import dev.shebang.devboard.glide.GlideOutcomes
import dev.shebang.devboard.glide.GlideWord

/**
 * A glided word with what learning needs: the word before it, whether it began a sentence, where its stroke
 * passed each letter, and the stroke itself (for re-aligning after a correction).
 */
internal class GlidedWord(
    var text: String,
    var word: GlideWord,
    var previous: String?,
    var sentenceStart: Boolean,
    var observations: FloatArray?,
    val stroke: FloatArray?,
    /** How this word ends up if it is kept from now on: as glided, or fixed from the strip or by the next glide. */
    var fixedBy: Int = GlideOutcomes.KEPT,
)

/**
 * When glided words are learned, and which never are. The last glide's words are pending (backspace can still
 * remove them, the strip can still swap its last word); then held for a few words, since a wrong glide is often
 * noticed a little later; then learned. A word changed, redone or deleted while open is dropped unlearned, so a
 * mistake does not teach its word, its pairs or its stroke. Also remembers the words glided lately, so a word
 * tapped later has its runners-up and stroke. A collaborator of [TextInputController] (R27); [field] is the
 * field now, whose rules decide what may be learned.
 */
internal class GlideLearning(
    private val learner: TextInputController.Learner,
    private val field: () -> FieldInfo,
) {
    /** The last glide's words, not yet learned. */
    private val pending = ArrayList<GlidedWord>()

    /** Glided words that are final but not learned yet, newest last: the last [HELD_WORDS]. */
    private val held = ArrayDeque<GlidedWord>()

    /** Words glided in this field lately, newest last. */
    private val recent = ArrayDeque<GlidedWord>()

    val pendingCount: Int get() = pending.size

    /** The last glide's last word, while it is still open. */
    fun lastPending(): GlidedWord? = pending.lastOrNull()

    /** A new glide's words: open until the next thing happens, and remembered for redoing. */
    fun add(words: List<GlidedWord>) {
        for (w in words) {
            recent.addLast(w)
            if (recent.size > MAX_RECENT) recent.removeFirst()
        }
        pending.addAll(words)
    }

    /** The latest glided word written [text] (any case), for a tapped word. */
    fun findRecent(text: String): GlidedWord? = recent.lastOrNull { it.text.equals(text, ignoreCase = true) }

    /** [w] is no longer in the text as glided (it was replaced): not offered for redoing. */
    fun forgetRecent(w: GlidedWord) {
        recent.remove(w)
    }

    /** The last glide's words became something else (one joined name): none of them is learned. */
    fun dropPending() {
        pending.clear()
    }

    /** The last glide can no longer be undone or swapped: its words are held, then learned. */
    fun settle() {
        for (w in pending) {
            held.addLast(w)
            while (held.size > HELD_WORDS) retire(held.removeFirst())
        }
        pending.clear()
    }

    /** Everything open is final now (the field is left, or the language changed): learned. */
    fun finish() {
        settle()
        while (held.isNotEmpty()) retire(held.removeFirst())
    }

    /** Nothing glided lately is offered for redoing any more (a new field, a new language's word indices). */
    fun forgetRecent() {
        recent.clear()
    }

    /** A glided word the user changed or replaced ([how]): never learned as it was glided. */
    fun abandon(w: GlidedWord, how: Int) {
        // Counted only while it was still open: a word redone after it was final has had its outcome.
        val open = pending.remove(w) or held.remove(w)
        recent.remove(w)
        if (open) outcome(w, how)
    }

    /** Up to [n] of the last glide's words, newest first, were deleted: unlearned. Returns how many. */
    fun deletePending(n: Int): Int {
        val count = minOf(n, pending.size)
        repeat(count) {
            val w = pending.removeAt(pending.size - 1)
            recent.remove(w)
            outcome(w, GlideOutcomes.DELETED)
        }
        return count
    }

    /** Up to [n] held words, newest first, were deleted: unlearned. */
    fun deleteHeld(n: Int) {
        repeat(minOf(n, held.size)) { abandon(held.last(), GlideOutcomes.DELETED) }
    }

    /** A glided word's outcome, for the diagnostics (not where the app asks keyboards not to learn). */
    private fun outcome(w: GlidedWord, how: Int) {
        if (!field().noPersonalizedLearning) learner.glideOutcome(how, w.text.length)
    }

    /** A glided word is final: learn it, and where its stroke passed its letters. */
    private fun retire(w: GlidedWord) {
        outcome(w, w.fixedBy)
        if (!field().allowsLearning) return
        learner.learnWord(w.text, w.previous, w.sentenceStart)
        w.observations?.let { learner.learnGlide(it) }
    }

    private companion object {
        /** Glided words remembered for redoing. */
        const val MAX_RECENT = 32
        /** Glided words held back from learning, in case one turns out wrong a few words on. */
        const val HELD_WORDS = 8
    }
}
