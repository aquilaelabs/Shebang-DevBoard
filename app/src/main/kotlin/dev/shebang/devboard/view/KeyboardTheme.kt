package dev.shebang.devboard.view

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import dev.shebang.devboard.settings.Settings

/**
 * Colours for the keyboard, from the theme setting ([Palettes]). Built once per settings/configuration
 * change; drawing reads plain ints. The keycap edges (a deeper shade under each key's face) are derived.
 */
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
    /** The keycap edge under a character key's face, a function key's, and the accent key's. */
    val keyEdge: Int = edgeOf(key)
    val keyFunctionalEdge: Int = edgeOf(keyFunctional)
    val accentEdge: Int = edgeOf(accent)

    private fun edgeOf(face: Int): Int {
        // A deeper shade of the face: towards black, more so on light themes where a thin edge reads weaker.
        val k = if (isDark) 0.45f else 0.22f
        val r = ((face shr 16) and 0xFF) * (1 - k)
        val g = ((face shr 8) and 0xFF) * (1 - k)
        val b = (face and 0xFF) * (1 - k)
        return (0xFF shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
    }

    companion object {
        fun systemIsDark(context: Context): Boolean =
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

        /** The palette a theme id stands for now: Auto (and Wallpaper before Android 12) follow the system. */
        fun paletteFor(context: Context, id: String): Palettes.Palette? = when (id) {
            Palettes.AUTO -> Palettes.byId(if (systemIsDark(context)) Palettes.NIGHT else Palettes.DAY)
            Palettes.WALLPAPER -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) null else paletteFor(context, Palettes.AUTO)
            else -> Palettes.byId(id) ?: paletteFor(context, Palettes.AUTO)
        }

        fun build(context: Context, settings: Settings): KeyboardTheme {
            val p = paletteFor(context, settings.palette)
            if (p == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return dynamicTheme(context, systemIsDark(context))
            return fromPalette(p ?: Palettes.all.first())
        }

        fun fromPalette(p: Palettes.Palette): KeyboardTheme = KeyboardTheme(
            isDark = p.dark,
            background = p.background.toInt(),
            key = p.key.toInt(),
            keyFunctional = p.keyFunctional.toInt(),
            keyPressed = p.keyPressed.toInt(),
            keyText = p.keyText.toInt(),
            keyTextSecondary = p.keyTextSecondary.toInt(),
            accent = p.accent.toInt(),
            onAccent = p.onAccent.toInt(),
            popup = p.popup.toInt(),
            popupText = p.popupText.toInt(),
            stripText = p.stripText.toInt(),
            trail = p.accent.toInt(),
            modifierActive = p.modifierActive.toInt(),
            modifierLocked = p.modifierLocked.toInt(),
        )

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
    }
}
