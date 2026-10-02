package dev.shebang.devboard.ime

/**
 * Tidies a piece of dictation by rules, the way people's speech needs it: hesitations ("um", "uh") go,
 * stutters and repeated phrases are said once, and a spoken correction replaces what it corrects
 * ("on Tuesday, no wait, on Wednesday" is "on Wednesday"). "Scratch that" drops the sentence before it, or,
 * said at the start of a piece, asks for the piece before to be taken back ([Result.dropPrevious]).
 * Nothing is reworded beyond that: no model, no guessing at meaning (R16).
 */
object DictationCleanup {
    class Result(val text: String, val dropPrevious: Boolean)

    private val FILLERS = setOf("um", "umm", "uh", "uhh", "er", "erm", "ah", "hmm", "mm", "mhm")
    /** Spoken corrections: what follows replaces what came just before. */
    private val CORRECTIONS = listOf("no wait", "wait no", "no sorry", "sorry", "i mean", "or rather", "rather")
    private val SCRATCH = listOf("scratch that", "delete that", "strike that")

    fun tidy(raw: String): Result {
        var tokens = tokenize(raw)
        var dropPrevious = false
        // Scratch that: keep only what follows it; at the very start it takes back the piece before.
        while (true) {
            val at = findPhrase(tokens, SCRATCH) ?: break
            val (start, end) = at
            if (tokens.subList(0, start).none { it.isWord }) dropPrevious = true
            tokens = afterSentenceStart(tokens, start) + tokens.drop(end)
        }
        tokens = dropFillers(tokens)
        tokens = applyCorrections(tokens)
        tokens = dropRepeats(tokens)
        return Result(render(tokens, startsSentence = raw.trimStart().firstOrNull()?.isUpperCase() == true), dropPrevious)
    }

    /** Hesitations go with the commas that set them off: "at, uh, three" is "at three". */
    private fun dropFillers(input: List<Token>): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        while (i < input.size) {
            val t = input[i]
            if (t.isWord && t.lower in FILLERS) {
                if (out.lastOrNull()?.text == ",") out.removeAt(out.size - 1)
                i++
                if (i < input.size && input[i].text == ",") i++
                continue
            }
            out += t
            i++
        }
        return out
    }

    /** A word, or punctuation that came after one. */
    private class Token(val text: String) {
        val isWord = text.any { it.isLetterOrDigit() }
        val lower = text.lowercase().trim('\'', '’')
    }

    private fun tokenize(s: String): List<Token> = Regex("[\\p{L}\\p{N}'’]+|[.,!?;:]").findAll(s).map { Token(it.value) }.toList()

    private fun words(tokens: List<Token>) = tokens.withIndex().filter { it.value.isWord }

    /** Where one of [phrases] is said (token range), the first one found from the start. */
    private fun findPhrase(tokens: List<Token>, phrases: List<String>): Pair<Int, Int>? {
        val ws = words(tokens)
        for (i in ws.indices) {
            for (p in phrases) {
                val parts = p.split(' ')
                if (i + parts.size > ws.size) continue
                if (parts.indices.all { ws[i + it].value.lower == parts[it] }) {
                    var end = ws[i + parts.size - 1].index + 1
                    // The punctuation that sets the phrase off goes with it.
                    while (end < tokens.size && !tokens[end].isWord && tokens[end].text in setOf(",", ".", "!", "?", ";", ":")) end++
                    return ws[i].index to end
                }
            }
        }
        return null
    }

    /** The tokens before [at] up to the start of their sentence: what "scratch that" leaves standing. */
    private fun afterSentenceStart(tokens: List<Token>, at: Int): List<Token> {
        var i = at - 1
        while (i >= 0 && tokens[i].text !in setOf(".", "!", "?")) i--
        return tokens.subList(0, i + 1)
    }

    /**
     * "X, no wait, Y": Y replaces the end of X. Where Y begins with a word X also ends near ("on Tuesday, no
     * wait, on Wednesday"), X is cut back to that word; otherwise Y's first word replaces X's last.
     */
    private fun applyCorrections(input: List<Token>): List<Token> {
        var tokens = input
        while (true) {
            val (start, end) = findCorrection(tokens) ?: return tokens
            val before = trimPunct(tokens.subList(0, start))
            val after = tokens.drop(end)
            val firstAfter = after.firstOrNull { it.isWord } ?: return before
            if (before.none { it.isWord }) return after
            val ws = words(before)
            val tail = ws.takeLast(6)
            val same = tail.lastOrNull { it.value.lower == firstAfter.lower }
            val cut = same?.index ?: ws.last().index
            tokens = before.subList(0, cut) + after
        }
    }

    /** A correction phrase set off as one: after a word, not at the very start. */
    private fun findCorrection(tokens: List<Token>): Pair<Int, Int>? {
        val at = findPhrase(tokens, CORRECTIONS) ?: return null
        // "Sorry" or "I mean" opening a sentence is not a correction of something before it.
        val prev = tokens.subList(0, at.first).lastOrNull()
        if (prev == null || prev.text in setOf(".", "!", "?")) return null
        // Without a comma or pause around it, "I mean it" is meant: only a phrase set off by punctuation counts,
        // except "no wait", which is never meant literally.
        val phrase = tokens.subList(at.first, at.second).filter { it.isWord }.joinToString(" ") { it.lower }
        val setOff = prev.text == "," || tokens.subList(at.first, at.second).any { !it.isWord }
        if (!setOff && phrase != "no wait" && phrase != "wait no") return null
        return at
    }

    private fun trimPunct(t: List<Token>): List<Token> {
        var e = t.size
        while (e > 0 && !t[e - 1].isWord) e--
        return t.subList(0, e)
    }

    /** "I I think" and "we should we should go": a word or phrase of up to three words said twice in a row. */
    private fun dropRepeats(input: List<Token>): List<Token> {
        val out = ArrayList<Token>()
        for (t in input) {
            out += t
            if (!t.isWord) continue
            for (n in 3 downTo 1) {
                val ws = out.withIndex().filter { it.value.isWord }
                if (ws.size < 2 * n) continue
                val first = ws.subList(ws.size - 2 * n, ws.size - n).map { it.value.lower }
                val second = ws.subList(ws.size - n, ws.size).map { it.value.lower }
                // "had had" and "that that" can be meant.
                if (first == second && !(n == 1 && first[0] in KEEP_DOUBLED)) {
                    // Drop the second saying, and any punctuation between the two.
                    val keepUpTo = ws[ws.size - n - 1].index
                    while (out.size > keepUpTo + 1) out.removeAt(out.size - 1)
                    break
                }
            }
        }
        return out
    }

    private val KEEP_DOUBLED = setOf("had", "that", "is", "do", "very", "so", "no", "bye", "ha", "knock")

    private fun render(tokens: List<Token>, startsSentence: Boolean): String {
        val sb = StringBuilder()
        var capital = startsSentence
        for (t in tokens) {
            if (t.isWord) {
                if (sb.isNotEmpty()) sb.append(' ')
                val w = if (capital) t.text.replaceFirstChar { it.uppercaseChar() } else t.text
                sb.append(w)
                capital = false
            } else {
                // No doubled or dangling punctuation ("word , ." becomes "word.").
                if (sb.isEmpty()) continue
                val last = sb.last()
                if (last in ".,!?;:") {
                    if (t.text in ".!?") sb.setCharAt(sb.length - 1, t.text[0])
                } else {
                    sb.append(t.text)
                }
                if (t.text in ".!?") capital = true
            }
        }
        return sb.toString()
    }
}
