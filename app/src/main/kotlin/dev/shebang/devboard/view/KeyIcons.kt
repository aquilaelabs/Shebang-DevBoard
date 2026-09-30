package dev.shebang.devboard.view

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.PathParser

/**
 * Key glyphs as paths in a 24x24 box. Backspace and return are Material Symbols (Apache-2.0, see
 * THIRD_PARTY_NOTICES.md); the shift arrow is original. Scaled copies are built once per geometry.
 */
object KeyIcons {
    private const val BACKSPACE =
        "M22 3H7c-.69 0-1.23.35-1.59.88L0 12l5.41 8.11c.36.53.9.89 1.59.89h15c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm-3 12.59L17.59 17 14 13.41 10.41 17 9 15.59 12.59 12 9 8.41 10.41 7 14 10.59 17.59 7 19 8.41 15.41 12 19 15.59z"
    private const val RETURN = "M19 7v4H5.83l3.58-3.59L8 6l-6 6 6 6 1.41-1.41L5.83 13H21V7z"
    /** Original outline arrow: point up, shaft down. */
    private const val SHIFT = "M12 3.5 L3.5 12 H8.5 V20.5 H15.5 V12 H20.5 Z"
    private const val KEYBOARD_HIDE =
        "M20 3H4c-1.1 0-1.99.9-1.99 2L2 15c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm-9 3h2v2h-2V6zm0 3h2v2h-2V9zM8 6h2v2H8V6zm0 3h2v2H8V9zm-1 2H5V9h2v2zm0-3H5V6h2v2zm9 7H8v-2h8v2zm0-4h-2V9h2v2zm0-3h-2V6h2v2zm3 3h-2V9h2v2zm0-3h-2V6h2v2zM12 23l4-4H8l4 4z"

    val backspace: Path = PathParser.createPathFromPathData(BACKSPACE)
    val enter: Path = PathParser.createPathFromPathData(RETURN)
    val shift: Path = PathParser.createPathFromPathData(SHIFT)
    val keyboardHide: Path = PathParser.createPathFromPathData(KEYBOARD_HIDE)

    /** Copies [src] scaled and centred inside [bounds] at [size] pixels. */
    fun fit(src: Path, size: Float, bounds: RectF, out: Path) {
        val m = Matrix()
        val s = size / 24f
        m.setScale(s, s)
        m.postTranslate(bounds.centerX() - size / 2f, bounds.centerY() - size / 2f)
        out.reset()
        src.transform(m, out)
    }
}
