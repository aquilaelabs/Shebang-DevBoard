package dev.shebang.devboard.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import dev.shebang.devboard.R

/**
 * The voice typing button at the end of the strip, shown when the Shebang Voice add-on is installed. Idle it
 * is a mic glyph in the strip's text colour; while listening it sits on an accent disc that swells with the
 * voice level, and while what was said is being written the disc is steady.
 */
class MicButton(context: Context) : View(context) {
    enum class State { IDLE, LISTENING, WRITING }

    private val density = resources.displayMetrics.density
    private var theme: KeyboardTheme = KeyboardTheme.build(context, dev.shebang.devboard.settings.Settings())
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val icon = Path()
    private val bounds = RectF()

    var state = State.IDLE
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    /** 0..100, while listening. */
    var level = 0
        set(value) {
            if (field != value) {
                field = value
                if (state == State.LISTENING) invalidate()
            }
        }

    init {
        contentDescription = context.getString(R.string.voice_typing)
        isClickable = true
    }

    fun setTheme(t: KeyboardTheme) {
        theme = t
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val base = minOf(width, height) * 0.36f
        if (state != State.IDLE) {
            paint.color = theme.accent
            val r = if (state == State.LISTENING) base * (0.85f + 0.3f * level / 100f) else base * 0.9f
            canvas.drawCircle(cx, cy, r, paint)
        }
        paint.color = if (state == State.IDLE) theme.stripText else theme.onAccent
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        KeyIcons.fit(KeyIcons.mic, 22 * density, bounds, icon)
        canvas.drawPath(icon, paint)
    }
}
