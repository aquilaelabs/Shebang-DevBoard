package dev.shebang.devboard.view

/**
 * The keyboard's themes: original palettes with original names. [AUTO] follows the system between [NIGHT] and
 * [DAY]; [WALLPAPER] takes the system's own palette on Android 12+ (and behaves like [AUTO] before it).
 */
object Palettes {
    const val AUTO = "auto"
    const val WALLPAPER = "wallpaper"
    const val NIGHT = "night"
    const val DAY = "day"

    class Palette(
        val id: String,
        val name: String,
        val dark: Boolean,
        val background: Long,
        val key: Long,
        val keyFunctional: Long,
        val keyPressed: Long,
        val keyText: Long,
        val keyTextSecondary: Long,
        val accent: Long,
        val onAccent: Long,
        val popup: Long,
        val popupText: Long,
        val stripText: Long,
        val modifierActive: Long,
        val modifierLocked: Long,
    )

    val all: List<Palette> = listOf(
        Palette(NIGHT, "Night", true, 0xFF1B1F2A, 0xFF2E3440, 0xFF252B36, 0xFF4A5366, 0xFFECEFF4, 0xFF9AA3B5,
            0xFF7BE0A6, 0xFF0B2A1A, 0xFF3B4252, 0xFFECEFF4, 0xFFE5E9F0, 0xFF3F5A4B, 0xFF5FBF8A),
        Palette(DAY, "Day", false, 0xFFE6E9F0, 0xFFFFFFFF, 0xFFCFD5E1, 0xFFB8C0D0, 0xFF1B1F2A, 0xFF5B6475,
            0xFF1E8E5A, 0xFFFFFFFF, 0xFFFFFFFF, 0xFF1B1F2A, 0xFF2A2F3A, 0xFFBFE8D2, 0xFF57C48B),
        Palette("phosphor", "Phosphor", true, 0xFF050A06, 0xFF0F1A11, 0xFF0A130C, 0xFF1F3A24, 0xFF8CFFA0, 0xFF3F8F4F,
            0xFF39FF6A, 0xFF03140A, 0xFF14261A, 0xFFB8FFC4, 0xFF8CFFA0, 0xFF1E4A2A, 0xFF2FD65A),
        Palette("amber", "Amber", true, 0xFF0F0A03, 0xFF1E1507, 0xFF170F05, 0xFF3A2A10, 0xFFFFC266, 0xFFA4733A,
            0xFFFF9F1C, 0xFF1F1200, 0xFF2A1D0A, 0xFFFFD699, 0xFFFFC266, 0xFF4A3310, 0xFFE08A12),
        Palette("deep-sea", "Deep Sea", true, 0xFF0B1624, 0xFF132338, 0xFF0F1C2E, 0xFF22395A, 0xFFDDE8F5, 0xFF7D93AE,
            0xFF4FC3F7, 0xFF04202E, 0xFF1A2E48, 0xFFDDE8F5, 0xFFD0DDEE, 0xFF1D4260, 0xFF3AA9DA),
        Palette("ember", "Ember", true, 0xFF1A1110, 0xFF2B1C19, 0xFF221614, 0xFF4A2E28, 0xFFF7E4DD, 0xFFB08A80,
            0xFFFF6B4A, 0xFF2A0B04, 0xFF36231F, 0xFFF7E4DD, 0xFFF0DAD2, 0xFF5A2A1F, 0xFFE85A3A),
        Palette("orchid", "Orchid", true, 0xFF1A1422, 0xFF2A2035, 0xFF221A2C, 0xFF43345A, 0xFFF0E7FA, 0xFFA493BA,
            0xFFD08BF2, 0xFF2A0E38, 0xFF332745, 0xFFF0E7FA, 0xFFE8DDF5, 0xFF4A335E, 0xFFB774DB),
        Palette("forest", "Forest", true, 0xFF121A15, 0xFF1D2A22, 0xFF17221B, 0xFF314638, 0xFFE4EFE6, 0xFF8FA696,
            0xFFA3D977, 0xFF16240B, 0xFF26372C, 0xFFE4EFE6, 0xFFDCE8DE, 0xFF36502A, 0xFF8BC25E),
        Palette("paper", "Paper", false, 0xFFECE6DA, 0xFFFBF8F1, 0xFFDDD5C6, 0xFFCFC5B3, 0xFF2B2622, 0xFF7A6F63,
            0xFFB8430E, 0xFFFFF7F0, 0xFFFFFDF8, 0xFF2B2622, 0xFF3A332C, 0xFFF1D2C2, 0xFFD9683A),
        Palette("glacier", "Glacier", false, 0xFFDCE8EE, 0xFFFFFFFF, 0xFFC3D6DF, 0xFFAFC7D3, 0xFF12303D, 0xFF587383,
            0xFF0077A8, 0xFFFFFFFF, 0xFFFFFFFF, 0xFF12303D, 0xFF1C3C4A, 0xFFBFE3F2, 0xFF2A9CCC),
        Palette("sand", "Sand", false, 0xFFE9DFCB, 0xFFF8F2E5, 0xFFD8CBB2, 0xFFC9BA9D, 0xFF3A2E1F, 0xFF857255,
            0xFF8A5A00, 0xFFFFF8EA, 0xFFFFFBF2, 0xFF3A2E1F, 0xFF45382A, 0xFFEBD6A8, 0xFFB98524),
        Palette("high-contrast", "High Contrast", true, 0xFF000000, 0xFF1A1A1A, 0xFF0D0D0D, 0xFF404040, 0xFFFFFFFF, 0xFFFFD600,
            0xFFFFD600, 0xFF000000, 0xFF1A1A1A, 0xFFFFFFFF, 0xFFFFFFFF, 0xFF5C4D00, 0xFFFFD600),
    )

    fun byId(id: String): Palette? = all.firstOrNull { it.id == id }

    /** Everything the theme setting offers, in the order the picker shows it: id to name. */
    val choices: List<Pair<String, String>> = listOf(AUTO to "Auto", WALLPAPER to "Wallpaper") + all.map { it.id to it.name }
}
