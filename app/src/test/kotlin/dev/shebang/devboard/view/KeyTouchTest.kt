package dev.shebang.devboard.view

import dev.shebang.devboard.layout.FieldVariant
import dev.shebang.devboard.layout.Key
import dev.shebang.devboard.layout.KeyAction
import dev.shebang.devboard.layout.KeyboardGeometry
import dev.shebang.devboard.layout.LayoutParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The keyboard's touch logic without a View (R26): touches in, what they mean out. */
class KeyTouchTest {
    private val layout = LayoutParser.parse(File("src/main/assets/layouts/text_qwerty.json").readText())
    private val geometry = KeyboardGeometry(layout, FieldVariant.PLAIN, 1080, 4 * 156, false, 12f, 20f, 1)

    private val events = ArrayList<String>()
    private var clock = 1_000L
    private var shift = ShiftState.OFF
    private var glideAllowed = true
    private val pending = HashMap<KeyTouch.Timer, Long>()

    private val listener = object : KeyboardView.Listener {
        override fun onKeyDown(key: Key) = Unit
        override fun onKeyTap(key: Key, shift: ShiftState) { events += "tap ${name(key)}" }
        override fun onKeyRepeat(key: Key) { events += "repeat ${name(key)}" }
        override fun onBackspaceWordRepeat() { events += "word repeat" }
        override fun onAlternate(key: Key, text: String) { events += "alternate $text" }
        override fun onGlideStart(points: FloatArray, times: LongArray, count: Int) { events += "glide start" }
        override fun onGlidePoint(x: Float, y: Float, t: Long) = Unit
        override fun onGlideBoundary() { events += "glide boundary" }
        override fun onGlideEnd(x: Float, y: Float, t: Long, trailingSpace: Boolean) { events += "glide end" + if (trailingSpace) " with space" else "" }
        override fun onGlideCancel() { events += "glide cancel" }
        override fun onSpaceLongPress() { events += "space hold" }
        override fun onShiftLongPress() { events += "shift hold" }
        override fun onCursorMove(steps: Int, select: Boolean) { events += "cursor $steps" }
        override fun onDeleteWordsPreview(words: Int) { events += "preview $words" }
        override fun onDeleteWords(words: Int) { events += "delete $words" }
        override fun onShiftChanged(state: ShiftState) = Unit
        override fun isGlideAllowed() = glideAllowed
        override fun onKeyboardWidthChanged(widthPx: Int) = Unit
    }

    private val popup = object : KeyTouch.Popup {
        var alternates: List<String>? = null
        var selected = -1
        override val isAlternates get() = alternates != null
        override fun showPreview(key: Key, label: String) = Unit
        override fun showAlternates(key: Key, alternates: List<String>) {
            this.alternates = alternates
            events += "alternates ${alternates.joinToString(" ")}"
        }
        override fun updateSelection(x: Float) { selected = 0 }
        override fun clearSelection() { selected = -1 }
        override fun selectedAlternate() = alternates?.getOrNull(selected)
        override fun dismiss() {
            alternates = null
            selected = -1
        }
    }

    private val touch = KeyTouch(
        object : KeyTouch.Host {
            override val listener get() = this@KeyTouchTest.listener
            override val shiftState get() = shift
            override fun setShift(state: ShiftState) { shift = state }
            override fun now() = clock
            override fun post(timer: KeyTouch.Timer, delayMs: Long) { pending[timer] = clock + delayMs }
            override fun cancel(timer: KeyTouch.Timer) { pending.remove(timer) }
            override fun redraw() = Unit
        },
        popup,
        density = 3f,
    ).also { it.geometry = geometry }

    private fun name(key: Key) = if (key.letter != 0.toChar()) key.letter.toString() else key.action.name.lowercase()
    private fun letter(c: Char) = geometry.letterKey(c)!!
    private fun action(a: KeyAction) = geometry.keys.first { it.action == a }

    /** Lets [ms] pass, firing the timers that fall due, in order. */
    private fun pass(ms: Long) {
        val end = clock + ms
        while (true) {
            val next = pending.entries.filter { it.value <= end }.minByOrNull { it.value } ?: break
            pending.remove(next.key)
            clock = next.value
            touch.fire(next.key)
        }
        clock = end
    }

    private fun down(k: Key, id: Int = 0) = touch.down(id, k.centerX, k.centerY, clock)
    private fun moveTo(x: Float, y: Float, id: Int = 0) {
        clock += 16
        touch.move(id, x, y, clock)
    }
    private fun up(x: Float, y: Float, id: Int = 0) {
        clock += 16
        touch.up(id, x, y, clock)
    }
    private fun tap(k: Key, id: Int = 0) {
        down(k, id)
        pass(60)
        touch.up(id, k.centerX, k.centerY, clock)
    }

    /** Slides from [from] to [to] in small steps, as a finger reports it. */
    private fun slide(fromX: Float, fromY: Float, toX: Float, toY: Float, steps: Int = 10) {
        for (i in 1..steps) moveTo(fromX + (toX - fromX) * i / steps, fromY + (toY - fromY) * i / steps)
    }

    @Test
    fun aTapTypesItsKeyWhereItCameDown() {
        val e = letter('e')
        tap(e)
        assertEquals(listOf("tap e"), events)
        assertEquals(e.centerX, touch.lastTapX, 0f)
    }

    @Test
    fun aSecondFingerTypesTheFirstKeyAtOnce() {
        down(letter('a'), id = 0)
        down(letter('b'), id = 1)
        assertEquals(listOf("tap a"), events)
        up(letter('b').centerX, letter('b').centerY, id = 1)
        // The first finger lifting later types nothing more.
        up(letter('a').centerX, letter('a').centerY, id = 0)
        assertEquals(listOf("tap a", "tap b"), events)
    }

    @Test
    fun holdingALetterOffersItsCapitalFirst() {
        val e = letter('e')
        down(e)
        pass(KeyTouch.LONG_PRESS_MS)
        assertTrue(events.single().startsWith("alternates E"))
        up(e.centerX, e.centerY)
        assertEquals("alternate E", events.last())
    }

    @Test
    fun backDownOutOfTheAlternatesTypesTheKeyItself() {
        val e = letter('e')
        down(e)
        pass(KeyTouch.LONG_PRESS_MS)
        // Up into the row, then back down onto the key.
        moveTo(e.centerX, e.top + e.height * 0.1f)
        moveTo(e.centerX, e.top + e.height * 0.9f)
        up(e.centerX, e.top + e.height * 0.9f)
        assertEquals("tap e", events.last())
    }

    @Test
    fun aSlideAcrossLettersIsAGlideNotATap() {
        val q = letter('q')
        val t = letter('t')
        down(q)
        slide(q.centerX, q.centerY, t.centerX, t.centerY)
        assertTrue(touch.gliding)
        up(t.centerX, t.centerY)
        assertEquals(listOf("glide start", "glide end"), events)
        assertFalse(touch.gliding)
    }

    @Test
    fun whereGlidingIsOffASlideIsTheTapItStartedOn() {
        glideAllowed = false
        val q = letter('q')
        val t = letter('t')
        down(q)
        slide(q.centerX, q.centerY, t.centerX, t.centerY)
        up(t.centerX, t.centerY)
        assertEquals(listOf("tap q"), events)
    }

    @Test
    fun aGlideOwnsTheKeyboardUntilItEnds() {
        val q = letter('q')
        val t = letter('t')
        down(q)
        slide(q.centerX, q.centerY, t.centerX, t.centerY)
        down(letter('m'), id = 1)
        up(letter('m').centerX, letter('m').centerY, id = 1)
        up(t.centerX, t.centerY)
        assertEquals(listOf("glide start", "glide end"), events)
    }

    @Test
    fun cancellingAGlideTellsTheListener() {
        val q = letter('q')
        val t = letter('t')
        down(q)
        slide(q.centerX, q.centerY, t.centerX, t.centerY)
        touch.cancel()
        assertEquals(listOf("glide start", "glide cancel"), events)
    }

    @Test
    fun swipingLeftFromBackspaceDeletesWords() {
        val del = action(KeyAction.BACKSPACE)
        val kw = geometry.letterKeyWidth
        down(del)
        slide(del.centerX, del.centerY, del.centerX - kw * 2.5f, del.centerY)
        up(del.centerX - kw * 2.5f, del.centerY)
        val previews = events.filter { it.startsWith("preview") }
        assertTrue(previews.isNotEmpty())
        assertEquals("delete ${previews.last().removePrefix("preview ")}", events.last())
        assertFalse(events.any { it.startsWith("tap") })
    }

    @Test
    fun aTouchFromBackspaceThatLeavesItsRowDeletesNothing() {
        // B16: a glide that began just beside a letter, on backspace, deleted words.
        val del = action(KeyAction.BACKSPACE)
        val kw = geometry.letterKeyWidth
        down(del)
        slide(del.centerX, del.centerY, del.centerX - kw * 2f, del.centerY)
        assertTrue(events.any { it.startsWith("preview") && it != "preview 0" })
        slide(del.centerX - kw * 2f, del.centerY, del.centerX - kw * 3f, del.centerY - del.height * 1.2f)
        assertEquals("preview 0", events.last())
        up(del.centerX - kw * 3f, del.centerY - del.height * 1.2f)
        // The swipe ends having deleted no words, and nothing is typed.
        assertEquals("delete 0", events.last())
        assertFalse(events.any { (it.startsWith("delete") && it != "delete 0") || it.startsWith("tap") })
    }

    @Test
    fun aTouchFromSpaceThatLeavesItsRowTypesNothing() {
        val space = action(KeyAction.SPACE)
        down(space)
        slide(space.centerX, space.centerY, space.centerX, space.centerY - space.height * 1.5f)
        up(space.centerX, space.centerY - space.height * 1.5f)
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun slidingAlongTheSpaceBarMovesTheCursor() {
        val space = action(KeyAction.SPACE)
        val kw = geometry.letterKeyWidth
        down(space)
        slide(space.centerX, space.centerY, space.centerX + kw * 3f, space.centerY)
        up(space.centerX + kw * 3f, space.centerY)
        val steps = events.filter { it.startsWith("cursor") }.sumOf { it.removePrefix("cursor ").toInt() }
        assertTrue("moved $steps", steps >= 3)
        assertFalse(events.any { it.startsWith("tap") })
    }

    @Test
    fun holdingSpaceIsItsLongPressAndTypesNothing() {
        val space = action(KeyAction.SPACE)
        down(space)
        pass(KeyTouch.LONG_PRESS_MS)
        up(space.centerX, space.centerY)
        assertEquals(listOf("space hold"), events)
    }

    @Test
    fun holdingBackspaceRepeatsThenGoesByWords() {
        val del = action(KeyAction.BACKSPACE)
        down(del)
        pass(KeyTouch.REPEAT_DELAY_MS)
        assertEquals(listOf("repeat backspace"), events)
        pass(KeyTouch.WORDS_AFTER_MS)
        assertEquals("word repeat", events.last())
        up(del.centerX, del.centerY)
        assertFalse(events.any { it.startsWith("tap") })
    }

    @Test
    fun withWholeWordsOffBackspaceKeepsRepeatingLetters() {
        touch.holdDeletesWords = false
        val del = action(KeyAction.BACKSPACE)
        down(del)
        pass(KeyTouch.REPEAT_DELAY_MS + KeyTouch.WORDS_AFTER_MS + 500)
        assertTrue(events.isNotEmpty() && events.all { it == "repeat backspace" })
    }

    @Test
    fun shiftTapsGoOnThenCapsLockOnAQuickSecondTap() {
        val s = action(KeyAction.SHIFT)
        tap(s)
        assertEquals(ShiftState.ON, shift)
        pass(100)
        tap(s)
        assertEquals(ShiftState.LOCKED, shift)
        pass(1000)
        tap(s)
        assertEquals(ShiftState.OFF, shift)
        tap(s)
        pass(KeyTouch.DOUBLE_TAP_MS + 100)
        tap(s)
        assertEquals(ShiftState.OFF, shift)
    }

    @Test
    fun holdingShiftLocksCapitalsAndLettingGoChangesNothing() {
        val s = action(KeyAction.SHIFT)
        down(s)
        pass(KeyTouch.LONG_PRESS_MS)
        assertEquals(ShiftState.LOCKED, shift)
        up(s.centerX, s.centerY)
        assertEquals(ShiftState.LOCKED, shift)
        assertEquals(listOf("shift hold"), events)
    }

    @Test
    fun aDipIntoTheSpaceBarEndsAWordWhenPhraseGliding() {
        touch.phraseGlideEnabled = true
        val space = action(KeyAction.SPACE)
        val h = letter('h')
        val i = letter('i')
        down(h)
        slide(h.centerX, h.centerY, i.centerX, i.centerY)
        // Down past the bar's middle, then back up to a letter.
        val deep = space.centerY + space.height * 0.2f
        slide(i.centerX, i.centerY, space.centerX, deep)
        assertTrue(touch.spaceArmed)
        val t = letter('t')
        slide(space.centerX, deep, t.centerX, t.centerY)
        up(t.centerX, t.centerY)
        assertEquals(listOf("glide start", "glide boundary", "glide end"), events)
    }

    @Test
    fun liftingInTheArmedSpaceBarEndsTheGlideWithASpace() {
        touch.phraseGlideEnabled = true
        val space = action(KeyAction.SPACE)
        val h = letter('h')
        val i = letter('i')
        down(h)
        slide(h.centerX, h.centerY, i.centerX, i.centerY)
        val deep = space.centerY + space.height * 0.2f
        slide(i.centerX, i.centerY, space.centerX, deep)
        up(space.centerX, deep)
        assertEquals(listOf("glide start", "glide end with space"), events)
    }

    @Test
    fun withoutPhraseGlidingTheSpaceBarIsJustPartOfThePath() {
        val space = action(KeyAction.SPACE)
        val h = letter('h')
        val i = letter('i')
        down(h)
        slide(h.centerX, h.centerY, i.centerX, i.centerY)
        val deep = space.centerY + space.height * 0.2f
        slide(i.centerX, i.centerY, space.centerX, deep)
        assertFalse(touch.spaceArmed)
        up(space.centerX, deep)
        assertEquals(listOf("glide start", "glide end"), events)
    }

    @Test
    fun newKeysAbandonEveryTouch() {
        val q = letter('q')
        val t = letter('t')
        down(q)
        slide(q.centerX, q.centerY, t.centerX, t.centerY)
        touch.geometry = geometry
        up(t.centerX, t.centerY)
        assertEquals(listOf("glide start", "glide cancel"), events)
    }
}
