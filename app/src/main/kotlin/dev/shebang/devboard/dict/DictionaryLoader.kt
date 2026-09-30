package dev.shebang.devboard.dict

import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Loads the bundled word list once, off the main thread. The keyboard shows immediately;
 * suggestions and glide start working as soon as [dictionary] becomes non-null.
 */
class DictionaryLoader(private val context: Context) {
    private val ref = AtomicReference<Dictionary?>(null)
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)
    private val listeners = mutableListOf<(Dictionary) -> Unit>()

    val dictionary: Dictionary? get() = ref.get()

    /** Starts loading if not already started; [onLoaded] is called on the loader thread. */
    fun ensureLoading(onLoaded: ((Dictionary) -> Unit)? = null) {
        onLoaded?.let { synchronized(listeners) { listeners.add(it) } }
        val existing = ref.get()
        if (existing != null) {
            onLoaded?.invoke(existing)
            return
        }
        if (!started.compareAndSet(false, true)) return
        Executors.newSingleThreadExecutor { r -> Thread(r, "devboard-dict").apply { isDaemon = true } }.execute {
            val dict = context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).useLines { Dictionary.parse(it) }
            ref.set(dict)
            val ls = synchronized(listeners) { listeners.toList().also { listeners.clear() } }
            ls.forEach { it(dict) }
        }
    }

    companion object {
        const val ASSET = "dict/en_words.txt"
    }
}
