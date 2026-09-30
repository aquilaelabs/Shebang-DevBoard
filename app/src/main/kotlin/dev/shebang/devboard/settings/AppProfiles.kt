package dev.shebang.devboard.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * What the keyboard remembers per app, on the device: the apps it has been used in (newest first, for the
 * bar editor's app list) and the mode (text or code) it was last in for each, so an app reopens in it.
 * Package names only; nothing typed. Each app's own terminal bar lives in [Settings.appBars].
 */
class AppProfiles(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Apps the keyboard was used in, newest first. */
    fun seenApps(): List<String> = prefs.getString(KEY_SEEN, null)?.split('\n')?.filter { it.isNotEmpty() }.orEmpty()

    fun noteApp(pkg: String) {
        if (pkg.isEmpty()) return
        val list = seenApps()
        if (list.firstOrNull() == pkg) return
        val next = (listOf(pkg) + list.filter { it != pkg }).take(MAX_APPS)
        prefs.edit().putString(KEY_SEEN, next.joinToString("\n")).apply()
    }

    /** The mode last used in [pkg]: [CODE] or [TEXT], or null when the app has none remembered. */
    fun modeFor(pkg: String): String? = prefs.getString(KEY_MODE + pkg, null)

    fun setMode(pkg: String, mode: String) {
        if (pkg.isEmpty()) return
        prefs.edit().putString(KEY_MODE + pkg, mode).apply()
    }

    companion object {
        const val TEXT = "text"
        const val CODE = "code"
        private const val FILE = "app_profiles"
        private const val KEY_SEEN = "seen"
        private const val KEY_MODE = "mode:"
        private const val MAX_APPS = 30
    }
}
