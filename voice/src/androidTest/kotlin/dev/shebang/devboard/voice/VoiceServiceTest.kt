package dev.shebang.devboard.voice

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The add-on as the keyboard uses it: speech cut at pauses and transcribed; the service's messages. */
@RunWith(AndroidJUnit4::class)
class VoiceServiceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target: Context = instrumentation.targetContext

    @Test
    fun aRecordingIsCutAtPausesAndTranscribed() {
        val samples = Wav.read16kMono(instrumentation.context.assets.open("jfk.wav").use { it.readBytes() })
        val pieces = ArrayList<FloatArray>()
        val u = Utterances { pieces += it }
        var i = 0
        while (i + Utterances.FRAME <= samples.size) {
            u.feed(samples.copyOfRange(i, i + Utterances.FRAME))
            i += Utterances.FRAME
        }
        u.flush()
        assertTrue("no utterances", pieces.isNotEmpty())
        val text = Whisper.load(Models.file(target).path, target.applicationInfo.nativeLibraryDir)!!.use { w -> pieces.joinToString(" ") { w.transcribe(it) } }
        android.util.Log.i("ShebangVoice", "${pieces.size} utterances: $text")
        val words = text.lowercase().replace(Regex("[^a-z ]"), " ").split(Regex(" +")).toSet()
        for (w in listOf("americans", "ask", "country")) assertTrue("'$w' missing from: $text", w in words)
    }

    @Test
    fun theServiceListensAndStops() {
        instrumentation.uiAutomation.grantRuntimePermission(target.packageName, Manifest.permission.RECORD_AUDIO)
        val messages = LinkedBlockingQueue<Message>()
        val thread = HandlerThread("replies").also { it.start() }
        val replies = Messenger(object : Handler(thread.looper) {
            override fun handleMessage(msg: Message) {
                messages += Message.obtain(msg)
            }
        })
        val bound = LinkedBlockingQueue<Messenger>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                bound += Messenger(service)
            }
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        val intent = Intent(VoiceProtocol.SERVICE_ACTION).setPackage(target.packageName)
        assertTrue(target.bindService(intent, connection, Context.BIND_AUTO_CREATE or Context.BIND_INCLUDE_CAPABILITIES))
        try {
            val service = bound.poll(5, TimeUnit.SECONDS)
            assertNotNull("not bound", service)
            service!!.send(Message.obtain(null, VoiceProtocol.START).also { it.replyTo = replies })
            val first = messages.poll(5, TimeUnit.SECONDS)
            assertNotNull("no reply", first)
            assertEquals("first reply ${first!!.what}/${first.arg1}", VoiceProtocol.STATE, first.what)
            assertEquals(VoiceProtocol.STATE_LISTENING, first.arg1)
            service.send(Message.obtain(null, VoiceProtocol.STOP).also { it.replyTo = replies })
            val deadline = System.currentTimeMillis() + 10_000
            var idle = false
            while (!idle && System.currentTimeMillis() < deadline) {
                val m = messages.poll(1, TimeUnit.SECONDS) ?: continue
                assertTrue("error ${m.arg1}", m.what != VoiceProtocol.ERROR)
                idle = m.what == VoiceProtocol.STATE && m.arg1 == VoiceProtocol.STATE_IDLE
            }
            assertTrue("never went idle", idle)
        } finally {
            target.unbindService(connection)
            thread.quitSafely()
        }
    }
}
