package dev.shebang.devboard.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModifierStateTest {
    @Test
    fun tapIsOneShotAndConsumeClearsIt() {
        val s = ModifierState()
        s.tap(Modifier.CTRL, 1000)
        assertEquals(ModState.ONESHOT, s.state(Modifier.CTRL))
        val meta = s.consume()
        assertTrue(meta and KeyEvent.META_CTRL_ON != 0)
        assertTrue(meta and KeyEvent.META_CTRL_LEFT_ON != 0)
        assertEquals(ModState.OFF, s.state(Modifier.CTRL))
        assertEquals(0, s.consume())
    }

    @Test
    fun doubleTapLocksAndStaysAcrossKeys() {
        val s = ModifierState(doubleTapMs = 300)
        s.tap(Modifier.SHIFT, 1000)
        s.tap(Modifier.SHIFT, 1200)
        assertEquals(ModState.LOCKED, s.state(Modifier.SHIFT))
        s.consume()
        assertEquals(ModState.LOCKED, s.state(Modifier.SHIFT))
        s.tap(Modifier.SHIFT, 5000)
        assertEquals(ModState.OFF, s.state(Modifier.SHIFT))
    }

    @Test
    fun slowSecondTapTurnsOff() {
        val s = ModifierState(doubleTapMs = 300)
        s.tap(Modifier.ALT, 1000)
        s.tap(Modifier.ALT, 2000)
        assertEquals(ModState.OFF, s.state(Modifier.ALT))
    }

    @Test
    fun shiftOnlyIsDetected() {
        val s = ModifierState()
        s.tap(Modifier.SHIFT, 0)
        assertTrue(s.isShiftOnly)
        s.tap(Modifier.CTRL, 0)
        assertFalse(s.isShiftOnly)
        assertTrue(s.anyActive)
        s.clearAll()
        assertFalse(s.anyActive)
    }
}
