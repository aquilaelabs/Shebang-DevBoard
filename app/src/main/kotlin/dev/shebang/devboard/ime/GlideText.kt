package dev.shebang.devboard.ime

import dev.shebang.devboard.dict.Dictionary
import dev.shebang.devboard.dict.NgramModel

/** Pure text helpers for glide commits and context, kept free of Android so they are unit-testable. */
object GlideText {
    /** Result of [contextWord] for the start of a sentence (or of the field). */
    const val SENTENCE_START = "<s>"

    /**
     * The word before the cursor, lowercased, read the way the n-gram model was trained: commas, quotes and
     * brackets are skipped; ". ! ?", a newline or the start of the field mean a new sentence
     * ([SENTENCE_START]); digits or other symbols give "" (unknown).
     */
    fun contextWord(before: CharSequence): String {
        var i = before.length - 1
        while (i >= 0) {
            val c = before[i]
            if (c == '\n' || c == '.' || c == '!' || c == '?') return SENTENCE_START
            if (c.isLetterOrDigit() || c == '\'' || c == '’') break
            if (!c.isWhitespace() && c !in SKIPPED) return ""
            i--
        }
        if (i < 0) return SENTENCE_START
        val end = i + 1
        while (i >= 0 && (before[i].isLetterOrDigit() || before[i] == '\'' || before[i] == '’')) i--
        val word = before.subSequence(i + 1, end).toString().replace('’', '\'').lowercase()
        return if (word.all { it.isLetter() || it == '\'' }) word.trim('\'') else ""
    }

    /**
     * The word before the one [contextWord] reads (for the trigram), the same way; "" when that one is not a
     * word (a sentence start or unknown has nothing before it that counts).
     */
    fun contextWord2(before: CharSequence): String {
        var i = before.length - 1
        while (i >= 0) {
            val c = before[i]
            if (c == '\n' || c == '.' || c == '!' || c == '?') return ""
            if (c.isLetterOrDigit() || c == '\'' || c == '’') break
            if (!c.isWhitespace() && c !in SKIPPED) return ""
            i--
        }
        if (i < 0) return ""
        while (i >= 0 && (before[i].isLetterOrDigit() || before[i] == '\'' || before[i] == '’')) i--
        return contextWord(before.subSequence(0, i + 1))
    }

    /** The model context for a word from [contextWord]. */
    private val SENTENCE_TOKEN = Regex("[a-z]+(?:'[a-z]+)*|[0-9]+|[.!?]+|\n")

    /**
     * The words of the sentence the cursor is in, before it, lowercase, as the next-word model was trained
     * (tools/build_ngrams.py's tokens): a sentence ends at . ! ? or a line break, and a number is a null.
     */
    fun sentenceWords(before: CharSequence): List<String?> {
        val out = ArrayList<String?>()
        val text = before.toString().lowercase().replace('’', '\'').replace('‘', '\'')
        for (m in SENTENCE_TOKEN.findAll(text)) {
            val t = m.value
            when {
                t[0] == '.' || t[0] == '!' || t[0] == '?' || t[0] == '\n' -> out.clear()
                t[0].isDigit() -> out += null
                else -> out += t
            }
        }
        // The text may begin mid-sentence: its first word may be cut, and older words matter least anyway.
        return if (out.size > MAX_SENTENCE_WORDS) out.subList(out.size - MAX_SENTENCE_WORDS, out.size) else out
    }

    /** The most words of a sentence the next-word model reads. */
    const val MAX_SENTENCE_WORDS = 24

    fun contextId(word: String, dictionary: Dictionary, lm: NgramModel): Int = when (word) {
        SENTENCE_START -> NgramModel.SENTENCE_START
        "" -> NgramModel.UNKNOWN
        else -> dictionary.indexOfLower(word).let { if (it < 0) NgramModel.UNKNOWN else lm.contextOf(it) }
    }

    /** Copies the casing of [template] onto [word]: ALL CAPS, Capitalised, or the dictionary's own casing. */
    fun matchCase(template: String, word: String): String = when {
        template.length > 1 && template.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
        template.firstOrNull()?.isUpperCase() == true -> word.replaceFirstChar { it.uppercaseChar() }
        else -> word
    }

    /** Whether position [at] in [text] starts a word: the field start, or after whitespace or an opening bracket. */
    fun atWordStart(text: CharSequence, at: Int): Boolean {
        if (at <= 0) return true
        val c = text[at - 1]
        return c.isWhitespace() || c == '(' || c == '[' || c == '{' || c == '<'
    }

    private const val SKIPPED = ",;:\"()[]{}*_-“”"
}
