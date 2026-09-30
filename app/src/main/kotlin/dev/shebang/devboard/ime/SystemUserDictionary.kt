package dev.shebang.devboard.ime

import android.content.Context
import android.provider.UserDictionary
import android.util.Log

/**
 * Android's own user dictionary: the words a person has added by hand in the system settings. Since
 * Android 6 only the current keyboard (and spell checker) may read it, so this works from the keyboard's
 * process. Never written to.
 */
object SystemUserDictionary {
    class Word(val word: String, /** 1..255, higher is more frequent. */ val frequency: Int)

    fun read(context: Context): List<Word> {
        return try {
            context.contentResolver.query(
                UserDictionary.Words.CONTENT_URI,
                arrayOf(UserDictionary.Words.WORD, UserDictionary.Words.FREQUENCY, UserDictionary.Words.LOCALE),
                null, null, null,
            )?.use { c ->
                val out = ArrayList<Word>()
                val wi = c.getColumnIndex(UserDictionary.Words.WORD)
                val fi = c.getColumnIndex(UserDictionary.Words.FREQUENCY)
                val li = c.getColumnIndex(UserDictionary.Words.LOCALE)
                while (c.moveToNext()) {
                    val word = if (wi >= 0) c.getString(wi) else null
                    val locale = if (li >= 0) c.getString(li) else null
                    if (word.isNullOrBlank() || (locale != null && !locale.startsWith("en"))) continue
                    out.add(Word(word.trim(), if (fi >= 0) c.getInt(fi) else 128))
                }
                out
            } ?: emptyList()
        } catch (e: SecurityException) {
            // Not the current keyboard (for example during setup): no access, nothing to add.
            Log.i("DevBoard", "system user dictionary not readable: ${e.message}")
            emptyList()
        } catch (e: RuntimeException) {
            Log.w("DevBoard", "system user dictionary query failed", e)
            emptyList()
        }
    }
}
