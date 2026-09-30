package dev.shebang.devboard.settings

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.widget.LinearLayout
import dev.shebang.devboard.glide.GlideTrace
import dev.shebang.devboard.ime.KeyboardSizing
import dev.shebang.devboard.layout.FieldVariant
import dev.shebang.devboard.layout.Key
import dev.shebang.devboard.layout.KeyboardGeometry
import dev.shebang.devboard.layout.LayoutDef
import dev.shebang.devboard.view.ImeRootView
import dev.shebang.devboard.view.KeyPopup
import dev.shebang.devboard.view.KeyboardTheme
import dev.shebang.devboard.view.KeyboardView
import dev.shebang.devboard.view.ShiftState

/**
 * The text keyboard, sized like the real one, for recording glides of prompted words. Taps do nothing; each
 * finished glide is handed to [onTrace] with the key positions it was made on.
 */
@SuppressLint("ViewConstructor") // Built in code by the recorder screen only.
class GlideRecorderView(
    context: Context,
    private val layout: LayoutDef,
    private val settings: Settings,
    private val onTrace: (xs: FloatArray, ys: FloatArray, ts: LongArray, geometry: KeyboardGeometry) -> Unit,
) : LinearLayout(context), KeyboardView.Listener {

    private val keyboard = KeyboardView(context)
    private val popup = KeyPopup(context)
    private var version = 0
    private val xs = ArrayList<Float>()
    private val ys = ArrayList<Float>()
    private val ts = ArrayList<Long>()
    private var start = 0L

    init {
        orientation = VERTICAL
        keyboard.popup = popup
        keyboard.listener = this
        keyboard.keyPreviewEnabled = false
        keyboard.glideTrailEnabled = true
        val theme = KeyboardTheme.build(context, settings)
        keyboard.theme = theme
        popup.setTheme(theme)
        addView(ImeRootView(context, keyboard, popup), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    private fun rebuild(width: Int) {
        if (width <= 0) return
        val rowHeight = KeyboardSizing.rowHeightPx(resources, settings.heightScale)
        keyboard.setGeometry(
            KeyboardGeometry(
                layout, FieldVariant.PLAIN, width, (layout.rows.size * rowHeight).toInt(), false,
                KeyboardSizing.horizontalGapPx(resources), KeyboardSizing.verticalGapPx(resources), ++version,
            )
        )
        keyboard.requestLayout()
    }

    // ---- KeyboardView.Listener -----------------------------------------------------------------------

    override fun onKeyboardWidthChanged(widthPx: Int) = rebuild(widthPx)
    override fun isGlideAllowed(): Boolean = true

    override fun onGlideStart(points: FloatArray, times: LongArray, count: Int) {
        xs.clear()
        ys.clear()
        ts.clear()
        start = times[0]
        for (i in 0 until count) add(points[2 * i], points[2 * i + 1], times[i])
    }

    override fun onGlidePoint(x: Float, y: Float, t: Long) = add(x, y, t)

    private fun add(x: Float, y: Float, t: Long) {
        xs.add(x)
        ys.add(y)
        ts.add(t - start)
    }

    override fun onGlideEnd(x: Float, y: Float, t: Long, trailingSpace: Boolean) {
        add(x, y, t)
        val g = keyboard.geometry ?: return
        onTrace(xs.toFloatArray(), ys.toFloatArray(), ts.toLongArray(), g)
    }

    override fun onGlideCancel() {
        xs.clear()
        ys.clear()
        ts.clear()
    }

    override fun onGlideBoundary() = Unit
    override fun onKeyDown(key: Key) = Unit
    override fun onKeyTap(key: Key, shift: ShiftState) = Unit
    override fun onKeyRepeat(key: Key) = Unit
    override fun onAlternate(key: Key, text: String) = Unit
    override fun onSpaceLongPress() = Unit
    override fun onCursorMove(steps: Int) = Unit
    override fun onShiftChanged(state: ShiftState) = Unit

    companion object {
        /** A trace of [word] from recorded points and the geometry they were made on. */
        fun trace(word: String, xs: FloatArray, ys: FloatArray, ts: LongArray, g: KeyboardGeometry, density: Float): GlideTrace {
            val cx = ArrayList<Float?>(26)
            val cy = ArrayList<Float?>(26)
            for (c in 'a'..'z') {
                val k = g.letterKey(c)
                cx.add(k?.centerX)
                cy.add(k?.centerY)
            }
            return GlideTrace(
                word = word,
                keyWidth = g.letterKeyWidth,
                keyHeight = g.rowHeightPx,
                centerX = cx,
                centerY = cy,
                x = xs.toList(),
                y = ys.toList(),
                t = ts.toList(),
                density = density,
                device = "${Build.MANUFACTURER} ${Build.MODEL}",
            )
        }
    }
}
