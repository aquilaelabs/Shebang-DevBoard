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

    @Test
    fun aTapGoesToTheKeyWhoseLandingSpotIsNearest() {
        // On the drawn line between o and p, a little to the o side: o, until this user is known to tap p low
        // and to the left.
        val x = (x('o') + x('p')) / 2 - 0.1f * model.pitchX
        assertEquals('o', model.nearestLetter(x, y('o')))
        val leansLeft = FloatArray(52).also { it['p' - 'a'] = -0.3f }
        assertEquals('p', TapModel(layout, density, leansLeft).nearestLetter(x, y('o')))
    }

    @Test
    fun aTapInTheMiddleOfAKeyIsThatKey() {
        for (c in 'a'..'z') if (layout.hasLetter(c)) assertEquals(c, model.nearestLetter(x(c), y(c)))
    }

    private val bar by lazy {
        val space = GlideBenchmarkTest.geometry.keys.first { it.action == dev.shebang.devboard.layout.KeyAction.SPACE }
        TapModel.Bar(space.left, space.right, space.centerY)
    }
    private val barTop get() = GlideBenchmarkTest.geometry.keys.first { it.action == dev.shebang.devboard.layout.KeyAction.SPACE }.top

    private fun odds(end: Float) = FloatArray(27) { if (it < 26) 1f / 26 else end }

    @Test
    fun aTapInTheMiddleOfALetterAboveTheBarStaysThatLetter() {
        assertTrue(!model.meansSpace(x('b'), y('b'), 'b', bar, odds(0.98f)))
        assertTrue(!model.meansSpace(x('n'), y('n'), 'n', bar, null))
    }

    @Test
    fun aTapLowOnALetterAboveTheBarIsASpaceWhereTheWordIsFinished() {
        val low = barTop - 0.15f * model.pitchY
        assertTrue(model.meansSpace(x('b'), low, 'b', bar, odds(0.9f)))
        assertTrue(!model.meansSpace(x('b'), low, 'b', bar, odds(0f)))
    }

    @Test
    fun theWordsOddsOfEndingMoveTheLineDownTheKey() {
        fun line(end: Float): Float {
            var y = barTop
            while (y > y('n') && model.meansSpace(x('n'), y, 'n', bar, odds(end))) y -= 1f
            return y
        }
        assertTrue(line(0.9f) < line(0.1f))
    }

    /** Odds where the word looks finished ([end]) and [c] continues no word the dictionary knows. */
    private fun oddsWithout(c: Char, end: Float): FloatArray {
        val p = FloatArray(27) { (1f - end) / 25f }
        p[c - 'a'] = 0.0001f
        p[dev.shebang.devboard.dict.LetterPrior.END] = end
        return p
    }

    @Test
    fun aLetterTheDictionaryDoesNotExpectStillWinsWhereTheTapIsOnIt() {
        // "tool" is a word and "toolchains" was not: a tap 0.15 of a key below the middle of c (the key's edge
        // is at 0.5). Before the floor, any tap below 0.01 here was a space.
        val onC = y('c') + 0.15f * model.pitchY
        assertTrue(!model.meansSpace(x('c'), onC, 'c', bar, oddsWithout('c', 0.9f)))
        val saved = TapModel.LETTER_FLOOR
        TapModel.LETTER_FLOOR = 0.0
        try {
            assertTrue("without the floor this was a space", model.meansSpace(x('c'), onC, 'c', bar, oddsWithout('c', 0.9f)))
        } finally {
            TapModel.LETTER_FLOOR = saved
        }
    }

    @Test
    fun someoneWhoTapsLowKeepsTheirLetters() {
        val low = FloatArray(52).also { o -> for (i in 26 until 52) o[i] = 0.2f }
        val lowTapper = TapModel(layout, density, low)
        // Where this person usually hits c: never a space, however finished the word looks.
        val theirC = y('c') + 0.2f * model.pitchY
        assertTrue(!lowTapper.meansSpace(x('c'), theirC, 'c', bar, odds(0.98f)))
    }
}
