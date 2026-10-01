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
import dev.shebang.devboard.layout.Key
import dev.shebang.devboard.layout.KeyAction
import dev.shebang.devboard.layout.KeyboardGeometry
import kotlin.math.abs
import kotlin.math.sqrt

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
    var keyPreviewEnabled = true
    var glideTrailEnabled = true
    /** Dipping into the space bar during a glide starts the next word. */
    var phraseGlideEnabled = false
    /** A quick flick up on a key types its corner character (its first long-press alternate). */
    var flickEnabled = true

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

    // Per-pointer touch state (index = pointer id, capped at MAX_POINTERS).
    private val pointerKey = arrayOfNulls<Key>(MAX_POINTERS)
    private val pointerDownX = FloatArray(MAX_POINTERS)

    /**
     * Where this user's taps land on the letter keys. When set, a touch on a letter key means the letter
     * whose usual landing spot is nearest, so a habitual lean (taps a little left of each key) still types
     * the key meant. Other keys go by their drawn edges.
     */
    var tapModel: TapModel? = null

    /** How likely each letter a..z is next in the word being typed, weighed with where a tap landed; null: none. */
    var letterPrior: FloatArray? = null

    private fun resolveLetter(hit: Key, x: Float, y: Float): Key {
        if (hit.letter == 0.toChar()) return hit
        val g = geometry ?: return hit
        val c = tapModel?.nearestLetter(x, y, prior = letterPrior) ?: return hit
        if (c == hit.letter) return hit
        return g.letterKey(c) ?: hit
    }

    /** Where the finger came down for the tap being reported to [Listener.onKeyTap], in view pixels. */
    var lastTapX = Float.NaN
        private set
    var lastTapY = Float.NaN
        private set
    private val pointerDownY = FloatArray(MAX_POINTERS)
    private val pointerDownT = LongArray(MAX_POINTERS)
    private val pointerLastX = FloatArray(MAX_POINTERS)
    private val pointerCancelled = BooleanArray(MAX_POINTERS)
    /** Pressed keys are drawn highlighted; pressedCount avoids scanning when nothing is down. */
    private var pressedCount = 0

    // Long-press / repeat / drag apply to one pointer at a time.
    private var activePointer = -1
    private var longPressPending = false
    private var repeatFired = false
    private var repeatInterval = 0L
    private var cursorDrag = false
    private var cursorDragAccum = 0f
    /** Swiping left from backspace, and how many words that would delete. */
    private var deleteDrag = false
    private var deleteWords = 0

    // Glide path: interleaved x,y, with touch times.
    private val glidePoints = FloatArray(2 * MAX_GLIDE_POINTS)
    private val glideTimes = LongArray(MAX_GLIDE_POINTS)
    private var glideCount = 0
    private var gliding = false
    private var glideLength = 0f
    private var lastShiftTapTime = 0L

    // Phrase gliding: points inside the space bar are held back until it is clear whether the dip was meant.
    private var spaceKey: Key? = null
    private var inSpace = false
    private var spaceArmed = false
    private var spaceEnterTime = 0L
    private val spaceBufX = FloatArray(SPACE_BUFFER)
    private val spaceBufY = FloatArray(SPACE_BUFFER)
    private val spaceBufT = LongArray(SPACE_BUFFER)
    private var spaceBufCount = 0
    private val spaceDwellRunnable = Runnable {
        if (gliding && inSpace && !spaceArmed) {
            spaceArmed = true
            invalidate()
        }
    }

    private val longPressRunnable = Runnable { onLongPress() }
    private val repeatRunnable = object : Runnable {
        override fun run() {
            val p = activePointer
            if (p < 0) return
            val key = pointerKey[p] ?: return
            repeatFired = true
            listener?.onKeyRepeat(key)
            repeatInterval = (repeatInterval * REPEAT_ACCEL).toLong().coerceAtLeast(REPEAT_MIN_MS)
            handler.postDelayed(this, repeatInterval)
        }
    }

    init {
        applyTheme()
        isClickable = true
    }

    private fun applyTheme() {
        bgPaint.color = theme.background
        trailPaint.color = theme.trail
        iconStrokePaint.strokeWidth = 1.8f * density
        cursorPaint.strokeWidth = 2.5f * density
    }

    fun setGeometry(g: KeyboardGeometry) {
        geometry = g
        spaceKey = g.keys.firstOrNull { it.action == KeyAction.SPACE && g.layout.composing }
        radius = (g.rowHeightPx * 0.16f).coerceIn(4 * density, 12 * density)
        labelSize = g.rowHeightPx * 0.42f
        hintSize = g.rowHeightPx * 0.22f
        iconSize = g.rowHeightPx * 0.44f
        lip = (g.rowHeightPx * 0.045f).coerceIn(1.5f * density, 3f * density)
        labelPaint.textSize = labelSize
        hintPaint.textSize = hintSize
        labelPaint.typeface = Typeface.DEFAULT
        cancelAllTouches()
        invalidate()
    }

    fun setShift(state: ShiftState, notify: Boolean = true) {
        if (shiftState == state) return
        shiftState = state
        if (notify) listener?.onShiftChanged(state)
        invalidate()
    }

    /** After a letter is typed with one-shot shift the keyboard returns to lowercase. Caps lock stays. */
    fun releaseOneShotShift() {
        if (shiftState == ShiftState.ON) setShift(ShiftState.OFF)
    }

    val isGliding: Boolean get() = gliding

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
        if (gliding && glideTrailEnabled && glideCount > 1) drawTrail(canvas)
    }

    private fun isPressed(key: Key): Boolean {
        if (pressedCount == 0) return false
        for (i in 0 until MAX_POINTERS) if (pointerKey[i] === key && !pointerCancelled[i]) return true
        return false
    }

    private fun drawKey(canvas: Canvas, key: Key, shifted: Boolean) {
        val pressed = isPressed(key) || (spaceArmed && key === spaceKey)
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
            // Always the enter icon, whatever the field's action (search, send, go): the user's choice.
            KeyAction.ENTER -> drawIcon(canvas, KeyIcons.enter, fg, false)
            KeyAction.SHIFT -> drawIcon(canvas, if (shiftState == ShiftState.OFF) KeyIcons.shift else KeyIcons.shiftOn, fg, false)
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
                if (key.alternates.isNotEmpty() && key.letter != 0.toChar()) {
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
        val n = glideCount
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
        val g = geometry ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val id = event.getPointerId(idx)
                if (id >= MAX_POINTERS) return true
                // A glide owns the keyboard until it ends: other fingers are ignored.
                if (gliding) return true
                // A second finger commits the first key's tap immediately (fast typing).
                if (activePointer >= 0 && activePointer != id && !cursorDrag) {
                    finishPointer(activePointer, pointerLastX[activePointer], -1f, false, event.eventTime)
                }
                val x = event.getX(idx)
                val y = event.getY(idx)
                val key = resolveLetter(g.keyAt(x, y) ?: return true, x, y)
                pointerKey[id] = key
                pointerDownX[id] = x
                pointerDownY[id] = y
                pointerDownT[id] = event.eventTime
                pointerLastX[id] = x
                pointerCancelled[id] = false
                pressedCount++
                activePointer = id
                longPressPending = true
                repeatFired = false
                cursorDrag = false
                cursorDragAccum = 0f
                deleteDrag = false
                deleteWords = 0
                gliding = false
                glideCount = 0
                glideLength = 0f
                if (key.letter != 0.toChar()) {
                    glidePoints[0] = x
                    glidePoints[1] = y
                    glideTimes[0] = event.eventTime
                    glideCount = 1
                }
                resetSpaceState()
                listener?.onKeyDown(key)
                handler.removeCallbacks(longPressRunnable)
                handler.removeCallbacks(repeatRunnable)
                if (key.def.repeat) {
                    repeatInterval = REPEAT_START_MS
                    handler.postDelayed(repeatRunnable, REPEAT_DELAY_MS)
                } else {
                    handler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                }
                if (keyPreviewEnabled && key.def.text != null && !key.def.functional) {
                    popup.showPreview(this, key, if (shiftState != ShiftState.OFF) key.shiftedLabel else key.label)
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    if (id >= MAX_POINTERS || id != activePointer) continue
                    val key = pointerKey[id] ?: continue
                    // Touchscreens report faster than the display: batched samples keep the path and its timing.
                    for (h in 0 until event.historySize) {
                        handleMove(g, id, key, event.getHistoricalX(i, h), event.getHistoricalY(i, h), event.getHistoricalEventTime(h))
                    }
                    handleMove(g, id, key, event.getX(i), event.getY(i), event.eventTime)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val idx = event.actionIndex
                val id = event.getPointerId(idx)
                if (id < MAX_POINTERS) finishPointer(id, event.getX(idx), event.getY(idx), true, event.eventTime)
            }
            MotionEvent.ACTION_CANCEL -> cancelAllTouches()
        }
        return true
    }

    private fun handleMove(g: KeyboardGeometry, id: Int, key: Key, x: Float, y: Float, t: Long) {
        moveTo(g, id, key, x, y, t)
        pointerLastX[id] = x
    }

    private fun moveTo(g: KeyboardGeometry, id: Int, key: Key, x: Float, y: Float, t: Long) {
        if (popup.isAlternates) {
            popup.updateSelection(x)
            return
        }
        val dx = x - pointerDownX[id]
        val dy = y - pointerDownY[id]
        val kw = g.letterKeyWidth
        if (key.action == KeyAction.BACKSPACE) {
            // Swiping left from backspace deletes whole words: one more for each step left, none if back.
            if (!deleteDrag && !repeatFired && dx < -kw * 0.6f) {
                deleteDrag = true
                handler.removeCallbacks(repeatRunnable)
                handler.removeCallbacks(longPressRunnable)
                longPressPending = false
            }
            if (deleteDrag) {
                val n = if (dx > -kw * 0.3f) 0 else 1 + ((-dx - kw * 0.6f) / (kw * 0.8f)).toInt().coerceAtLeast(0)
                if (n != deleteWords) {
                    deleteWords = n
                    listener?.onDeleteWordsPreview(n)
                }
            }
            return
        }
        if (key.action == KeyAction.SPACE) {
            if (!cursorDrag && abs(dx) > kw * 0.6f) {
                cursorDrag = true
                cursorDragAccum = 0f
                handler.removeCallbacks(longPressRunnable)
                longPressPending = false
            }
            if (cursorDrag) {
                cursorDragAccum += x - pointerLastX[id]
                val stepPx = kw * 0.55f
                val steps = (cursorDragAccum / stepPx).toInt()
                if (steps != 0) {
                    cursorDragAccum -= steps * stepPx
                    listener?.onCursorMove(steps, shiftState != ShiftState.OFF)
                }
            }
            return
        }
        // Moving a fifth of a key is a slide, not a hold: drop the pending long-press.
        if (longPressPending && (abs(dx) > kw * 0.2f || abs(dy) > kw * 0.2f)) {
            handler.removeCallbacks(longPressRunnable)
            longPressPending = false
        }
        if (key.letter == 0.toChar()) return
        if (gliding && phraseGlideEnabled) trackSpace(x, y, t)
        // Record the path (touches that start on a letter), keeping points at least 2 dp apart.
        var recorded = false
        if (glideCount in 1 until MAX_GLIDE_POINTS) {
            val px = glidePoints[2 * glideCount - 2]
            val py = glidePoints[2 * glideCount - 1]
            val ddx = x - px
            val ddy = y - py
            val d = sqrt(ddx * ddx + ddy * ddy)
            if (d >= MIN_SAMPLE_PX * density) {
                glidePoints[2 * glideCount] = x
                glidePoints[2 * glideCount + 1] = y
                glideTimes[glideCount] = t
                glideCount++
                glideLength += d
                recorded = true
            }
        }
        if (!gliding && glideLength > kw * 0.5f && listener?.isGlideAllowed() == true) {
            val under = g.keyAt(x, y)
            if (under != null && under !== key && under.letter != 0.toChar()) {
                gliding = true
                handler.removeCallbacks(longPressRunnable)
                longPressPending = false
                popup.dismiss()
                listener?.onGlideStart(glidePoints, glideTimes, glideCount)
                invalidate()
                return
            }
        }
        if (gliding) {
            if (recorded) {
                if (inSpace) bufferSpacePoint(x, y, t) else listener?.onGlidePoint(x, y, t)
            }
            invalidate()
        }
    }

    // ---- Phrase gliding ------------------------------------------------------------------------------

    /** Tracks the finger against the space bar; a dip past its middle or a rest there arms a word boundary. */
    private fun trackSpace(x: Float, y: Float, t: Long) {
        val space = spaceKey ?: return
        if (space.contains(x, y)) {
            if (!inSpace) {
                inSpace = true
                spaceArmed = false
                spaceEnterTime = t
                spaceBufCount = 0
                handler.removeCallbacks(spaceDwellRunnable)
                handler.postDelayed(spaceDwellRunnable, SPACE_DWELL_MS)
            }
            if (!spaceArmed && (y > space.centerY || t - spaceEnterTime >= SPACE_DWELL_MS)) {
                spaceArmed = true
                invalidate()
            }
        } else if (inSpace) {
            leaveSpace()
        }
    }

    private fun bufferSpacePoint(x: Float, y: Float, t: Long) {
        if (spaceBufCount >= SPACE_BUFFER) return
        spaceBufX[spaceBufCount] = x
        spaceBufY[spaceBufCount] = y
        spaceBufT[spaceBufCount] = t
        spaceBufCount++
    }

    /** Back out of the space bar: a boundary if the dip was deliberate, otherwise the held points are the word's. */
    private fun leaveSpace() {
        inSpace = false
        handler.removeCallbacks(spaceDwellRunnable)
        if (spaceArmed) {
            spaceArmed = false
            listener?.onGlideBoundary()
        } else {
            for (k in 0 until spaceBufCount) listener?.onGlidePoint(spaceBufX[k], spaceBufY[k], spaceBufT[k])
        }
        spaceBufCount = 0
        invalidate()
    }

    private fun resetSpaceState() {
        inSpace = false
        spaceArmed = false
        spaceBufCount = 0
        handler.removeCallbacks(spaceDwellRunnable)
    }

    private fun onLongPress() {
        longPressPending = false
        val p = activePointer
        if (p < 0) return
        val key = pointerKey[p] ?: return
        when {
            key.action == KeyAction.SPACE -> {
                pointerCancelled[p] = true
                listener?.onSpaceLongPress()
            }
            key.alternates.isNotEmpty() -> {
                val alts = if (shiftState != ShiftState.OFF && key.letter != 0.toChar()) key.shiftedAlternates else key.alternates
                popup.showAlternates(this, key, alts)
                popup.updateSelection(pointerLastX[p])
            }
        }
    }

    private fun finishPointer(id: Int, x: Float, y: Float, fromUp: Boolean, t: Long) {
        val key = pointerKey[id] ?: return
        val wasActive = id == activePointer
        if (wasActive) {
            handler.removeCallbacks(longPressRunnable)
            handler.removeCallbacks(repeatRunnable)
        }
        val l = listener
        val flick = wasActive && fromUp && !pointerCancelled[id] && isFlick(id, key, x, y, t)
        when {
            pointerCancelled[id] -> Unit
            flick -> {
                // A flick up types the corner character; a glide it started is dropped.
                if (gliding) {
                    resetSpaceState()
                    l?.onGlideCancel()
                }
                val alts = if (shiftState != ShiftState.OFF && key.letter != 0.toChar()) key.shiftedAlternates else key.alternates
                l?.onAlternate(key, alts[0])
            }
            wasActive && popup.isAlternates -> popup.selectedAlternate()?.let { l?.onAlternate(key, it) }
            wasActive && gliding -> {
                var trailingSpace = false
                if (inSpace) {
                    if (spaceArmed) {
                        trailingSpace = true
                    } else {
                        for (k in 0 until spaceBufCount) l?.onGlidePoint(spaceBufX[k], spaceBufY[k], spaceBufT[k])
                    }
                }
                resetSpaceState()
                l?.onGlideEnd(x, y, t, trailingSpace)
            }
            wasActive && cursorDrag -> Unit
            wasActive && deleteDrag -> l?.onDeleteWords(deleteWords)
            wasActive && repeatFired -> Unit
            key.action == KeyAction.SHIFT -> onShiftTap()
            else -> {
                lastTapX = pointerDownX[id]
                lastTapY = pointerDownY[id]
                l?.onKeyTap(key, shiftState)
            }
        }
        popup.dismiss()
        pointerKey[id] = null
        if (pressedCount > 0) pressedCount--
        if (wasActive) {
            activePointer = -1
            gliding = false
            glideCount = 0
            cursorDrag = false
            deleteDrag = false
            deleteWords = 0
            repeatFired = false
            longPressPending = false
        }
        invalidate()
    }

    /**
     * A flick up: quick (at most [FLICK_MS]), between half a row and 1.3 rows up, and nearly straight (less
     * than 0.6 of a key sideways), on a key with a corner character. Glides between letters a row apart
     * almost always move sideways too, and take longer.
     */
    private fun isFlick(id: Int, key: Key, x: Float, y: Float, t: Long): Boolean {
        val g = geometry ?: return false
        if (!flickEnabled || key.alternates.isEmpty() || popup.isAlternates || cursorDrag || deleteDrag || repeatFired) return false
        if (key.action != KeyAction.NONE || key.def.text == null) return false
        if (t - pointerDownT[id] > FLICK_MS) return false
        val up = pointerDownY[id] - y
        return up >= g.rowHeightPx * 0.5f && up <= g.rowHeightPx * 1.3f && abs(x - pointerDownX[id]) < g.letterKeyWidth * 0.6f
    }

    private fun onShiftTap() {
        val now = SystemClock.uptimeMillis()
        val next = when (shiftState) {
            ShiftState.OFF -> ShiftState.ON
            ShiftState.ON -> if (now - lastShiftTapTime < DOUBLE_TAP_MS) ShiftState.LOCKED else ShiftState.OFF
            ShiftState.LOCKED -> ShiftState.OFF
        }
        lastShiftTapTime = now
        setShift(next)
    }

    fun cancelAllTouches() {
        if (gliding) listener?.onGlideCancel()
        if (deleteDrag) listener?.onDeleteWords(0)
        deleteDrag = false
        deleteWords = 0
        resetSpaceState()
        handler.removeCallbacks(longPressRunnable)
        handler.removeCallbacks(repeatRunnable)
        popup.dismiss()
        for (i in 0 until MAX_POINTERS) pointerKey[i] = null
        pressedCount = 0
        activePointer = -1
        gliding = false
        glideCount = 0
        cursorDrag = false
        repeatFired = false
        invalidate()
    }

    override fun onDetachedFromWindow() {
        cancelAllTouches()
        super.onDetachedFromWindow()
    }

    companion object {
        private const val MAX_POINTERS = 10
        private const val MAX_GLIDE_POINTS = 2048
        private const val TRAIL_SEGMENTS = 60
        private const val MIN_SAMPLE_PX = 2f
        private const val LONG_PRESS_MS = 320L
        private const val FLICK_MS = 250L
        private const val DOUBLE_TAP_MS = 350L
        private const val REPEAT_DELAY_MS = 380L
        private const val REPEAT_START_MS = 80L
        private const val REPEAT_MIN_MS = 25L
        private const val REPEAT_ACCEL = 0.85f
        /** Resting this long in the space bar during a glide also ends a word. */
        private const val SPACE_DWELL_MS = 150L
        private const val SPACE_BUFFER = 256
    }
}
