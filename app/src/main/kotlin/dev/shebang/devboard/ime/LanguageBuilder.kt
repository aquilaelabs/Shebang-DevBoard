package dev.shebang.devboard.ime

import dev.shebang.devboard.dict.Dictionary
import dev.shebang.devboard.dict.NgramData
import dev.shebang.devboard.dict.NgramModel
import dev.shebang.devboard.dict.PersonalSnapshot
import dev.shebang.devboard.dict.PersonalWord
import dev.shebang.devboard.dict.PersonalWords
import dev.shebang.devboard.dict.Suggester
import dev.shebang.devboard.glide.GlideLanguage

/** Everything the keyboard knows about words for one build: dictionary, model, glide tree, suggestions. */
class LanguageBundle(
    val dictionary: Dictionary,
    val lm: NgramModel,
    val glide: GlideLanguage,
    val suggester: Suggester,
    /** [PersonalWords.vocabularyVersion] and [PersonalWords.countsVersion] this build reflects. */
    val vocabularyVersion: Int,
    val countsVersion: Int,
    val systemWords: Int,
    /** [dev.shebang.devboard.dict.RemovedWords.version] this build reflects. */
    val removedVersion: Int = 0,
)

/**
 * Combines the bundled word list and n-gram data with the user's learned words and Android's user dictionary.
 * Learned words the dictionary lacks (once used [PersonalWords.NEW_WORD_THRESHOLD] times) and system words
 * join the dictionary, so they can be suggested and glided. Pure: safe to run on any background thread.
 */
object LanguageBuilder {
    /** Tier given to words the bundled list lacks: shown in suggestions like a mid-frequency word. */
    const val PERSONAL_TIER = 35

    fun build(
        base: Dictionary,
        data: NgramData,
        personal: PersonalSnapshot,
        system: List<SystemUserDictionary.Word>,
        vocabularyVersion: Int,
        countsVersion: Int,
        glideModel: dev.shebang.devboard.glide.GlideModel? = null,
        nextWord: dev.shebang.devboard.dict.NextWordModel? = null,
        removed: Set<String> = emptySet(),
        removedVersion: Int = 0,
    ): LanguageBundle {
        // Words the user removed from the built-in list go, and learned words do not bring them back (Android's
        // personal dictionary still can: that one is the user's own list).
        val base = base.withoutWords(removed)
        val removedLower = removed.mapTo(HashSet()) { it.lowercase() }
        val extra = ArrayList<Pair<String, Int>>()
        for (w in personal.words) if (w.known && base.indexOfLower(w.lower) < 0 && w.lower !in removedLower) extra.add(w.display to PERSONAL_TIER)
        val systemWords = system.filter { PersonalWords.isLearnable(it.word) }
        for (s in systemWords) extra.add(s.word to PERSONAL_TIER)
        val dictionary = base.withExtraWords(extra)

        // System words count as a few uses, by their frequency; learned counts as they are.
        val known = personal.words.filter { (it.known || base.indexOfLower(it.lower) >= 0) && it.lower !in removedLower }
        val withSystem = if (systemWords.isEmpty()) known else known + systemWords.map {
            PersonalWord(it.word.lowercase(), it.word, 1 + it.frequency.coerceIn(0, 255) / 64, known = true, system = true)
        }
        val snapshot = PersonalSnapshot(withSystem, personal.pairs, personal.totalTokens + withSystem.count { it.system })
        val lm = NgramModel.build(dictionary, data, snapshot)
        val counts = IntArray(dictionary.size)
        for (w in withSystem) {
            val i = dictionary.indexOfLower(w.lower)
            if (i >= 0) counts[i] += w.count
        }
        return LanguageBundle(
            dictionary, lm, GlideLanguage.build(dictionary, lm, glideModel, nextWord), Suggester(dictionary, counts, FloatArray(dictionary.size) { kotlin.math.exp(-lm.unigramCost(it).toDouble()).toFloat() }, lm),
            vocabularyVersion, countsVersion, systemWords.size, removedVersion,
        )
    }
}
