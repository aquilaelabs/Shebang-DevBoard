package dev.shebang.devboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** What the clipboard panel keeps: newest first, pinned first, a day at most unless pinned, twenty at most. */
class ClipboardHistoryTest {
    @get:Rule val folder = TemporaryFolder()
    private var now = 1_000_000_000L
    private fun history() = ClipboardHistory(folder.root.resolve(ClipboardHistory.FILE)) { now }

    @Test
    fun newestFirstAndACopyAgainMovesToTheTop() {
        val h = history()
        h.add("one")
        now += 1000
        h.add("two")
        now += 1000
        h.add("one")
        assertEquals(listOf("one", "two"), h.list().map { it.text })
    }

    @Test
    fun pinnedCopiesComeFirstAndOutliveTheDay() {
        val h = history()
        h.add("keep me")
        h.setPinned("keep me", true)
        now += 1000
        h.add("passing")
        assertEquals(listOf("keep me", "passing"), h.list().map { it.text })
        now += ClipboardHistory.KEEP_MS + 1
        assertEquals(listOf("keep me"), h.list().map { it.text })
    }

    @Test
    fun clearForgetsAllButThePinned() {
        val h = history()
        h.add("a")
        h.add("b")
        h.setPinned("a", true)
        h.clear()
        assertEquals(listOf("a"), h.list().map { it.text })
    }

    @Test
    fun atMostTwentyAndItSurvivesARestart() {
        val h = history()
        for (i in 1..25) {
            now += 1000
            h.add("copy $i")
        }
        val kept = history().list()
        assertEquals(ClipboardHistory.MAX_ITEMS, kept.size)
        assertEquals("copy 25", kept.first().text)
        assertTrue(kept.none { it.text == "copy 1" })
    }

    @Test
    fun blankAndHugeCopiesAreNotKept() {
        val h = history()
        h.add("   ")
        h.add("x".repeat(ClipboardHistory.MAX_CHARS + 1))
        assertTrue(h.list().isEmpty())
    }

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3, 4)

    @Test
    fun aPictureIsKeptOnceAsAFileBesideTheHistory() {
        val h = history()
        assertTrue(h.addImage(png, "image/png"))
        now += 1000
        h.add("text")
        now += 1000
        assertTrue(h.addImage(png, "image/png"))
        val items = h.list()
        assertEquals(2, items.size)
        val pic = items.first()
        assertTrue(pic.isImage)
        assertEquals("image/png", pic.mime)
        assertTrue(h.imageFile(pic)!!.readBytes().contentEquals(png))
        assertEquals(1, h.imageDir!!.listFiles()!!.size)
    }

    @Test
    fun removingOrClearingAPictureDeletesItsFile() {
        val h = history()
        h.addImage(png, "image/png")
        h.addImage(png + byteArrayOf(9), "image/jpeg")
        val keys = h.list().map { it.key }
        h.remove(keys[0])
        assertEquals(1, h.imageDir!!.listFiles()!!.size)
        h.clear()
        assertEquals(0, h.imageDir!!.listFiles()!!.size)
        assertTrue(h.list().isEmpty())
    }

    @Test
    fun tooBigOrNotAPictureIsRefused() {
        val h = history()
        assertTrue(!h.addImage(ByteArray(ClipboardHistory.MAX_IMAGE_BYTES + 1), "image/png"))
        assertTrue(!h.addImage(png, "text/plain"))
        assertTrue(h.list().isEmpty())
    }
}

