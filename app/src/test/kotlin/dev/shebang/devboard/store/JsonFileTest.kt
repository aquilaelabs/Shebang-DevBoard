package dev.shebang.devboard.store

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Saved documents survive interrupted writes, and one that cannot be read is set aside, never overwritten. */
class JsonFileTest {
    @Serializable
    data class Doc(val words: List<String> = emptyList())

    private val dir = Files.createTempDirectory("jsonfile").toFile()
    private val json = Json { ignoreUnknownKeys = true }
    private val store = JsonFile(File(dir, "doc.json"))

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun whatIsWrittenIsRead() {
        store.write(Doc.serializer(), json, Doc(listOf("kubectl")))
        assertEquals(Doc(listOf("kubectl")), store.read(Doc.serializer(), json))
        assertFalse(File(dir, "doc.json.tmp").exists())
    }

    @Test
    fun anUnreadableFileIsSetAsideAndTheNextSaveLeavesIt() {
        store.file.writeText("{\"words\": [\"kubec")
        assertNull(store.read(Doc.serializer(), json))
        assertEquals("{\"words\": [\"kubec", store.unreadable.readText())
        // The store starts over beside it; the damaged copy stays for a later version to read.
        store.write(Doc.serializer(), json, Doc(listOf("new")))
        assertEquals(Doc(listOf("new")), store.read(Doc.serializer(), json))
        assertTrue(store.unreadable.exists())
    }

    @Test
    fun aSaveStoppedBeforeItsRenameIsRecovered() {
        File(dir, "doc.json.tmp").writeText("{\"words\": [\"kept\"]}")
        assertEquals(Doc(listOf("kept")), store.read(Doc.serializer(), json))
    }

    @Test
    fun aHalfWrittenTemporaryFileIsIgnored() {
        store.write(Doc.serializer(), json, Doc(listOf("good")))
        File(dir, "doc.json.tmp").writeText("{\"wor")
        assertEquals(Doc(listOf("good")), store.read(Doc.serializer(), json))
    }

    @Test
    fun deletingLeavesNothingBehind() {
        store.file.writeText("garbage")
        store.read(Doc.serializer(), json)
        store.write(Doc.serializer(), json, Doc(listOf("x")))
        store.delete()
        assertEquals(0, dir.listFiles()!!.size)
    }
}
