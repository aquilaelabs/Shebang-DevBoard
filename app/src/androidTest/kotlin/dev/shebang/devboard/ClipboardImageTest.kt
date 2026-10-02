package dev.shebang.devboard

import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Puts a picture on the clipboard, as an app copying an image does, so the clipboard panel can be checked by
 * eye on a device with the keyboard up. Run with -e clipimage 1; the picture is a drawn test card.
 */
@RunWith(AndroidJUnit4::class)
class ClipboardImageTest {
    @Test
    fun copyAPicture() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("clipimage") != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.filesDir, "clip_images").apply { mkdirs() }
        val file = File(dir, "test_card.png")
        val bmp = Bitmap.createBitmap(480, 270, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(27, 31, 42))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(123, 224, 166); textSize = 96f; isFakeBoldText = true }
        c.drawText("#!", 60f, 170f, p)
        p.textSize = 40f
        p.color = Color.WHITE
        c.drawText("Shebang DevBoard", 200f, 160f, p)
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, "dev.shebang.devboard.clips", file)
        val cm = context.getSystemService(ClipboardManager::class.java)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            cm.setPrimaryClip(ClipData.newUri(context.contentResolver, "Test card", uri))
        }
    }
}
