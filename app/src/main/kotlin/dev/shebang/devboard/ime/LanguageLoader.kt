package dev.shebang.devboard.ime

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.shebang.devboard.dict.Dictionary
import dev.shebang.devboard.dict.NgramModel
import dev.shebang.devboard.glide.GlideLanguage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loads the word list, then the n-gram model and the glide trie, once, on a background thread. The keyboard
 * appears immediately; suggestions start when the dictionary arrives and glide when the language does.
 * Callbacks run on the loader thread.
 */
class LanguageLoader(
    private val context: Context,
    private val onDictionary: (Dictionary) -> Unit,
    private val onGlideLanguage: (GlideLanguage) -> Unit,
) {
    private val started = AtomicBoolean(false)

    @Volatile
    var dictionary: Dictionary? = null
        private set

    @Volatile
    var glide: GlideLanguage? = null
        private set

    fun ensureLoading() {
        if (!started.compareAndSet(false, true)) return
        Executors.newSingleThreadExecutor { r -> Thread(r, "devboard-language").apply { isDaemon = true } }.execute {
            val t0 = SystemClock.elapsedRealtime()
            val dict = context.assets.open(DICTIONARY_ASSET).bufferedReader(Charsets.UTF_8).useLines { Dictionary.parse(it) }
            dictionary = dict
            val t1 = SystemClock.elapsedRealtime()
            onDictionary(dict)
            val lm = context.assets.open(NgramModel.ASSET).use { NgramModel.load(it, dict) }
            val lang = GlideLanguage.build(dict, lm)
            glide = lang
            val t2 = SystemClock.elapsedRealtime()
            Log.i(TAG, "dictionary ${dict.size} words in ${t1 - t0} ms; n-grams and trie (${lang.trie.nodeCount} nodes) in ${t2 - t1} ms")
            onGlideLanguage(lang)
        }
    }

    companion object {
        const val DICTIONARY_ASSET = "dict/en_words.txt"
        private const val TAG = "DevBoard"
    }
}
