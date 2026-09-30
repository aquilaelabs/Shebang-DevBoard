package dev.shebang.devboard.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyEventMapperTest {
    @Test
    fun ctrlPlusLetterBecomesAKeyEvent() {
        val sticky = ModifierState().apply { tap(Modifier.CTRL, 0) }
        val d = KeyEventMapper.dispatchChar("c", sticky.consume())
        assertTrue(d is CharDispatch.Event)
        val plan = (d as CharDispatch.Event).plan
        assertEquals(KeyEvent.KEYCODE_C, plan.keyCode)
        assertTrue(plan.hasCtrl)
        assertTrue(!plan.hasShift)
    }

    @Test
    fun shiftPlusLetterJustTypesTheCapital() {
        val sticky = ModifierState().apply { tap(Modifier.SHIFT, 0) }
        val d = KeyEventMapper.dispatchChar("a", sticky.consume())
        assertEquals(CharDispatch.Text("A"), d)
    }

    @Test
    fun noModifiersTypesText() {
        assertEquals(CharDispatch.Text("x"), KeyEventMapper.dispatchChar("x", 0))
    }

    @Test
    fun ctrlPlusShiftedSymbolAddsShiftMeta() {
        val meta = ModifierState().apply { tap(Modifier.CTRL, 0) }.consume()
        val d = KeyEventMapper.dispatchChar("_", meta) as CharDispatch.Event
        assertEquals(KeyEvent.KEYCODE_MINUS, d.plan.keyCode)
        assertTrue(d.plan.hasCtrl)
        assertTrue(d.plan.hasShift)
    }

    @Test
    fun altPlusDigit() {
        val meta = ModifierState().apply { tap(Modifier.ALT, 0) }.consume()
        val d = KeyEventMapper.dispatchChar("3", meta) as CharDispatch.Event
        assertEquals(KeyEvent.KEYCODE_3, d.plan.keyCode)
        assertTrue(d.plan.hasAlt)
    }

    @Test
    fun barKeyCombinesOwnAndStickyModifiers() {
        val sticky = ModifierState().apply { tap(Modifier.SHIFT, 0) }
        val plan = KeyEventMapper.planForKey(KeyEvent.KEYCODE_C, KeyEventMapper.metaFor(listOf("ctrl")), sticky.consume())
        assertTrue(plan.hasCtrl)
        assertTrue(plan.hasShift)
        assertEquals(KeyEvent.KEYCODE_C, plan.keyCode)
    }

    @Test
    fun everyPrintableAsciiHasAKeycode() {
        for (c in 32..126) {
            val stroke = CharKeyCodes.forChar(c.toChar())
            assertTrue("no keycode for '${c.toChar()}'", stroke != null)
        }
        assertEquals(KeyStroke(KeyEvent.KEYCODE_A, true), CharKeyCodes.forChar('A'))
        assertEquals(KeyStroke(KeyEvent.KEYCODE_7, true), CharKeyCodes.forChar('&'))
    }
}
