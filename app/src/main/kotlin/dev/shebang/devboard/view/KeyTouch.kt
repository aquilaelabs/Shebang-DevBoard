package dev.shebang.devboard.view

import dev.shebang.devboard.glide.TapModel
import dev.shebang.devboard.layout.Key
import dev.shebang.devboard.layout.KeyAction
import dev.shebang.devboard.layout.KeyboardGeometry
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * What the keyboard's touches mean, kept apart from the View so it can be tested (R26): touches go in
 * ([down], [move], [up], [cancel] and the timers firing), and what they mean comes out through the
 * [KeyboardView.Listener], the [Popup] and [Host]. Taps, the second finger that commits the first, hold to
 * repeat (whole words after a second on backspace), long-press alternates and backing down out of them,
 * swiping from backspace and along the space bar, a glide's start, its points and phrase-gliding dips into the
 * space bar, a touch on backspace or space that leaves its row (B16), shift taps, holds and double taps, and
 * a letter tap just above the bar taken as space.
 *
 * Touch paths are kept in primitive arrays, so a keystroke allocates nothing.
 */
class KeyTouch(private val host: Host, private val popup: Popup, private val density: Float) {
    /** What the touch logic needs from the keyboard around it. */
    interface Host {
        val listener: KeyboardView.Listener?
        val shiftState: ShiftState
        fun setShift(state: ShiftState)
        /** Now, on the clock touch times use. */
        fun now(): Long
        /** Runs [timer] after [delayMs], replacing any of the same kind already waiting. */
        fun post(timer: Timer, delayMs: Long)
        fun cancel(timer: Timer)
        /** Something drawn changed (pressed keys, the trail, the armed space bar). */
        fun redraw()
    }

    /** The key preview and the long-press alternates row. */
    interface Popup {
        val isAlternates: Boolean
        fun showPreview(key: Key, label: String)
        fun showAlternates(key: Key, alternates: List<String>)
        fun updateSelection(x: Float)
        fun clearSelection()
        fun selectedAlternate(): String?
        fun dismiss()
    }

    /** The delayed things a touch waits for; the host calls [fire] when one is due. */
    enum class Timer { LONG_PRESS, REPEAT, SPACE_DWELL }

    var geometry: KeyboardGeometry? = null
        set(value) {
            field = value
            spaceKey = value?.keys?.firstOrNull { it.action == KeyAction.SPACE && value.layout.composing }
            cancel()
        }

    var keyPreviewEnabled = true
    /** Holding backspace past [WORDS_AFTER_MS] deletes whole words (setting). */
    var holdDeletesWords = true
    /** Dipping into the space bar during a glide starts the next word. */
    var phraseGlideEnabled = false

    /**
     * Where this user's taps land on the letter keys. When set, a touch on a letter key means the letter
     * whose usual landing spot is nearest, so a habitual lean (taps a little left of each key) still types
     * the key meant. Other keys go by their drawn edges.
     */
    var tapModel: TapModel? = null

    /** How likely each letter a..z is next in the word being typed, weighed with where a tap landed; null: none. */
    var letterPrior: FloatArray? = null

    /** Whether a letter tap just above the space bar may be taken as a space ([spaceInstead]). */
    var spaceFromLetters = false
    /** Whether the last tap reported was a letter tap turned into a space. */
    var lastSpaceFromLetter = false
        private set

    /** Where the finger came down for the tap being reported to [KeyboardView.Listener.onKeyTap], in view pixels. */
    var lastTapX = Float.NaN
        private set
    var lastTapY = Float.NaN
        private set

    // Per-pointer touch state (index = pointer id, capped at MAX_POINTERS).
    private val pointerKey = arrayOfNulls<Key>(MAX_POINTERS)
    private val pointerDownX = FloatArray(MAX_POINTERS)
    private val pointerDownY = FloatArray(MAX_POINTERS)
    private val pointerDownT = LongArray(MAX_POINTERS)
    private val pointerLastX = FloatArray(MAX_POINTERS)
    private val pointerCancelled = BooleanArray(MAX_POINTERS)
    /** Pressed keys are drawn highlighted; pressedCount avoids scanning when nothing is down. */
    private var pressedCount = 0

    // Long-press / repeat / drag apply to one pointer at a time.
    private var activePointer = -1
    private var longPressPending = false
    /** Where the finger was when the alternates row opened, and whether it has reached up into the row since. */
    private var popupOpenY = 0f
    private var popupEntered = false
    private var repeatFired = false
    private var repeatInterval = 0L
    private var cursorDrag = false
    private var cursorDragAccum = 0f
    /** Swiping left from backspace, and how many words that would delete. */
    private var deleteDrag = false
    private var deleteWords = 0
    /**
     * The finger left the bottom row during a touch that began on backspace or the space bar: it is gliding, not
     * swiping, so that key's swipe is over for this touch and letting go does nothing (B16).
     */
    private var rowGestureOff = false

    // Glide path: interleaved x,y, with touch times.
    /** The glide's points so far, x,y interleaved; the first [glideCount] pairs are the path. */
    val glidePoints = FloatArray(2 * MAX_GLIDE_POINTS)
    private val glideTimes = LongArray(MAX_GLIDE_POINTS)
    var glideCount = 0
        private set
    var gliding = false
        private set
    private var glideLength = 0f
    private var lastShiftTapTime = 0L

    // Phrase gliding: points inside the space bar are held back until it is clear whether the dip was meant.
    /** The space bar of a layout that composes words (where phrase gliding and space-for-letter apply). */
    var spaceKey: Key? = null
        private set
    private var inSpace = false
    /** A dip into the space bar during a glide is deliberate: lifting or leaving now ends a word. */
    var spaceArmed = false
        private set
    private var spaceEnterTime = 0L
    private val spaceBufX = FloatArray(SPACE_BUFFER)
    private val spaceBufY = FloatArray(SPACE_BUFFER)
    private val spaceBufT = LongArray(SPACE_BUFFER)
    private var spaceBufCount = 0

    /** Whether [key] is held down now (drawn pressed). */
    fun isPressed(key: Key): Boolean {
        if (pressedCount == 0) return false
        for (i in 0 until MAX_POINTERS) if (pointerKey[i] === key && !pointerCancelled[i]) return true
        return false
    }

    // ---- Touches -------------------------------------------------------------------------------------

    /** A finger [id] came down at [x],[y] at time [t]. */
    fun down(id: Int, x: Float, y: Float, t: Long) {
        val g = geometry ?: return
        if (id < 0 || id >= MAX_POINTERS) return
        // A glide owns the keyboard until it ends: other fingers are ignored.
        if (gliding) return
        // A second finger commits the first key's tap immediately (fast typing).
        if (activePointer >= 0 && activePointer != id && !cursorDrag) {
            finishPointer(activePointer, pointerLastX[activePointer], -1f, t)
        }
        val key = resolveLetter(g.keyAt(x, y) ?: return, x, y)
        pointerKey[id] = key
        pointerDownX[id] = x
        pointerDownY[id] = y
        pointerDownT[id] = t
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
        rowGestureOff = false
        gliding = false
        glideCount = 0
        glideLength = 0f
        if (key.letter != 0.toChar()) {
            glidePoints[0] = x
            glidePoints[1] = y
            glideTimes[0] = t
            glideCount = 1
        }
        resetSpaceState()
        host.listener?.onKeyDown(key)
        host.cancel(Timer.LONG_PRESS)
        host.cancel(Timer.REPEAT)
        if (key.def.repeat) {
            repeatInterval = REPEAT_START_MS
            host.post(Timer.REPEAT, REPEAT_DELAY_MS)
        } else {
            host.post(Timer.LONG_PRESS, LONG_PRESS_MS)
        }
        if (keyPreviewEnabled && key.def.text != null && !key.def.functional) {
            popup.showPreview(key, if (host.shiftState != ShiftState.OFF) key.shiftedLabel else key.label)
        }
        host.redraw()
    }

    /** Finger [id] moved to [x],[y] at time [t]; batched samples are passed one by one, oldest first. */
    fun move(id: Int, x: Float, y: Float, t: Long) {
        val g = geometry ?: return
        if (id < 0 || id >= MAX_POINTERS || id != activePointer) return
        val key = pointerKey[id] ?: return
        moveTo(g, id, key, x, y, t)
        pointerLastX[id] = x
    }

    /** Finger [id] lifted at [x],[y] at time [t]. */
    fun up(id: Int, x: Float, y: Float, t: Long) {
        if (id < 0 || id >= MAX_POINTERS) return
        finishPointer(id, x, y, t)
    }

    /** Every touch is abandoned (the system cancelled them, or the keyboard changed under them). */
    fun cancel() {
        if (gliding) host.listener?.onGlideCancel()
        if (deleteDrag) host.listener?.onDeleteWords(0)
        deleteDrag = false
        deleteWords = 0
        rowGestureOff = false
        resetSpaceState()
        host.cancel(Timer.LONG_PRESS)
        host.cancel(Timer.REPEAT)
        popup.dismiss()
        for (i in 0 until MAX_POINTERS) pointerKey[i] = null
        pressedCount = 0
        activePointer = -1
        gliding = false
        glideCount = 0
        cursorDrag = false
        repeatFired = false
        host.redraw()
    }

    /** A timer the host was asked to run is due. */
    fun fire(timer: Timer) {
        when (timer) {
            Timer.LONG_PRESS -> onLongPress()
            Timer.REPEAT -> onRepeat()
            Timer.SPACE_DWELL -> if (gliding && inSpace && !spaceArmed) {
                spaceArmed = true
                host.redraw()
            }
        }
    }

    /** Shift tapped (or activated by a screen reader): on, then caps lock on a quick second tap, then off. */
    fun shiftTap() {
        val now = host.now()
        val next = when (host.shiftState) {
            ShiftState.OFF -> ShiftState.ON
            ShiftState.ON -> if (now - lastShiftTapTime < DOUBLE_TAP_MS) ShiftState.LOCKED else ShiftState.OFF
            ShiftState.LOCKED -> ShiftState.OFF
        }
        lastShiftTapTime = now
        host.setShift(next)
    }

    // ---- Moving --------------------------------------------------------------------------------------

    private fun moveTo(g: KeyboardGeometry, id: Int, key: Key, x: Float, y: Float, t: Long) {
        if (popup.isAlternates) {
            // Back down on the key after reaching up into the row (or well down from where the hold began):
            // nothing is highlighted, and letting go types the key itself.
            val popupBottom = key.top + key.height * POPUP_OVERLAP
            if (y < popupBottom) popupEntered = true
            val onKey = (popupEntered && y > popupBottom + key.height * BACK_ON_KEY) || y > popupOpenY + key.height * BACK_DOWN
            if (onKey) popup.clearSelection() else popup.updateSelection(x)
            return
        }
        val dx = x - pointerDownX[id]
        val dy = y - pointerDownY[id]
        val kw = g.letterKeyWidth
        // A touch that began on backspace or the space bar and then rises (or drops) into another row is a glide that
        // started a little off its first letter, not a swipe along the key: the swipe ends there, and whatever it
        // previewed is put back (B16: a glide from beside the m deleted five words).
        if ((key.action == KeyAction.BACKSPACE || key.action == KeyAction.SPACE) && !repeatFired) {
            if (rowGestureOff) return
            if (abs(dy) > key.height * LEAVE_ROW) {
                rowGestureOff = true
                host.cancel(Timer.REPEAT)
                host.cancel(Timer.LONG_PRESS)
                longPressPending = false
                if (deleteDrag && deleteWords != 0) {
                    deleteWords = 0
                    host.listener?.onDeleteWordsPreview(0)
                }
                return
            }
        }
        if (key.action == KeyAction.BACKSPACE) {
            // Swiping left from backspace deletes whole words: one more for each step left, none if back.
            if (!deleteDrag && !repeatFired && dx < -kw * 0.6f) {
                deleteDrag = true
                host.cancel(Timer.REPEAT)
                host.cancel(Timer.LONG_PRESS)
                longPressPending = false
            }
            if (deleteDrag) {
                val n = if (dx > -kw * 0.3f) 0 else 1 + ((-dx - kw * 0.6f) / (kw * 0.8f)).toInt().coerceAtLeast(0)
                if (n != deleteWords) {
                    deleteWords = n
                    host.listener?.onDeleteWordsPreview(n)
                }
            }
            return
        }
        if (key.action == KeyAction.SPACE) {
            if (!cursorDrag && abs(dx) > kw * 0.6f) {
                cursorDrag = true
                cursorDragAccum = 0f
                host.cancel(Timer.LONG_PRESS)
                longPressPending = false
            }
            if (cursorDrag) {
                cursorDragAccum += x - pointerLastX[id]
                val stepPx = kw * 0.55f
                val steps = (cursorDragAccum / stepPx).toInt()
                if (steps != 0) {
                    cursorDragAccum -= steps * stepPx
                    host.listener?.onCursorMove(steps, host.shiftState != ShiftState.OFF)
                }
            }
            return
        }
        // Moving a fifth of a key is a slide, not a hold: drop the pending long-press.
        if (longPressPending && (abs(dx) > kw * 0.2f || abs(dy) > kw * 0.2f)) {
            host.cancel(Timer.LONG_PRESS)
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
        if (!gliding && glideLength > kw * 0.5f && host.listener?.isGlideAllowed() == true) {
            val under = g.keyAt(x, y)
            if (under != null && under !== key && under.letter != 0.toChar()) {
                gliding = true
                host.cancel(Timer.LONG_PRESS)
                longPressPending = false
                popup.dismiss()
                host.listener?.onGlideStart(glidePoints, glideTimes, glideCount)
                host.redraw()
                return
            }
        }
        if (gliding) {
            if (recorded) {
                if (inSpace) bufferSpacePoint(x, y, t) else host.listener?.onGlidePoint(x, y, t)
            }
            host.redraw()
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
                host.post(Timer.SPACE_DWELL, SPACE_DWELL_MS)
            }
            if (!spaceArmed && (y > space.centerY || t - spaceEnterTime >= SPACE_DWELL_MS)) {
                spaceArmed = true
                host.redraw()
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
        host.cancel(Timer.SPACE_DWELL)
        if (spaceArmed) {
            spaceArmed = false
            host.listener?.onGlideBoundary()
        } else {
            for (k in 0 until spaceBufCount) host.listener?.onGlidePoint(spaceBufX[k], spaceBufY[k], spaceBufT[k])
        }
        spaceBufCount = 0
        host.redraw()
    }

    private fun resetSpaceState() {
        inSpace = false
        spaceArmed = false
        spaceBufCount = 0
        host.cancel(Timer.SPACE_DWELL)
    }

    // ---- Holding -------------------------------------------------------------------------------------

    private fun onRepeat() {
        val p = activePointer
        if (p < 0) return
        val key = pointerKey[p] ?: return
        repeatFired = true
        if (key.action == KeyAction.BACKSPACE && holdDeletesWords && host.now() - pointerDownT[p] >= WORDS_AFTER_MS) {
            // Held a while: whole words now, a little slower, so letting go stops at a word's edge.
            host.listener?.onBackspaceWordRepeat()
            host.post(Timer.REPEAT, WORD_REPEAT_MS)
            return
        }
        host.listener?.onKeyRepeat(key)
        repeatInterval = (repeatInterval * REPEAT_ACCEL).toLong().coerceAtLeast(REPEAT_MIN_MS)
        host.post(Timer.REPEAT, repeatInterval)
    }

    private fun onLongPress() {
        longPressPending = false
        val p = activePointer
        if (p < 0) return
        val key = pointerKey[p] ?: return
        val shift = host.shiftState
        when {
            key.action == KeyAction.SHIFT -> {
                // Holding shift locks capitals, as a double tap does; letting go then changes nothing.
                pointerCancelled[p] = true
                host.setShift(ShiftState.LOCKED)
                host.listener?.onShiftLongPress()
            }
            key.action == KeyAction.SPACE -> {
                pointerCancelled[p] = true
                host.listener?.onSpaceLongPress()
            }
            key.action == KeyAction.MODE_CODE || key.action == KeyAction.MODE_TEXT -> {
                pointerCancelled[p] = true
                host.listener?.onModeLongPress()
            }
            key.alternates.isNotEmpty() || (key.letter != 0.toChar() && shift == ShiftState.OFF) -> {
                // A lowercase letter offers its capital first, ahead of its accents and symbols.
                val alts = when {
                    key.letter == 0.toChar() -> key.alternates
                    shift != ShiftState.OFF -> key.shiftedAlternates
                    else -> listOf(key.shiftedLabel) + key.alternates
                }
                popup.showAlternates(key, alts)
                popup.updateSelection(pointerLastX[p])
                popupOpenY = pointerDownY[p]
                popupEntered = false
            }
        }
    }

    // ---- Letting go ----------------------------------------------------------------------------------

    private fun finishPointer(id: Int, x: Float, y: Float, t: Long) {
        val key = pointerKey[id] ?: return
        val wasActive = id == activePointer
        if (wasActive) {
            host.cancel(Timer.LONG_PRESS)
            host.cancel(Timer.REPEAT)
        }
        val l = host.listener
        when {
            pointerCancelled[id] -> Unit
            wasActive && popup.isAlternates -> {
                val alt = popup.selectedAlternate()
                if (alt != null) {
                    l?.onAlternate(key, alt)
                } else {
                    lastTapX = pointerDownX[id]
                    lastTapY = pointerDownY[id]
                    l?.onKeyTap(key, host.shiftState)
                }
            }
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
            wasActive && rowGestureOff -> Unit
            wasActive && repeatFired -> Unit
            key.action == KeyAction.SHIFT -> shiftTap()
            else -> {
                lastTapX = pointerDownX[id]
                lastTapY = pointerDownY[id]
                val space = spaceInstead(key, lastTapX, lastTapY)
                lastSpaceFromLetter = space != null
                l?.onKeyTap(space ?: key, host.shiftState)
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
            rowGestureOff = false
            repeatFired = false
            longPressPending = false
        }
        host.redraw()
    }

    // ---- Which key -----------------------------------------------------------------------------------

    /**
     * The space bar, when a tap that came down on a letter of the row above it more likely meant the bar:
     * thumbs reaching for space often land on the letters above it, and more readily where the word typed so
     * far is likely finished ([TapModel.meansSpace]). Decided when the tap ends, so a glide can still start
     * on those letters; a tap on the bar itself always stays a space.
     */
    private fun spaceInstead(key: Key, x: Float, y: Float): Key? {
        if (!spaceFromLetters || key.letter == 0.toChar()) return null
        val space = spaceKey ?: return null
        val model = tapModel ?: return null
        if (y < space.top - space.height || y > space.top) return null
        return if (model.meansSpace(x, y, key.letter, TapModel.Bar(space.left, space.right, space.centerY), letterPrior)) space else null
    }

    private fun resolveLetter(hit: Key, x: Float, y: Float): Key {
        if (hit.letter == 0.toChar()) return hit
        val g = geometry ?: return hit
        val c = tapModel?.nearestLetter(x, y, prior = letterPrior) ?: return hit
        if (c == hit.letter) return hit
        return g.letterKey(c) ?: hit
    }

    companion object {
        const val MAX_POINTERS = 10
        /** How far (in rows) a touch on backspace or space may stray up or down before it counts as a glide. */
        private const val LEAVE_ROW = 0.6f
        private const val MAX_GLIDE_POINTS = 2048
        private const val MIN_SAMPLE_PX = 2f
        const val LONG_PRESS_MS = 320L
        /** How far the alternates row reaches down over its key, in key heights ([KeyPopup] places it so). */
        private const val POPUP_OVERLAP = 0.35f
        /** Below the row by this much (key heights), a finger that reached up into it is back on its key. */
        private const val BACK_ON_KEY = 0.15f
        /** Down by this much from where the hold began (key heights), the finger has gone back to its key. */
        private const val BACK_DOWN = 0.35f
        const val DOUBLE_TAP_MS = 350L
        const val REPEAT_DELAY_MS = 380L
        /** How long backspace is held before it deletes whole words, and how often one goes then. */
        const val WORDS_AFTER_MS = 1000L
        const val WORD_REPEAT_MS = 150L
        private const val REPEAT_START_MS = 80L
        private const val REPEAT_MIN_MS = 25L
        private const val REPEAT_ACCEL = 0.85f
        /** Resting this long in the space bar during a glide also ends a word. */
        const val SPACE_DWELL_MS = 150L
        private const val SPACE_BUFFER = 256
    }
}
