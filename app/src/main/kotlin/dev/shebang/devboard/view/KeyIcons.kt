package dev.shebang.devboard.view

import android.graphics.Matrix
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.PathParser

/**
 * Key glyphs as paths in a 24x24 box, all original to this project, in a terminal idiom: backspace is a
 * chevron erasing back toward a block cursor, return a bent arrow with an open chevron head, shift a caret
 * that gains an underline while shift is on. Scaled copies are built once per geometry.
 */
object KeyIcons {
    /** A left chevron and a block cursor after it. */
    private const val BACKSPACE = "M11.3 6.3 L12.7 7.7 L8.4 12 L12.7 16.3 L11.3 17.7 L5.6 12 Z M14.5 8 H20 V16 H14.5 Z"
    /** Down from the top right, then left, ending in an open chevron. */
    private const val RETURN = "M19 4.5 H21 V15 H8.8 L11.9 18.1 L10.5 19.5 L5 14 L10.5 8.5 L11.9 9.9 L8.8 13 H19 Z"
    /** A caret pointing up. */
    private const val SHIFT = "M12 4.6 L20.1 12.7 L18.7 14.1 L12 7.4 L5.3 14.1 L3.9 12.7 Z"
    /** The caret with a bar under it: shift is on. */
    private const val SHIFT_ON = "$SHIFT M6.5 17.5 H17.5 V19.5 H6.5 Z"

    val backspace: Path = PathParser.createPathFromPathData(BACKSPACE)
    val enter: Path = PathParser.createPathFromPathData(RETURN)
    val shift: Path = PathParser.createPathFromPathData(SHIFT)
    val shiftOn: Path = PathParser.createPathFromPathData(SHIFT_ON)

    private val matrix = Matrix()

    /** Copies [src] scaled and centred inside [bounds] at [size] pixels. Main thread only; allocates nothing. */
    fun fit(src: Path, size: Float, bounds: RectF, out: Path) {
        val m = matrix
        val s = size / 24f
        m.setScale(s, s)
        m.postTranslate(bounds.centerX() - size / 2f, bounds.centerY() - size / 2f)
        out.reset()
        src.transform(m, out)
    }
}
