package dev.shebang.devboard.ime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.util.Log

/**
 * The keyboard's side of voice typing: the Shebang Voice add-on (a separate app holding the microphone
 * permission) listens and sends back what was said. Bound only while listening, with
 * BIND_INCLUDE_CAPABILITIES so the add-on shares the keyboard's foreground status and its microphone is not
 * silenced. The message values mirror the add-on's VoiceProtocol; change both together.
 */
class VoiceClient(private val context: Context, private val listener: Listener) {
    interface Listener {
        fun onVoiceState(state: Int, level: Int)
        fun onVoiceText(text: String)
        fun onVoiceError(code: Int)
    }

    private val intent = Intent(SERVICE_ACTION).setPackage(PACKAGE)
    private var service: Messenger? = null
    private var bound = false
    private var startWhenBound = false
    /** Listening (or waiting for the last words to be written). */
    var active = false
        private set

    private val replies = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                STATE -> {
                    listener.onVoiceState(msg.arg1, msg.arg2)
                    if (msg.arg1 == STATE_IDLE) finish()
                }
                TEXT -> msg.data.getString(KEY_TEXT)?.let { listener.onVoiceText(it) }
                ERROR -> {
                    finish()
                    listener.onVoiceError(msg.arg1)
                }
            }
        }
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = Messenger(binder)
            if (startWhenBound) {
                startWhenBound = false
                send(START)
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            if (active) {
                finish()
                listener.onVoiceState(STATE_IDLE, 0)
            }
        }
    }

    /** Whether the add-on is installed. */
    fun installed(): Boolean = context.packageManager.queryIntentServices(intent, 0).isNotEmpty()

    fun start() {
        if (active) return
        active = true
        if (service != null) {
            send(START)
            return
        }
        startWhenBound = true
        bound = runCatching {
            // From Android 10, the add-on may use the microphone while the keyboard (on screen) is bound to it.
            val capabilities = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Context.BIND_INCLUDE_CAPABILITIES else 0
            context.bindService(intent, connection, Context.BIND_AUTO_CREATE or capabilities)
        }.getOrDefault(false)
        if (!bound) {
            active = false
            startWhenBound = false
            listener.onVoiceError(ERROR_UNAVAILABLE)
        }
    }

    /** Stops listening; what was said so far is still written. */
    fun stop() {
        if (active) send(STOP)
    }

    /** Stops listening and drops what was not written yet. */
    fun cancel() {
        if (!active) return
        send(CANCEL)
        finish()
        listener.onVoiceState(STATE_IDLE, 0)
    }

    private fun send(what: Int) {
        val s = service ?: return
        runCatching { s.send(Message.obtain(null, what).also { it.replyTo = replies }) }.onFailure { Log.w(TAG, "add-on gone", it) }
    }

    private fun finish() {
        active = false
        startWhenBound = false
        if (bound) {
            runCatching { context.unbindService(connection) }
            bound = false
        }
        service = null
    }

    /** Opens the add-on's request for the microphone (a keyboard cannot ask itself). */
    fun askForMicrophone() {
        val i = Intent().setClassName(PACKAGE, "$PACKAGE.MicPermissionActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(i) }.onFailure { Log.w(TAG, "could not ask for the microphone", it) }
    }

    companion object {
        private const val TAG = "DevBoard"
        const val PACKAGE = "dev.shebang.devboard.voice"
        /** Where the add-on is downloaded from. */
        const val RELEASES_URL = "https://github.com/aquilaelabs/Shebang-DevBoard/releases"
        private const val SERVICE_ACTION = "dev.shebang.devboard.voice.LISTEN"
        private const val START = 1
        private const val STOP = 2
        private const val CANCEL = 3
        private const val STATE = 10
        private const val TEXT = 11
        private const val ERROR = 12
        const val STATE_IDLE = 0
        const val STATE_LISTENING = 1
        const val STATE_HEARING = 2
        const val STATE_TRANSCRIBING = 3
        const val ERROR_NO_PERMISSION = 1
        const val ERROR_MODEL = 2
        const val ERROR_MIC = 3
        const val ERROR_NOT_ALLOWED = 4
        /** The keyboard could not reach the add-on. */
        const val ERROR_UNAVAILABLE = 100
        private const val KEY_TEXT = "text"
    }
}
