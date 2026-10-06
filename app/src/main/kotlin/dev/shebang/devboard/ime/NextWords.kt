package dev.shebang.devboard.ime

import dev.shebang.devboard.dict.Dictionary
import dev.shebang.devboard.dict.NextWordModel
import dev.shebang.devboard.dict.NgramModel
import dev.shebang.devboard.dict.WordPredictions
import java.util.concurrent.Executor

/**
 * The strip's next-word predictions after a space: working them out off the main thread, the words offered
 * now, and whether the text has moved on from them. A collaborator of [TextInputController] (R27), which
 * decides when to predict and shows what comes back.
 */
internal class NextWords(private val background: Executor, private val postToMain: (Runnable) -> Unit) {
    /** The next-word network, this keyboard's own copy (used on [background]); null before it is loaded. */
    var network: NextWordModel? = null

    /** Next words the strip offers now; empty when it offers something else. */
    var offered: List<String> = emptyList()
        private set

    /** The text before the cursor the [offered] words were made for: once it is not, they are stale. */
    private var offeredBefore = ""
    private var generation = 0

    fun isOffered(word: String) = word in offered

    /** Whether the offered words no longer follow from the text: it changed under them, or text is selected. */
    fun stale(textBefore: String?, selection: Boolean) = offered.isNotEmpty() && (selection || textBefore != offeredBefore)

    /** Predictions still being worked out are not wanted (something else takes the strip). */
    fun cancel() {
        generation++
    }

    /** Nothing offered, and nothing on its way. */
    fun clear() {
        generation++
        offered = emptyList()
    }

    /**
     * Works out the [count] words most likely to follow [before] (the two words before the cursor, the corpus
     * model mixed with the user's own word pairs, and the [sentence] for the network), capitalised at a
     * sentence start, and hands them to [show] on the main thread unless something newer came first. [show]
     * says whether it showed them; only then are they [offered].
     */
    fun predict(
        model: Pair<Dictionary, NgramModel>,
        before: CharSequence,
        sentence: List<String?>,
        count: Int,
        show: (List<String>) -> Boolean,
    ) {
        val w1 = GlideText.contextWord(before)
        val w2 = GlideText.contextWord2(before)
        val network = network
        val gen = ++generation
        val beforeText = before.toString()
        background.execute {
            val (dictionary, lm) = model
            val c1 = GlideText.contextId(w1, dictionary, lm)
            val c2 = if (w2.isEmpty()) NgramModel.UNKNOWN else GlideText.contextId(w2, dictionary, lm)
            val start = w1 == GlideText.SENTENCE_START
            val words = WordPredictions.predict(dictionary, lm, network, sentence, c2, c1, count)
                .map { dictionary.words[it] }.map { if (start) it.replaceFirstChar { c -> c.uppercaseChar() } else it }
            postToMain {
                if (gen != generation || words.isEmpty()) return@postToMain
                if (!show(words)) return@postToMain
                offered = words
                offeredBefore = beforeText
            }
        }
    }
}
