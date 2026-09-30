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
            theme = p[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.theme,
            dynamicColor = p[Keys.DYNAMIC_COLOR] ?: d.dynamicColor,
            heightScale = (p[Keys.HEIGHT_SCALE] ?: d.heightScale).coerceIn(0.7f, 1.4f),
            numberRow = p[Keys.NUMBER_ROW] ?: d.numberRow,
            keyPreview = p[Keys.KEY_PREVIEW] ?: d.keyPreview,
            haptics = p[Keys.HAPTICS] ?: d.haptics,
            hapticStrength = (p[Keys.HAPTIC_STRENGTH] ?: d.hapticStrength).coerceIn(1, 3),
            keySounds = p[Keys.KEY_SOUNDS] ?: d.keySounds,
            glide = p[Keys.GLIDE] ?: d.glide,
            glideTrail = p[Keys.GLIDE_TRAIL] ?: d.glideTrail,
            phraseGlide = p[Keys.PHRASE_GLIDE] ?: d.phraseGlide,
            reviseGlide = p[Keys.REVISE_GLIDE] ?: d.reviseGlide,
            autocorrect = p[Keys.AUTOCORRECT] ?: d.autocorrect,
            autoCaps = p[Keys.AUTO_CAPS] ?: d.autoCaps,
            doubleSpacePeriod = p[Keys.DOUBLE_SPACE_PERIOD] ?: d.doubleSpacePeriod,
            stripMode = p[Keys.STRIP_MODE]?.let { runCatching { StripMode.valueOf(it) }.getOrNull() } ?: d.stripMode,
            barJson = p[Keys.BAR_JSON],
        )
    }

    private fun write(p: androidx.datastore.preferences.core.MutablePreferences, s: Settings) {
        p[Keys.THEME] = s.theme.name
        p[Keys.DYNAMIC_COLOR] = s.dynamicColor
        p[Keys.HEIGHT_SCALE] = s.heightScale
        p[Keys.NUMBER_ROW] = s.numberRow
        p[Keys.KEY_PREVIEW] = s.keyPreview
        p[Keys.HAPTICS] = s.haptics
        p[Keys.HAPTIC_STRENGTH] = s.hapticStrength
        p[Keys.KEY_SOUNDS] = s.keySounds
        p[Keys.GLIDE] = s.glide
        p[Keys.GLIDE_TRAIL] = s.glideTrail
        p[Keys.PHRASE_GLIDE] = s.phraseGlide
        p[Keys.REVISE_GLIDE] = s.reviseGlide
        p[Keys.AUTOCORRECT] = s.autocorrect
        p[Keys.AUTO_CAPS] = s.autoCaps
        p[Keys.DOUBLE_SPACE_PERIOD] = s.doubleSpacePeriod
        p[Keys.STRIP_MODE] = s.stripMode.name
        if (s.barJson == null) p.remove(Keys.BAR_JSON) else p[Keys.BAR_JSON] = s.barJson
    }

    private object Keys {
        val THEME = stringPreferencesKey("theme")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val HEIGHT_SCALE = floatPreferencesKey("height_scale")
        val NUMBER_ROW = booleanPreferencesKey("number_row")
        val KEY_PREVIEW = booleanPreferencesKey("key_preview")
        val HAPTICS = booleanPreferencesKey("haptics")
        val HAPTIC_STRENGTH = intPreferencesKey("haptic_strength")
        val KEY_SOUNDS = booleanPreferencesKey("key_sounds")
        val GLIDE = booleanPreferencesKey("glide")
        val GLIDE_TRAIL = booleanPreferencesKey("glide_trail")
        val PHRASE_GLIDE = booleanPreferencesKey("phrase_glide")
        val REVISE_GLIDE = booleanPreferencesKey("revise_glide")
        val AUTOCORRECT = booleanPreferencesKey("autocorrect")
        val AUTO_CAPS = booleanPreferencesKey("auto_caps")
        val DOUBLE_SPACE_PERIOD = booleanPreferencesKey("double_space_period")
        val STRIP_MODE = stringPreferencesKey("strip_mode")
        val BAR_JSON = stringPreferencesKey("bar_json")
    }

    companion object {
        @Volatile private var instance: SettingsRepository? = null

        fun get(context: Context): SettingsRepository =
            instance ?: synchronized(this) { instance ?: SettingsRepository(context).also { instance = it } }
    }
}
