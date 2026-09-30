package dev.shebang.devboard.layout

import kotlinx.serialization.Serializable

/** Which kind of field a layout variant is meant for; see [KeyDef.only] / [KeyDef.hide]. */
enum class FieldVariant { PLAIN, EMAIL, URL, NUMBER, PHONE, DATE }

/** Named actions a key can trigger instead of typing text. */
enum class KeyAction {
    SHIFT, BACKSPACE, ENTER, SPACE, MODE_CODE, MODE_TEXT, NONE;

    companion object {
        fun parse(s: String?): KeyAction = when (s?.lowercase()) {
            null -> NONE
            "shift" -> SHIFT
            "backspace" -> BACKSPACE
            "enter" -> ENTER
            "space" -> SPACE
            "mode_code" -> MODE_CODE
            "mode_text" -> MODE_TEXT
            else -> throw IllegalArgumentException("unknown action '$s'")
        }
    }
}

/** A key as written in a layout JSON asset. */
@Serializable
data class KeyDef(
    /** Text to insert when tapped. For letters this is the lowercase form. */
    val text: String? = null,
    /** Display label; defaults to [text]. */
    val label: String? = null,
    /** Android keycode name (without KEYCODE_), e.g. "DPAD_LEFT". Sent as a KeyEvent. */
    val code: String? = null,
    /** Special action name, see [KeyAction]. */
    val action: String? = null,
    /** Width in layout units (a layout is [LayoutDef.widthUnits] wide). */
    val width: Float = 1f,
    /** Long-press alternates in popup order. */
    val alternates: List<String> = emptyList(),
    /** The key repeats while held. */
    val repeat: Boolean = false,
    /** An invisible gap. */
    val spacer: Boolean = false,
    /** Absorbs leftover row width so rows always sum to widthUnits. */
    val flex: Boolean = false,
    /** Only present for these field variants (lowercase names). Empty = always. */
    val only: List<String> = emptyList(),
    /** Hidden for these field variants. */
    val hide: List<String> = emptyList(),
    /** Draw as a "function" key (darker) rather than a character key. */
    val functional: Boolean = false,
) {
    val keyAction: KeyAction get() = KeyAction.parse(action)

    val isLetter: Boolean
        get() = text != null && text.length == 1 && text[0].isLetter()

    fun visibleFor(variant: FieldVariant): Boolean {
        val v = variant.name.lowercase()
        if (only.isNotEmpty() && v !in only) return false
        if (v in hide) return false
        return true
    }
}

@Serializable
data class RowDef(
    val keys: List<KeyDef>,
    /** Height relative to the layout's base row height. */
    val height: Float = 1f,
)

@Serializable
data class LayoutDef(
    val name: String,
    /** "text", "code" or "numeric". */
    val mode: String,
    val rows: List<RowDef>,
    val widthUnits: Float = 10f,
    /** Optional row shown above [rows] when the number-row setting is on (text mode). */
    val numberRow: RowDef? = null,
    /** Letters are typed with composing/suggestions/glide. False for code and numeric. */
    val composing: Boolean = false,
)
