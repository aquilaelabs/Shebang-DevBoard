package dev.shebang.devboard.ime

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.shebang.devboard.dict.Dictionary
import dev.shebang.devboard.dict.NextWordModel
import dev.shebang.devboard.dict.NgramData
import dev.shebang.devboard.dict.PersonalSnapshot
import dev.shebang.devboard.dict.PersonalWords
import dev.shebang.devboard.dict.Suggester
import dev.shebang.devboard.dict.WordPacks
import dev.shebang.devboard.glide.GlideModel
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loads the word list, then the n-gram data, the user's learned words and Android's user dictionary, and
 * builds a [LanguageBundle], all on one background thread. The keyboard appears immediately: suggestions
 * start when the word list arrives ([onDictionary]), glide when the bundle does ([onLanguage]). [rebuild]
 * makes a new bundle when the personal vocabulary changed. Callbacks run on the loader thread.
 */
class LanguageLoader(
    private val context: Context,
    private val personal: PersonalWords,
    private val onDictionary: (Suggester) -> Unit,
    private val onLanguage: (LanguageBundle) -> Unit,
) {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "devboard-language").apply { isDaemon = true } }
    private val started = AtomicBoolean(false)
    /** Built-in words the user removed (Settings > Learning and privacy > Built-in dictionary). */
    val removedWords = dev.shebang.devboard.dict.RemovedWords.get(context.filesDir)
    /** Which word packs are on, and the lists the user imported (Settings > Dictionaries). */
    val packs = dev.shebang.devboard.dict.WordPackStore.get(context.filesDir)
    /** [packs]' version the current word list reflects. */
    @Volatile
    var packsVersion = -1
        private set
    private var regular: Dictionary? = null
    private val packCache = HashMap<Int, Dictionary>()
    private val rebuildQueued = AtomicBoolean(false)
    private var base: Dictionary? = null
    private var data: NgramData? = null
    private var glideModel: GlideModel? = null
    private var nextWord: NextWordModel? = null

    /** Settings that decide what the build includes, read on the loader thread. */
    @Volatile
    var learnWords = true

    fun ensureLoading() {
        if (!started.compareAndSet(false, true)) return
        executor.execute {
            val t0 = SystemClock.elapsedRealtime()
            val dict = loadBase()
            base = dict
            val t1 = SystemClock.elapsedRealtime()
            onDictionary(Suggester(dict.withoutWords(removedWords.snapshot())))
            data = context.assets.open(dev.shebang.devboard.dict.NgramModel.ASSET).use { NgramData.load(it) }
            glideModel = runCatching { context.assets.open(GlideModel.ASSET).use { GlideModel.load(it) } }
                .onFailure { Log.w(TAG, "no glide model", it) }.getOrNull()
            nextWord = runCatching { context.assets.open(NextWordModel.ASSET).use { NextWordModel.load(it) } }
                .onFailure { Log.w(TAG, "no next-word model", it) }.getOrNull()
            personal.load()
            buildNow("load", t1)
            Log.i(TAG, "dictionary ${dict.size} words in ${t1 - t0} ms")
        }
    }

    /**
     * The regular words merged with the packs that are on and the user's own lists that are on (their words the
     * rest lacks). Parsed packs are kept, so turning one on or off again is quick. On the loader thread.
     */
    private fun loadBase(): Dictionary {
        packsVersion = packs.version
        val reg = regular ?: parseAsset(WordPacks.REGULAR_ASSET, WordPacks.REGULAR).also { regular = it }
        val parts = arrayListOf(reg)
        for (p in WordPacks.builtIn) {
            if (!packs.isEnabled(p.key)) continue
            val d = packCache[p.id] ?: runCatching { parseAsset(p.asset, p.id) }.onFailure { Log.w(TAG, "no ${p.key} pack", it) }.getOrNull()
            if (d != null) {
                packCache[p.id] = d
                parts.add(d)
            }
        }
        var merged = Dictionary.merge(parts)
        val own = packs.enabledImportedTiers().filter { merged.indexOfLower(it.first.lowercase()) < 0 }.distinctBy { it.first.lowercase() }
        if (own.isNotEmpty()) {
            merged = Dictionary.merge(listOf(merged, Dictionary.parse(own.asSequence().map { (w, t) -> "$w\t$t" }, WordPacks.IMPORTED)))
        }
        return merged
    }

    private fun parseAsset(asset: String, pack: Int): Dictionary =
        context.assets.open(asset).bufferedReader(Charsets.UTF_8).useLines { Dictionary.parse(it, pack) }

    /** The word packs changed: the word list is put together again, then the bundle rebuilt. */
    fun reloadBase() {
        if (!started.get()) return
        executor.execute {
            val t0 = SystemClock.elapsedRealtime()
            base = loadBase()
            base?.let { onDictionary(Suggester(it.withoutWords(removedWords.snapshot()))) }
            buildNow("packs", t0)
        }
    }

    /** Builds a new bundle from the current learned words; coalesces requests made while one is queued. */
    fun rebuild() {
        if (!started.get()) return
        if (!rebuildQueued.compareAndSet(false, true)) return
        executor.execute {
            rebuildQueued.set(false)
            buildNow("rebuild", SystemClock.elapsedRealtime())
        }
    }

    private fun buildNow(why: String, t0: Long) {
        val b = base ?: return
        val d = data ?: return
        val vocab = personal.vocabularyVersion
        val counts = personal.countsVersion
        val snapshot = if (learnWords) personal.snapshot() else PersonalSnapshot.EMPTY
        val system = SystemUserDictionary.read(context)
        val removedVersion = removedWords.version
        val bundle = LanguageBuilder.build(b, d, snapshot, system, vocab, counts, glideModel, nextWord, removedWords.snapshot(), removedVersion, packsVersion)
        Log.i(TAG, "$why: ${bundle.dictionary.size} words (${bundle.dictionary.size - b.size} personal or system, " +
            "${bundle.systemWords} from the system dictionary), trie ${bundle.glide.trie.nodeCount} nodes in " +
            "${SystemClock.elapsedRealtime() - t0} ms")
        onLanguage(bundle)
    }

    companion object {
        /** The regular words; the packs are listed in [WordPacks]. */
        const val DICTIONARY_ASSET = WordPacks.REGULAR_ASSET
        /** Tier of a word from the user's own lists: offered and glidable, never autocorrected to. */
        /** Where an imported word without a frequency starts ([WordPackStore.IMPORTED_TIER]). */
        const val IMPORTED_TIER = dev.shebang.devboard.dict.WordPackStore.IMPORTED_TIER
        private const val TAG = "DevBoard"
    }
}
