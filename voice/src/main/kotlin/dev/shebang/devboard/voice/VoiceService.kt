package dev.shebang.devboard.voice

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.Log
import java.util.concurrent.Executors

/**
 * Listens for the keyboard. Only an app signed with this add-on's key may use it: every message's sender is
 * checked. On START it records from the microphone, cuts the audio into utterances at pauses, transcribes
 * each with Whisper and sends the text back, until STOP (the text so far is still sent) or CANCEL, or until
 * [IDLE_STOP_MS] pass without speech. The keyboard binds with BIND_INCLUDE_CAPABILITIES, which lends this
 * process its foreground status, so the microphone is not silenced as it would be for a background app.
 */
class VoiceService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val transcriber = Executors.newSingleThreadExecutor()
    private var whisper: Whisper? = null
    private var client: Messenger? = null
    private var session: Session? = null

    private val incoming = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            val reply = msg.replyTo
            if (!allowed(msg.sendingUid)) {
                reply?.send(Message.obtain(null, VoiceProtocol.ERROR, VoiceProtocol.ERROR_NOT_ALLOWED, 0))
                return
            }
            when (msg.what) {
                VoiceProtocol.START -> start(reply)
                VoiceProtocol.STOP -> session?.stop(keepText = true)
                VoiceProtocol.CANCEL -> session?.stop(keepText = false)
            }
        }
    })

    override fun onBind(intent: Intent?): IBinder = incoming.binder

    override fun onUnbind(intent: Intent?): Boolean {
        session?.stop(keepText = false)
        return false
    }

    override fun onDestroy() {
        session?.stop(keepText = false)
        transcriber.execute { whisper?.close(); whisper = null }
        transcriber.shutdown()
        super.onDestroy()
    }

    /** Signed with the same key as this add-on (the keyboard), or this app itself. */
    private fun allowed(uid: Int): Boolean =
        uid == Process.myUid() || packageManager.checkSignatures(uid, Process.myUid()) == PackageManager.SIGNATURE_MATCH

    private fun send(what: Int, arg1: Int = 0, arg2: Int = 0, text: String? = null) {
        val c = client ?: return
        val m = Message.obtain(null, what, arg1, arg2)
        if (text != null) m.data = Bundle().apply { putString(VoiceProtocol.KEY_TEXT, text) }
        runCatching { c.send(m) }.onFailure { Log.w(TAG, "keyboard gone", it) }
    }

    private fun start(reply: Messenger?) {
        session?.stop(keepText = false)
        client = reply
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            send(VoiceProtocol.ERROR, VoiceProtocol.ERROR_NO_PERMISSION)
            return
        }
        // Debug builds only: a WAV left in the add-on's files stands in for the microphone (the emulator has none).
        val testInput = java.io.File(filesDir, TEST_INPUT)
        val source = if (BuildConfig.DEBUG && testInput.isFile) WavSource(testInput) else MicSource()
        session = Session(source).also { it.begin() }
    }

    /** One listening session: a recording thread feeding [Utterances], and transcription on [transcriber]. */
    private inner class Session(private val source: AudioSource) {
        @Volatile private var running = true
        @Volatile private var keep = true
        private var lastSpeech = System.currentTimeMillis()
        private val thread = HandlerThread("voice-record")
        private val utterances = Utterances { audio ->
            transcriber.execute {
                if (!keep) return@execute
                main.post { send(VoiceProtocol.STATE, VoiceProtocol.STATE_TRANSCRIBING) }
                val model = whisper ?: Whisper.load(Models.file(this@VoiceService).path).also { whisper = it }
                if (model == null) {
                    main.post { send(VoiceProtocol.ERROR, VoiceProtocol.ERROR_MODEL) }
                    return@execute
                }
                val t0 = System.nanoTime()
                val text = runCatching { model.transcribe(audio) }.onFailure { Log.e(TAG, "transcription failed", it) }.getOrNull().orEmpty()
                Log.i(TAG, "%.1f s of speech in %d ms".format(audio.size / 16000.0, (System.nanoTime() - t0) / 1_000_000))
                main.post {
                    if (keep && text.isNotBlank() && !isNoise(text)) send(VoiceProtocol.TEXT, text = text)
                    // Idle is sent once, after the last piece (queued when the recording ends).
                    if (running) send(VoiceProtocol.STATE, VoiceProtocol.STATE_LISTENING)
                }
            }
        }

        fun begin() {
            if (!source.open()) {
                send(VoiceProtocol.ERROR, VoiceProtocol.ERROR_MIC)
                running = false
                return
            }
            send(VoiceProtocol.STATE, VoiceProtocol.STATE_LISTENING)
            // Load the model while the user starts talking.
            transcriber.execute { if (whisper == null) whisper = Whisper.load(Models.file(this@VoiceService).path) }
            thread.start()
            Handler(thread.looper).post { loop() }
        }

        private fun loop() {
            val frame = FloatArray(Utterances.FRAME)
            var lastLevelSent = 0L
            while (running) {
                if (!source.read(frame)) break
                utterances.feed(frame)
                val now = System.currentTimeMillis()
                if (utterances.hearing) lastSpeech = now
                if (now - lastLevelSent > 100) {
                    lastLevelSent = now
                    val state = if (utterances.hearing) VoiceProtocol.STATE_HEARING else VoiceProtocol.STATE_LISTENING
                    val level = utterances.level
                    main.post { if (running) send(VoiceProtocol.STATE, state, level) }
                }
                if (now - lastSpeech > IDLE_STOP_MS) break
            }
            if (keep) utterances.flush()
            source.close()
            running = false
            main.post { if (session === this) session = null }
            transcriber.execute { main.post { send(VoiceProtocol.STATE, VoiceProtocol.STATE_IDLE) } }
            thread.quitSafely()
        }

        fun stop(keepText: Boolean) {
            keep = keepText
            running = false
        }
    }

    /** Whisper's stock outputs for silence and noise, which are not words the user said. */
    private fun isNoise(text: String): Boolean {
        val t = text.trim().lowercase().trim('.', ' ')
        return t.isEmpty() || t.startsWith("[") || t.startsWith("(") || t == "you" || t == "thank you"
    }

    /** Where samples come from: the microphone, or (in tests) a recording. */
    interface AudioSource {
        fun open(): Boolean
        /** Fills [frame] with the next samples in -1..1; false at the end. */
        fun read(frame: FloatArray): Boolean
        fun close()
    }

    /** A 16 kHz mono WAV played at the pace of speech, then a few seconds of quiet. Debug builds only. */
    private class WavSource(private val file: java.io.File) : AudioSource {
        private var samples = FloatArray(0)
        private var at = 0
        private val tail = Utterances.RATE * 2

        override fun open(): Boolean = runCatching { samples = Wav.read16kMono(file.readBytes()) }.isSuccess

        override fun read(frame: FloatArray): Boolean {
            if (at >= samples.size + tail) return false
            for (i in frame.indices) frame[i] = samples.getOrElse(at + i) { 0f }
            at += frame.size
            Thread.sleep(Utterances.FRAME_MS.toLong())
            return true
        }

        override fun close() = Unit
    }

    private class MicSource : AudioSource {
        private var record: AudioRecord? = null
        private val buf = ShortArray(Utterances.FRAME)

        @SuppressLint("MissingPermission") // Checked in start().
        override fun open(): Boolean {
            val min = AudioRecord.getMinBufferSize(Utterances.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) return false
            val r = runCatching {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, Utterances.RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, maxOf(min, Utterances.FRAME * 2 * 8))
            }.getOrNull() ?: return false
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                r.release()
                return false
            }
            r.startRecording()
            record = r
            return true
        }

        override fun read(frame: FloatArray): Boolean {
            val r = record ?: return false
            var got = 0
            while (got < buf.size) {
                val n = r.read(buf, got, buf.size - got)
                if (n <= 0) return false
                got += n
            }
            for (i in frame.indices) frame[i] = buf[i] / 32768f
            return true
        }

        override fun close() {
            record?.let { runCatching { it.stop() }; it.release() }
            record = null
        }
    }

    companion object {
        private const val TAG = "ShebangVoice"
        /** Listening stops after this long without speech. */
        const val IDLE_STOP_MS = 8_000L
        private const val TEST_INPUT = "test_input.wav"
    }
}
