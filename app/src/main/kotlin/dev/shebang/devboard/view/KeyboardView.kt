package dev.shebang.devboard.view

import dev.shebang.devboard.glide.TapModel
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.core.view.ViewCompat
import dev.shebang.devboard.layout.Key
import dev.shebang.devboard.layout.KeyAction
import dev.shebang.devboard.layout.KeyboardGeometry

enum class ShiftState { OFF, ON, LOCKED }

/**
 * The keyboard: every key drawn on a Canvas, with its own multitouch handling.
 *
 * Touch paths are kept in primitive arrays and paints/rects are preallocated, so a keystroke allocates
 * nothing on the main thread. Geometry is swapped in as a whole when the size, layout or variant changes.
 *
 * A glide is streamed to the listener while the finger moves: every recorded point with its touch time
 * (historical samples included), and, with phrase gliding on, a word boundary each time the finger dips
 * deliberately into the space bar (past its middle, or resting there).
 */
class KeyboardView(context: Context) : View(context) {

    interface Listener {
        /** Finger down on a key: feedback only. */
        fun onKeyDown(key: Key)
        /** A tap (or a click-after-hold of a non-repeating key). */
        fun onKeyTap(key: Key, shift: ShiftState)
        /** Hold-to-repeat tick. The tap on release is suppressed once this has fired. */
        fun onKeyRepeat(key: Key)
        /** Backspace held past [WORDS_AFTER_MS] with [holdDeletesWords] on: a whole word goes per tick. */
        fun onBackspaceWordRepeat() = Unit
        /** Alternate chosen from the long-press popup. */
        fun onAlternate(key: Key, text: String)
        /** A touch became a glide: the points so far, x,y interleaved in [points], touch times in [times]. */
        fun onGlideStart(points: FloatArray, times: LongArray, count: Int)
        /** The next recorded point of the glide. */
        fun onGlidePoint(x: Float, y: Float, t: Long)
        /** Phrase gliding: the finger dipped into the space bar, ending one word. */
        fun onGlideBoundary()
        /** The finger lifted; [trailingSpace] when it lifted in the space bar after a dip. */
        fun onGlideEnd(x: Float, y: Float, t: Long, trailingSpace: Boolean)
        /** The glide was abandoned (touch cancelled). */
        fun onGlideCancel()
        fun onSpaceLongPress()
        /** Shift held down: caps lock is now on (for feedback). */
        fun onShiftLongPress() = Unit
        /** The mode key (#! or ABC) held down. */
        fun onModeLongPress() = Unit
        /** Cursor drag along the space bar: +1 right, -1 left; with [select] (shift on) the selection grows. */
        fun onCursorMove(steps: Int, select: Boolean)
        /** Swiping left from backspace: [words] words before the cursor would go (0: none). */
        fun onDeleteWordsPreview(words: Int) = Unit
        /** The swipe from backspace ended: delete [words] words (0: nothing). */
        fun onDeleteWords(words: Int) = Unit
        fun onShiftChanged(state: ShiftState)
        /** Whether a touch starting on a letter may become a glide right now (field and setting). */
        fun isGlideAllowed(): Boolean
        /** The view was laid out at a width the current geometry was not built for. */
        fun onKeyboardWidthChanged(widthPx: Int)
    }

    var listener: Listener? = null
    var geometry: KeyboardGeometry? = null
        private set
    var theme: KeyboardTheme = KeyboardTheme.build(context, dev.shebang.devboard.settings.Settings())
        set(value) {
            field = value
            if (::popup.isInitialized) popup.setTheme(value)
            applyTheme()
            invalidate()
        }
    var shiftState: ShiftState = ShiftState.OFF
        private set
    var keyPreviewEnabled: Boolean
        get() = touch.keyPreviewEnabled
        set(value) {
            touch.keyPreviewEnabled = value
        }
    /** The Enter key's glyph, from what the field does with Enter. */
    var enterKind = dev.shebang.devboard.ime.EnterKind.RETURN
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }
    /** What a screen reader says for the Enter key ([dev.shebang.devboard.ime.FieldInfo.enterSpoken]). */
    var enterSpoken = "Enter"
    var glideTrailEnabled = true
    /** Dipping into the space bar during a glide starts the next word. */
    var phraseGlideEnabled: Boolean
        get() = touch.phraseGlideEnabled
        set(value) {
            touch.phraseGlideEnabled = value
        }

    private val density = resources.displayMetrics.density
    /** The preview/alternates overlay, owned by the IME root so it can draw above the top row. */
    lateinit var popup: KeyPopup
    private val handler = Handler(Looper.getMainLooper())

    // Paints and scratch, allocated once.
    private val bgPaint = Paint()
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val iconStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val rect = RectF()
    private val iconPath = Path()
    private var radius = 8 * density
    private var labelSize = 0f
    private var hintSize = 0f
    private var iconSize = 0f
    /** Height of the keycap edge under each key's face. */
    private var lip = 2 * density

    /** What touches mean: taps, holds, swipes and glides ([KeyTouch]); this View feeds it and draws. */
    private val touch: KeyTouch = KeyTouch(
        object : KeyTouch.Host {
            override val listener: Listener? get() = this@KeyboardView.listener
            override val shiftState: ShiftState get() = this@KeyboardView.shiftState
            override fun setShift(state: ShiftState) = this@KeyboardView.setShift(state)
            override fun now() = SystemClock.uptimeMillis()
            override fun post(timer: KeyTouch.Timer, delayMs: Long) {
                val r = timerRunnables[timer.ordinal]
                handler.removeCallbacks(r)
                handler.postDelayed(r, delayMs)
            }
            override fun cancel(timer: KeyTouch.Timer) = handler.removeCallbacks(timerRunnables[timer.ordinal])
            override fun redraw() = invalidate()
        },
        object : KeyTouch.Popup {
            override val isAlternates get() = popup.isAlternates
            override fun showPreview(key: Key, label: String) = popup.showPreview(this@KeyboardView, key, label)
            override fun showAlternates(key: Key, alternates: List<String>) = popup.showAlternates(this@KeyboardView, key, alternates)
            override fun updateSelection(x: Float) = popup.updateSelection(x)
            override fun clearSelection() = popup.clearSelection()
            override fun selectedAlternate() = popup.selectedAlternate()
            override fun dismiss() = popup.dismiss()
        },
        density,
    )
    private val timerRunnables: List<Runnable> = KeyTouch.Timer.entries.map { t -> Runnable { touch.fire(t) } }

    /**
     * Where this user's taps land on the letter keys. When set, a touch on a letter key means the letter
     * whose usual landing spot is nearest, so a habitual lean (taps a little left of each key) still types
     * the key meant. Other keys go by their drawn edges.
     */
    var tapModel: TapModel?
        get() = touch.tapModel
        set(value) {
            touch.tapModel = value
        }

    /** How likely each letter a..z is next in the word being typed, weighed with where a tap landed; null: none. */
    var letterPrior: FloatArray?
        get() = touch.letterPrior
        set(value) {
            touch.letterPrior = value
        }

    /** Whether a letter tap just above the space bar may be taken as a space. */
    var spaceFromLetters: Boolean
        get() = touch.spaceFromLetters
        set(value) {
            touch.spaceFromLetters = value
        }
    /** Whether the last tap reported was a letter tap turned into a space. */
    val lastSpaceFromLetter: Boolean get() = touch.lastSpaceFromLetter

    /** Where the finger came down for the tap being reported to [Listener.onKeyTap], in view pixels. */
    val lastTapX: Float get() = touch.lastTapX
    val lastTapY: Float get() = touch.lastTapY

    /** Holding backspace past [WORDS_AFTER_MS] deletes whole words (setting). */
    var holdDeletesWords: Boolean
        get() = touch.holdDeletesWords
        set(value) {
            touch.holdDeletesWords = value
        }

    /** The keys as virtual views for screen readers. */
    private val accessibility = KeyboardAccessibility(this)

    init {
        applyTheme()
        isClickable = true
        ViewCompat.setAccessibilityDelegate(this, accessibility)
    }

    /** The key a screen reader's exploring finger is on, for lift-to-type. */
    private var exploredKey: Key? = null

    // With a screen reader's explore-by-touch on, a finger arrives as hover events, which find the keys. Lifting
    // the finger on the key it is exploring types it (lift-to-type, as keyboards do for TalkBack; the screen
    // reader leaves that to the keyboard); a double tap types the focused key through the accessibility node.
    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        val am = context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        if (am?.isTouchExplorationEnabled == true) {
            val key = geometry?.keys?.firstOrNull { !it.def.spacer && it.contains(event.x, event.y) }
            when (event.actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> exploredKey = key
                MotionEvent.ACTION_HOVER_EXIT -> {
                    if (key != null && key === exploredKey) accessibilityTap(key)
                    exploredKey = null
                }
            }
        }
        return accessibility.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)
    }

    /** A key activated through a screen reader: typed as a tap on it would type it. */
    internal fun accessibilityTap(key: Key) {
        listener?.onKeyDown(key)
        if (key.action == KeyAction.SHIFT) touch.shiftTap() else listener?.onKeyTap(key, shiftState)
    }

    private fun applyTheme() {
        bgPaint.color = theme.background
        trailPaint.color = theme.trail
        iconStrokePaint.strokeWidth = 1.8f * density
        cursorPaint.strokeWidth = 2.5f * density
    }

    fun setGeometry(g: KeyboardGeometry) {
        geometry = g
        accessibility.invalidateRoot()
        radius = (g.rowHeightPx * 0.16f).coerceIn(4 * density, 12 * density)
        // By the row height, but no wider than the key allows: on a taller keyboard the keys grow taller, not
        // wider, and a capital "W" would reach the hint in the corner. The width caps are the proportions at
        // the default height, which they leave unchanged.
        labelSize = minOf(g.rowHeightPx * 0.42f, g.letterKeyWidth * 0.6f)
        hintSize = minOf(g.rowHeightPx * 0.22f, g.letterKeyWidth * 0.32f)
        iconSize = g.rowHeightPx * 0.44f
        lip = (g.rowHeightPx * 0.045f).coerceIn(1.5f * density, 3f * density)
        labelPaint.textSize = labelSize
        hintPaint.textSize = hintSize
        labelPaint.typeface = Typeface.DEFAULT
        // The keys moved: every touch is abandoned.
        touch.geometry = g
        invalidate()
    }

    fun setShift(state: ShiftState, notify: Boolean = true) {
        if (shiftState == state) return
        shiftState = state
        if (notify) listener?.onShiftChanged(state)
        accessibility.invalidateRoot()
        invalidate()
    }

    /** After a letter is typed with one-shot shift the keyboard returns to lowercase. Caps lock stays. */
    fun releaseOneShotShift() {
        if (shiftState == ShiftState.ON) setShift(ShiftState.OFF)
    }

    val isGliding: Boolean get() = touch.gliding

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val g = geometry
        if (w > 0 && (g == null || g.widthPx != w)) listener?.onKeyboardWidthChanged(w)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = geometry?.heightPx ?: (4 * 52 * density).toInt()
        setMeasuredDimension(w, h)
    }

    // ---- Drawing -------------------------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        val g = geometry ?: return
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        val keys = g.keys
        val shifted = shiftState != ShiftState.OFF
        for (i in keys.indices) drawKey(canvas, keys[i], shifted)
        if (touch.gliding && glideTrailEnabled && touch.glideCount > 1) drawTrail(canvas)
    }

    private fun drawKey(canvas: Canvas, key: Key, shifted: Boolean) {
        val pressed = touch.isPressed(key) || (touch.spaceArmed && key === touch.spaceKey)
        val action = key.action
        val accentKey = action == KeyAction.ENTER || (action == KeyAction.SHIFT && shiftState == ShiftState.LOCKED)
        keyPaint.color = when {
            pressed -> theme.keyPressed
            accentKey -> theme.accent
            key.def.functional -> theme.keyFunctional
            else -> theme.key
        }
        // A keycap: the edge underneath, the face raised above it; a pressed key sinks onto its edge.
        rect.set(key.left, key.top, key.right, key.bottom)
        if (!pressed) {
            edgePaint.color = when {
                accentKey -> theme.accentEdge
                key.def.functional -> theme.keyFunctionalEdge
                else -> theme.keyEdge
            }
            canvas.drawRoundRect(rect, radius, radius, edgePaint)
            rect.bottom -= lip
        } else {
            rect.top += lip
        }
        canvas.drawRoundRect(rect, radius, radius, keyPaint)

        val fg = if (accentKey && !pressed) theme.onAccent else theme.keyText
        when (action) {
            KeyAction.BACKSPACE -> drawIcon(canvas, KeyIcons.backspace, fg, false)
            // A glyph for what Enter does here (return, search, or Go/Send/Done), never a word: the user's choice.
            KeyAction.ENTER -> drawIcon(canvas, KeyIcons.enterFor(enterKind), fg, false)
            // The #! key wears the Shebang mark.
            KeyAction.MODE_CODE -> drawIcon(canvas, KeyIcons.shebang, fg, false)
            KeyAction.SHIFT -> drawIcon(canvas, when (shiftState) {
                ShiftState.OFF -> KeyIcons.shift
                ShiftState.ON -> KeyIcons.shiftOn
                ShiftState.LOCKED -> KeyIcons.shiftLocked
            }, fg, false)
            KeyAction.SPACE -> {
                // A cursor mark on the space bar.
                val w = minOf(rect.width() * 0.08f, 16 * density)
                val y = rect.centerY() + labelSize * 0.28f
                cursorPaint.color = theme.keyTextSecondary
                canvas.drawLine(rect.centerX() - w / 2, y, rect.centerX() + w / 2, y, cursorPaint)
            }
            else -> {
                val label = if (shifted) key.shiftedLabel else key.label
                val scale = if (label.length > 1) 0.7f else 1f
                drawLabel(canvas, label, fg, scale)
                // Letters show their first alternate; so do code mode's keys (5 holds %, 6 holds ^).
                if (key.alternates.isNotEmpty() && (key.letter != 0.toChar() || geometry?.layout?.mode == "code")) {
                    hintPaint.color = theme.keyTextSecondary
                    canvas.drawText(key.alternates[0], rect.right - 5 * density, rect.top + hintSize + 3 * density, hintPaint)
                }
            }
        }
    }

    /** A label centred on the key face in [rect]. */
    private fun drawLabel(canvas: Canvas, label: String, color: Int, scale: Float) {
        labelPaint.color = color
        labelPaint.textSize = labelSize * scale
        val baseline = rect.centerY() - (labelPaint.descent() + labelPaint.ascent()) / 2f
        canvas.drawText(label, rect.centerX(), baseline, labelPaint)
    }

    private fun drawIcon(canvas: Canvas, icon: Path, color: Int, stroke: Boolean) {
        KeyIcons.fit(icon, iconSize, rect, iconPath)
        if (stroke) {
            iconStrokePaint.color = color
            canvas.drawPath(iconPath, iconStrokePaint)
        } else {
            iconPaint.color = color
            canvas.drawPath(iconPath, iconPaint)
        }
    }

    private fun drawTrail(canvas: Canvas) {
        // Newest segments are wide and opaque, older ones fade out.
        val n = touch.glideCount
        val glidePoints = touch.glidePoints
        val visible = minOf(n, TRAIL_SEGMENTS)
        val start = n - visible
        val maxWidth = 7 * density
        for (i in start + 1 until n) {
            val t = (i - start).toFloat() / visible
            trailPaint.alpha = (255 * t * t).toInt().coerceIn(0, 255)
            trailPaint.strokeWidth = maxWidth * (0.3f + 0.7f * t)
            canvas.drawLine(glidePoints[2 * i - 2], glidePoints[2 * i - 1], glidePoints[2 * i], glidePoints[2 * i + 1], trailPaint)
        }
    }

    // ---- Touch ---------------------------------------------------------------------------------------

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    @Suppress("ClickableViewAccessibility") // Keys are drawn, not child views; taps are reported through the listener.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (geometry == null) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                touch.down(event.getPointerId(idx), event.getX(idx), event.getY(idx), event.eventTime)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    // Touchscreens report faster than the display: batched samples keep the path and its timing.
                    for (h in 0 until event.historySize) {
                        touch.move(id, event.getHistoricalX(i, h), event.getHistoricalY(i, h), event.getHistoricalEventTime(h))
                    }
                    touch.move(id, event.getX(i), event.getY(i), event.eventTime)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val idx = event.actionIndex
                touch.up(event.getPointerId(idx), event.getX(idx), event.getY(idx), event.eventTime)
            }
            MotionEvent.ACTION_CANCEL -> touch.cancel()
        }
        return true
    }

    fun cancelAllTouches() = touch.cancel()

    override fun onDetachedFromWindow() {
        cancelAllTouches()
        super.onDetachedFromWindow()
    }

    companion object {
        private const val TRAIL_SEGMENTS = 60
        /** How long backspace is held before it deletes whole words. */
        const val WORDS_AFTER_MS = KeyTouch.WORDS_AFTER_MS
    }
}
