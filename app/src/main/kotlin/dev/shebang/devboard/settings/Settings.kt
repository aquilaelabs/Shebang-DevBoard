package dev.shebang.devboard.settings

/** What the top strip shows in text mode. */
enum class StripMode {
    /** Suggestions while composing, the terminal bar otherwise. */
    AUTO,
    /** Always the terminal bar; suggestions are never shown. */
    ALWAYS_BAR,
    /** Both: bar above, suggestions below. */
    TWO_ROWS,
}

/** Every user setting, with its default. Read on the main thread from a cached snapshot; never blocks. */
data class Settings(
    /** The keyboard's theme: an id from [dev.shebang.devboard.view.Palettes] ("auto" follows the system). */
    val palette: String = "auto",
    /** Multiplier on the base row height, 0.7..1.4. */
    val heightScale: Float = 1.0f,
    val numberRow: Boolean = false,
    val keyPreview: Boolean = true,
    val haptics: Boolean = true,
    /** 1 = light, 2 = medium, 3 = strong. */
    val hapticStrength: Int = 2,
    val keySounds: Boolean = false,
    val glide: Boolean = true,
    val glideTrail: Boolean = true,
    /** Dip into the space bar mid-glide to start the next word in the same stroke. */
    val phraseGlide: Boolean = false,
    /** Let a new glide rewrite the recent glided words when it makes another reading more likely. */
    /** Glided words wait in a row above the keys for a moment, where they can be tapped and corrected. */
    /** Learn words typed or glided in ordinary text fields, on this device only. */
    val learnWords: Boolean = true,
    /** Remember addresses entered in email fields and offer them there, on this device only. */
    val rememberEmails: Boolean = true,
    /** Adapt glide to where this user's strokes actually pass the keys. */
    val adaptGlide: Boolean = true,
    /** Autocorrect learns where this user's taps land on each key. */
    val adaptTaps: Boolean = true,
    val autocorrect: Boolean = true,
    val autoCaps: Boolean = true,
    val doubleSpacePeriod: Boolean = true,
    /** A glide may fix the glided word right before it, when the two together clearly read otherwise. */
    val fixPreviousGlide: Boolean = true,
    /** Dictation is tidied: hesitations, stutters and spoken corrections ("no wait", "scratch that"). */
    val tidyDictation: Boolean = true,
    /** After a space, the strip offers the words likely to come next. */
    val nextWord: Boolean = true,
    /** In code mode, ( [ { and quotes are typed in pairs. */
    val pairBrackets: Boolean = true,
    val stripMode: StripMode = StripMode.AUTO,
    /** Terminal bar as JSON, or null for the bundled default. */
    val barJson: String? = null,
    /** Apps with a terminal bar of their own: package name to bar JSON. Others use [barJson]. */
    val appBars: Map<String, String> = emptyMap(),
)
