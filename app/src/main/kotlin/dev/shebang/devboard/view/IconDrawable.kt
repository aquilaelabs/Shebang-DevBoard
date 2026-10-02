package dev.shebang.devboard.view

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/** One of [KeyIcons]' glyphs in a single colour, [sizePx] square, centred in its bounds (for chips and buttons). */
class IconDrawable(private val icon: Path, color: Int, private val sizePx: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
    private val box = RectF()
    private val path = Path()

    override fun draw(canvas: Canvas) {
        box.set(bounds)
        KeyIcons.fit(icon, sizePx, box, path)
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
