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
        assertEquals(1, s["byLength"]!!.jsonObject["1-2"]!!.jsonObject["fixedFromStrip"]!!.jsonPrimitive.int)
        assertEquals(9, s["byLength"]!!.jsonObject["4"]!!.jsonObject["kept"]!!.jsonPrimitive.int)
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
