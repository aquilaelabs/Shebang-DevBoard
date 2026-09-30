package dev.shebang.devboard.view

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import dev.shebang.devboard.settings.Settings
import dev.shebang.devboard.settings.ThemeMode

/** Colours for the keyboard. Built once per settings/configuration change; drawing reads plain ints. */
class KeyboardTheme(
    val isDark: Boolean,
    val background: Int,
    val key: Int,
    val keyFunctional: Int,
    val keyPressed: Int,
    val keyText: Int,
    val keyTextSecondary: Int,
    val accent: Int,
    val onAccent: Int,
    val popup: Int,
    val popupText: Int,
    val stripText: Int,
    val trail: Int,
    val modifierActive: Int,
    val modifierLocked: Int,
) {
    companion object {
        fun resolveDark(context: Context, mode: ThemeMode): Boolean = when (mode) {
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
            ThemeMode.SYSTEM -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }

        fun build(context: Context, settings: Settings): KeyboardTheme {
            val dark = resolveDark(context, settings.theme)
            val dynamic = settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            return if (dynamic) dynamicTheme(context, dark) else staticTheme(dark)
        }

        private fun c(context: Context, id: Int): Int = ContextCompat.getColor(context, id)

        /** Material 3 dynamic colour from the system palette (neutral1 for surfaces, accent1 for the accent). */
        @RequiresApi(Build.VERSION_CODES.S)
        private fun dynamicTheme(context: Context, dark: Boolean): KeyboardTheme {
            return if (dark) KeyboardTheme(
                isDark = true,
                background = c(context, android.R.color.system_neutral1_900),
                key = c(context, android.R.color.system_neutral1_700),
                keyFunctional = c(context, android.R.color.system_neutral2_800),
                keyPressed = c(context, android.R.color.system_neutral1_500),
                keyText = c(context, android.R.color.system_neutral1_50),
                keyTextSecondary = c(context, android.R.color.system_neutral2_300),
                accent = c(context, android.R.color.system_accent1_300),
                onAccent = c(context, android.R.color.system_accent1_900),
                popup = c(context, android.R.color.system_neutral1_600),
                popupText = c(context, android.R.color.system_neutral1_50),
                stripText = c(context, android.R.color.system_neutral1_100),
                trail = c(context, android.R.color.system_accent1_200),
                modifierActive = c(context, android.R.color.system_accent2_700),
                modifierLocked = c(context, android.R.color.system_accent1_400),
            ) else KeyboardTheme(
                isDark = false,
                background = c(context, android.R.color.system_neutral2_100),
                key = c(context, android.R.color.system_neutral1_10),
                keyFunctional = c(context, android.R.color.system_neutral2_200),
                keyPressed = c(context, android.R.color.system_neutral2_300),
                keyText = c(context, android.R.color.system_neutral1_900),
                keyTextSecondary = c(context, android.R.color.system_neutral2_600),
                accent = c(context, android.R.color.system_accent1_600),
                onAccent = c(context, android.R.color.system_accent1_10),
                popup = c(context, android.R.color.system_neutral1_0),
                popupText = c(context, android.R.color.system_neutral1_900),
                stripText = c(context, android.R.color.system_neutral1_800),
                trail = c(context, android.R.color.system_accent1_500),
                modifierActive = c(context, android.R.color.system_accent2_200),
                modifierLocked = c(context, android.R.color.system_accent1_300),
            )
        }

        /** Original fallback palette (pre-Android 12 or dynamic colour off). */
        private fun staticTheme(dark: Boolean): KeyboardTheme = if (dark) KeyboardTheme(
            isDark = true,
            background = 0xFF1B1F2A.toInt(),
            key = 0xFF2E3440.toInt(),
            keyFunctional = 0xFF252B36.toInt(),
            keyPressed = 0xFF4A5366.toInt(),
            keyText = 0xFFECEFF4.toInt(),
            keyTextSecondary = 0xFF9AA3B5.toInt(),
            accent = 0xFF7BE0A6.toInt(),
            onAccent = 0xFF0B2A1A.toInt(),
            popup = 0xFF3B4252.toInt(),
            popupText = 0xFFECEFF4.toInt(),
            stripText = 0xFFE5E9F0.toInt(),
            trail = 0xFF7BE0A6.toInt(),
            modifierActive = 0xFF3F5A4B.toInt(),
            modifierLocked = 0xFF5FBF8A.toInt(),
        ) else KeyboardTheme(
            isDark = false,
            background = 0xFFE6E9F0.toInt(),
            key = 0xFFFFFFFF.toInt(),
            keyFunctional = 0xFFCFD5E1.toInt(),
            keyPressed = 0xFFB8C0D0.toInt(),
            keyText = 0xFF1B1F2A.toInt(),
            keyTextSecondary = 0xFF5B6475.toInt(),
            accent = 0xFF1E8E5A.toInt(),
            onAccent = 0xFFFFFFFF.toInt(),
            popup = 0xFFFFFFFF.toInt(),
            popupText = 0xFF1B1F2A.toInt(),
            stripText = 0xFF2A2F3A.toInt(),
            trail = 0xFF1E8E5A.toInt(),
            modifierActive = 0xFFBFE8D2.toInt(),
            modifierLocked = 0xFF57C48B.toInt(),
        )
    }
}
