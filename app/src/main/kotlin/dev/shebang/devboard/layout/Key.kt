package dev.shebang.devboard.layout

/** A key positioned in pixels. Geometry is immutable; a new [KeyboardGeometry] is built when size or layout changes. */
class Key(
    val def: KeyDef,
    val row: Int,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float = (left + right) / 2f
    val centerY: Float = (top + bottom) / 2f
    val label: String = def.label ?: def.text ?: ""
    /** Uppercase label/text used while shifted. Precomputed so drawing allocates nothing. */
    val shiftedLabel: String = if (def.isLetter) label.uppercase() else label
    val shiftedText: String? = if (def.isLetter) def.text!!.uppercase() else def.text
    /** Alternates shown while shifted (uppercase accents); digits and symbols are unchanged. */
    val shiftedAlternates: List<String> = if (def.isLetter) def.alternates.map { it.uppercase() } else def.alternates
    /** The lowercase letter this key types, or 0 when it is not a letter key. Used by glide. */
    val letter: Char = if (def.isLetter) def.text!![0].lowercaseChar() else 0.toChar()
    val action: KeyAction = def.keyAction
    val keyCode: Int = def.code?.let { KeyCodeNames.lookup(it) } ?: 0

    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom

    /** Squared distance from a point to the nearest point of this key (0 inside). */
    fun distanceSq(x: Float, y: Float): Float {
        val dx = when {
            x < left -> left - x
            x > right -> x - right
            else -> 0f
        }
        val dy = when {
            y < top -> top - y
            y > bottom -> y - bottom
            else -> 0f
        }
        return dx * dx + dy * dy
    }
}
