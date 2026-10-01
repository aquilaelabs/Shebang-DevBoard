package dev.shebang.devboard.glide

import dev.shebang.devboard.dict.SlipCost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Weighing a typed letter by where its tap came down. */
class TapModelTest {
    private val layout get() = GlideBenchmarkTest.layout
    private val density = GlideBenchmarkTest.DENSITY
    private val model by lazy { TapModel(layout, density) }

    private fun x(c: Char) = layout.centerX[c - 'a']
    private fun y(c: Char) = layout.centerY[c - 'a']

    @Test
    fun aTapOnTheLineBetweenTwoKeysMakesEitherNearlyFree() {
        // Halfway between where taps on each key centre (people tap a little left of and above the middle).
        val mx = (model.aimX('a')!! + model.aimX('s')!!) / 2
        val my = model.aimY('a')!!
        assertTrue(model.cost(mx, my, 's', 'a')!! < 0.01)
    }

    @Test
    fun aTapInTheMiddleOfItsKeyMakesTheNeighbourCostly() {
        // A neighbouring key is a whole pitch away: several nats, more than the flat neighbour cost.
        val c = model.cost(x('s'), y('s'), 's', 'a')!!
        assertTrue("cost $c", c > 3.0)
    }

    @Test
    fun slipCostFollowsTheTaps() {
        val onLine = SlipCost.Taps { i, a, b -> if (i == 1) model.cost((model.aimX('a')!! + model.aimX('s')!!) / 2, model.aimY('a')!!, a, b) else 0.0 }
        val centred = SlipCost.Taps { i, a, b -> if (i == 1) model.cost(x('s'), y('s'), a, b) else 0.0 }
        val keysOnly = SlipCost.cost("cst", "cat")
        assertTrue(SlipCost.cost("cst", "cat", onLine) < keysOnly)
        assertTrue(SlipCost.cost("cst", "cat", centred) > keysOnly)
    }

    @Test
    fun learnedOffsetsMoveWhereTheModelAims() {
        val offsets = FloatArray(52).also { it[0] = 0.2f }
        val moved = TapModel(layout, density, offsets)
        assertEquals(0.2f * moved.pitchX, moved.aimX('a')!! - model.aimX('a')!!, 0.01f)
    }

    @Test
    fun anObservationIsRelativeToWhereTheModelAims() {
        val o = model.observation(model.aimX('k')!!, model.aimY('k')!!, 'k')!!
        assertEquals(('k' - 'a').toFloat(), o[0])
        assertEquals(0f, o[1], 1e-4f)
        assertEquals(0f, o[2], 1e-4f)
    }
}
