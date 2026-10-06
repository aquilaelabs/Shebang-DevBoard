package dev.shebang.devboard.ime

import android.view.inputmethod.InputConnection
import dev.shebang.devboard.glide.GlideResult

/**
 * Code-like identifiers in the text around the cursor ("maxRetries", "user_id"), most used first: typing one's
 * start is not a slip to correct, typing more of one offers it, and a glide that fits one about as well as the
 * word it read offers it too. A collaborator of [TextInputController] (R27).
 */
internal class TextIdentifiers(private val clock: () -> Long) {
    /**
     * How well a glide's stroke fits the keys of any letters (mean distance in key pitches, lower is better);
     * set by the service for the current key layout.
     */
    var scorer: ((stroke: FloatArray, letters: String) -> Float?)? = null

    private var identifiers: List<String> = emptyList()
    private var readAt = NEVER

    fun isEmpty() = identifiers.isEmpty()

    /** A new field: nothing read yet. */
    fun reset() {
        identifiers = emptyList()
        readAt = NEVER
    }

    /** Reads the identifiers around the cursor, at most every few seconds. */
    fun refresh(ic: InputConnection) {
        val now = clock()
        if (now - readAt < TTL_MS) return
        readAt = now
        val text = (ic.getTextBeforeCursor(AROUND_CHARS, 0)?.toString() ?: "") + " " + (ic.getTextAfterCursor(AROUND_CHARS / 4, 0)?.toString() ?: "")
        val counts = HashMap<String, Int>()
        for (m in IDENTIFIER.findAll(text)) {
            val t = m.value
            if (looksLikeCode(t) && t.any { it.isLetter() }) counts[t] = (counts[t] ?: 0) + 1
        }
        identifiers = counts.entries.sortedByDescending { it.value }.map { it.key }.take(MAX_IDENTIFIERS)
    }

    /** Whether [typed] begins an identifier ("max" of "maxRetries"): not a slip. */
    fun startedBy(typed: String): Boolean = typed.isNotEmpty() && completing(typed) != null

    /** The most used identifier that [typed] begins, to lead the strip; null when none. */
    fun completing(typed: String): String? =
        identifiers.firstOrNull { it.length > typed.length && it.startsWith(typed, ignoreCase = true) }

    /**
     * An identifier that fits the glide's stroke about as well as the word chosen (within [MARGIN] of a key on
     * average), for the strip; null when none does or no scorer is set.
     */
    fun alternative(result: GlideResult, chosen: String): String? {
        val score = scorer ?: return null
        val stroke = result.strokes.lastOrNull() ?: return null
        if (identifiers.isEmpty()) return null
        val own = score(stroke, chosen) ?: return null
        var best: String? = null
        var bestCost = own + MARGIN
        for (id in identifiers.take(MAX_SCORED)) {
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

    companion object {
        /** A word written the way code is: digits or underscores, or capitals inside it after lowercase. */
        fun looksLikeCode(w: String): Boolean {
            if (w.any { it.isDigit() || it == '_' }) return true
            val inner = w.drop(1)
            return inner.any { it.isUpperCase() } && inner.any { it.isLowerCase() }
        }

        private const val NEVER = Long.MIN_VALUE / 2
        /** Identifiers: letters, digits and underscores, starting with a letter or underscore. */
        private val IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]{2,}")
        private const val MAX_IDENTIFIERS = 200
        private const val MAX_SCORED = 60
        private const val TTL_MS = 3000L
        /** How much worse (mean key pitches) an identifier may fit a glide than the word chosen and still be offered. */
        private const val MARGIN = 0.1f
        /** Text read around the cursor (before it; a quarter of it after). */
        private const val AROUND_CHARS = 4000
    }
}
