package dev.shebang.devboard.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "devboard_settings")

/** DataStore-backed settings. One instance per process is fine; DataStore is a singleton per file. */
class SettingsRepository(context: Context) {
    private val store = context.applicationContext.dataStore

    val settings: Flow<Settings> = store.data.map { p -> fromPrefs(p) }

    suspend fun update(transform: (Settings) -> Settings) {
        store.edit { p ->
            val next = transform(fromPrefs(p))
            write(p, next)
        }
    }

    private fun fromPrefs(p: Preferences): Settings {
        val d = Settings()
        return Settings(
            palette = p[Keys.PALETTE] ?: d.palette,
            heightScale = (p[Keys.HEIGHT_SCALE] ?: d.heightScale).coerceIn(0.7f, 1.4f),
            numberRow = p[Keys.NUMBER_ROW] ?: d.numberRow,
            keyPreview = p[Keys.KEY_PREVIEW] ?: d.keyPreview,
            haptics = p[Keys.HAPTICS] ?: d.haptics,
            hapticStrength = (p[Keys.HAPTIC_STRENGTH] ?: d.hapticStrength).coerceIn(1, 3),
            keySounds = p[Keys.KEY_SOUNDS] ?: d.keySounds,
            glide = p[Keys.GLIDE] ?: d.glide,
            glideTrail = p[Keys.GLIDE_TRAIL] ?: d.glideTrail,
            phraseGlide = p[Keys.PHRASE_GLIDE] ?: d.phraseGlide,
            learnWords = p[Keys.LEARN_WORDS] ?: d.learnWords,
            rememberEmails = p[Keys.REMEMBER_EMAILS] ?: d.rememberEmails,
            adaptGlide = p[Keys.ADAPT_GLIDE] ?: d.adaptGlide,
            adaptTaps = p[Keys.ADAPT_TAPS] ?: d.adaptTaps,
            autocorrect = p[Keys.AUTOCORRECT] ?: d.autocorrect,
            autoCaps = p[Keys.AUTO_CAPS] ?: d.autoCaps,
            doubleSpacePeriod = p[Keys.DOUBLE_SPACE_PERIOD] ?: d.doubleSpacePeriod,
            pairBrackets = p[Keys.PAIR_BRACKETS] ?: d.pairBrackets,
            nextWord = p[Keys.NEXT_WORD] ?: d.nextWord,
            tidyDictation = p[Keys.TIDY_DICTATION] ?: d.tidyDictation,
            fixPreviousGlide = p[Keys.FIX_PREVIOUS_GLIDE] ?: d.fixPreviousGlide,
            stripMode = p[Keys.STRIP_MODE]?.let { runCatching { StripMode.valueOf(it) }.getOrNull() } ?: d.stripMode,
            barJson = p[Keys.BAR_JSON],
            appBars = p[Keys.APP_BARS]?.let { runCatching { appBarsJson.decodeFromString<Map<String, String>>(it) }.getOrNull() } ?: emptyMap(),
        )
    }

    private fun write(p: androidx.datastore.preferences.core.MutablePreferences, s: Settings) {
        p[Keys.PALETTE] = s.palette
        p[Keys.HEIGHT_SCALE] = s.heightScale
        p[Keys.NUMBER_ROW] = s.numberRow
        p[Keys.KEY_PREVIEW] = s.keyPreview
        p[Keys.HAPTICS] = s.haptics
        p[Keys.HAPTIC_STRENGTH] = s.hapticStrength
        p[Keys.KEY_SOUNDS] = s.keySounds
        p[Keys.GLIDE] = s.glide
        p[Keys.GLIDE_TRAIL] = s.glideTrail
        p[Keys.PHRASE_GLIDE] = s.phraseGlide
        p[Keys.LEARN_WORDS] = s.learnWords
        p[Keys.REMEMBER_EMAILS] = s.rememberEmails
        p[Keys.ADAPT_GLIDE] = s.adaptGlide
        p[Keys.ADAPT_TAPS] = s.adaptTaps
        p[Keys.AUTOCORRECT] = s.autocorrect
        p[Keys.AUTO_CAPS] = s.autoCaps
        p[Keys.DOUBLE_SPACE_PERIOD] = s.doubleSpacePeriod
        p[Keys.PAIR_BRACKETS] = s.pairBrackets
        p[Keys.NEXT_WORD] = s.nextWord
        p[Keys.TIDY_DICTATION] = s.tidyDictation
        p[Keys.FIX_PREVIOUS_GLIDE] = s.fixPreviousGlide
        p[Keys.STRIP_MODE] = s.stripMode.name
        if (s.barJson == null) p.remove(Keys.BAR_JSON) else p[Keys.BAR_JSON] = s.barJson
        if (s.appBars.isEmpty()) p.remove(Keys.APP_BARS) else p[Keys.APP_BARS] = appBarsJson.encodeToString(s.appBars)
    }

    private object Keys {
        val PALETTE = stringPreferencesKey("keyboard_theme")
        val HEIGHT_SCALE = floatPreferencesKey("height_scale")
        val NUMBER_ROW = booleanPreferencesKey("number_row")
        val KEY_PREVIEW = booleanPreferencesKey("key_preview")
        val HAPTICS = booleanPreferencesKey("haptics")
        val HAPTIC_STRENGTH = intPreferencesKey("haptic_strength")
        val KEY_SOUNDS = booleanPreferencesKey("key_sounds")
        val GLIDE = booleanPreferencesKey("glide")
        val GLIDE_TRAIL = booleanPreferencesKey("glide_trail")
        val PHRASE_GLIDE = booleanPreferencesKey("phrase_glide")
        val LEARN_WORDS = booleanPreferencesKey("learn_words")
        val REMEMBER_EMAILS = booleanPreferencesKey("remember_emails")
        val ADAPT_GLIDE = booleanPreferencesKey("adapt_glide")
        val ADAPT_TAPS = booleanPreferencesKey("adapt_taps")
        val AUTOCORRECT = booleanPreferencesKey("autocorrect")
        val AUTO_CAPS = booleanPreferencesKey("auto_caps")
        val DOUBLE_SPACE_PERIOD = booleanPreferencesKey("double_space_period")
        val PAIR_BRACKETS = booleanPreferencesKey("pair_brackets")
        val NEXT_WORD = booleanPreferencesKey("next_word")
        val TIDY_DICTATION = booleanPreferencesKey("tidy_dictation")
        val FIX_PREVIOUS_GLIDE = booleanPreferencesKey("fix_previous_glide")
        val STRIP_MODE = stringPreferencesKey("strip_mode")
        val BAR_JSON = stringPreferencesKey("bar_json")
        val APP_BARS = stringPreferencesKey("bar_json_apps")
    }

    companion object {
        @Volatile private var instance: SettingsRepository? = null

        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) { instance ?: SettingsRepository(context).also { instance = it } }
    }
}

private val appBarsJson = Json { ignoreUnknownKeys = true }
