package dev.shebang.devboard.glide

import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The diagnostics' glide outcomes: counts by outcome and word length, the last 500, kept across restarts. */
class GlideOutcomesTest {
    private val dir = Files.createTempDirectory("outcomes").toFile()
    private val file = File(dir, GlideOutcomes.FILE)

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun countsByOutcomeAndLength() {
        val o = GlideOutcomes(file)
        repeat(9) { o.add(GlideOutcomes.KEPT, 4) }
        o.add(GlideOutcomes.STRIP, 2)
        o.save()
        val s = GlideOutcomes(file).summary()
        assertEquals(10, s["all"]!!.jsonObject["glides"]!!.jsonPrimitive.int)
        assertEquals(9, s["all"]!!.jsonObject["kept"]!!.jsonPrimitive.int)
        assertEquals(1, s["byLength"]!!.jsonObject["2"]!!.jsonObject["fixedFromStrip"]!!.jsonPrimitive.int)
        assertEquals(9, s["byLength"]!!.jsonObject["4"]!!.jsonObject["kept"]!!.jsonPrimitive.int)
    }

    @Test
    fun strokesAreCountedInBandsOfReachAndDuration() {
        val o = GlideOutcomes(file)
        // A tap that slid: one letter, under a key, quick; deleted.
        o.add(GlideOutcomes.DELETED, 1, reach = 0.6f, durationMs = 90L)
        o.add(GlideOutcomes.KEPT, 2, reach = 1.4f, durationMs = 200L)
        o.add(GlideOutcomes.KEPT, 6, reach = 5f, durationMs = 900L)
        // Saved before strokes were kept: counted by length, not in the bands.
        o.add(GlideOutcomes.KEPT, 4)
        o.save()
        val s = GlideOutcomes(file).summary()
        assertEquals(4, s["all"]!!.jsonObject["glides"]!!.jsonPrimitive.int)
        assertEquals(3, s["strokesKnown"]!!.jsonPrimitive.int)
        val reach = s["byReach"]!!.jsonObject
        assertEquals(1, reach["under1Key"]!!.jsonObject["deleted"]!!.jsonPrimitive.int)
        assertEquals(1, reach["1to2Keys"]!!.jsonObject["kept"]!!.jsonPrimitive.int)
        assertEquals(0, reach["2to4Keys"]!!.jsonObject["glides"]!!.jsonPrimitive.int)
        assertEquals(1, reach["4KeysOrMore"]!!.jsonObject["kept"]!!.jsonPrimitive.int)
        val duration = s["byDuration"]!!.jsonObject
        assertEquals(1, duration["under150ms"]!!.jsonObject["deleted"]!!.jsonPrimitive.int)
        assertEquals(1, duration["150to300ms"]!!.jsonObject["glides"]!!.jsonPrimitive.int)
        assertEquals(1, duration["600msOrMore"]!!.jsonObject["glides"]!!.jsonPrimitive.int)
        val short = s["oneOrTwoLettersByReach"]!!.jsonObject
        assertEquals(1, short["under1Key"]!!.jsonObject["glides"]!!.jsonPrimitive.int)
        assertEquals(1, short["1to2Keys"]!!.jsonObject["glides"]!!.jsonPrimitive.int)
        assertEquals(0, short["4KeysOrMore"]!!.jsonObject["glides"]!!.jsonPrimitive.int)
        assertEquals(1, s["byLength"]!!.jsonObject["1"]!!.jsonObject["deleted"]!!.jsonPrimitive.int)
    }

    @Test
    fun outcomesSavedBeforeBandsStillRead() {
        // The old format: outcome * 32 + letters, nothing above.
        file.writeText("{\"codes\":[${GlideOutcomes.STRIP * 32 + 3},${GlideOutcomes.DELETED * 32 + 2}]}")
        val s = GlideOutcomes(file).summary()
        assertEquals(1, s["byLength"]!!.jsonObject["3"]!!.jsonObject["fixedFromStrip"]!!.jsonPrimitive.int)
        assertEquals(1, s["byLength"]!!.jsonObject["2"]!!.jsonObject["deleted"]!!.jsonPrimitive.int)
        assertEquals(0, s["strokesKnown"]!!.jsonPrimitive.int)
    }

    @Test
    fun reachIsTheFarthestPointFromTheStart() {
        assertEquals(5f, GlideOutcomes.reach(floatArrayOf(1f, 1f, 4f, 5f, 2f, 1f)), 1e-4f)
        assertEquals(0f, GlideOutcomes.reach(floatArrayOf(1f, 1f)), 0f)
        assertEquals(-1f, GlideOutcomes.reach(null), 0f)
    }

    @Test
    fun onlyTheLastFiveHundredCount() {
        val o = GlideOutcomes(file)
        repeat(600) { o.add(if (it < 100) GlideOutcomes.DELETED else GlideOutcomes.KEPT, 5) }
        val all = o.summary()["all"]!!.jsonObject
        assertEquals(GlideOutcomes.MAX, all["glides"]!!.jsonPrimitive.int)
        assertEquals(0, all["deleted"]!!.jsonPrimitive.int)
    }
}
