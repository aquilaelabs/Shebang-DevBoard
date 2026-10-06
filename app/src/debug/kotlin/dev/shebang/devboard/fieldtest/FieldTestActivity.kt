package dev.shebang.devboard.fieldtest

import android.annotation.SuppressLint
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.sp
import java.util.Collections

/**
 * One real text field, of the kind named by the intent's [EXTRA_FIELD], filling the screen above the keyboard,
 * for FieldCompatibilityTest to type into through the real keyboard. It runs in its own process (":fields"), so
 * what the app saw is written to [stateFile] on every change for the test to read: the field's text, the editor
 * actions it ran, the keys a terminal got, and where an EditText's composing text starts. Debug builds only.
 */
class FieldTestActivity : ComponentActivity() {
    /** Editor actions the field received, by name ("search", "go", "send", "done"), in order. */
    private val actions: MutableList<String> = Collections.synchronizedList(mutableListOf())
    /** Characters the terminal field received as key events ('\n' for Enter). */
    private val keys = StringBuffer()
    private var focusedOnce = false
    lateinit var fieldView: View
        private set
    private var editText: EditText? = null
    private val composeText = mutableStateOf("")
    private val composeFocus = androidx.compose.ui.focus.FocusRequester()
    @Volatile
    private var webText = ""

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kind = intent.getStringExtra(EXTRA_FIELD) ?: MULTILINE
        fieldView = when (kind) {
            MULTILINE -> edit(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, EditorInfo.IME_ACTION_UNSPECIFIED)
            SENTENCES -> edit(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
                EditorInfo.IME_ACTION_UNSPECIFIED,
            )
            SEARCH -> edit(InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_SEARCH)
            URL -> edit(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI, EditorInfo.IME_ACTION_GO)
            EMAIL -> edit(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, EditorInfo.IME_ACTION_DONE)
            PASSWORD -> edit(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, EditorInfo.IME_ACTION_DONE)
            NUMBER -> edit(InputType.TYPE_CLASS_NUMBER, EditorInfo.IME_ACTION_DONE)
            COMPOSE_MULTILINE_SEARCH -> compose(singleLine = false, ImeAction.Search)
            COMPOSE_PLAIN -> compose(singleLine = false, ImeAction.Default)
            WEB_SEARCH -> web("<form onsubmit=\"Android.action('submit'); return false;\">" +
                "<input id=f type=search autocapitalize=off autofocus oninput=\"Android.text(this.value)\" style=\"$WEB_STYLE\"></form>")
            WEB_TEXTAREA -> web("<textarea id=f autocapitalize=off autofocus oninput=\"Android.text(this.value)\" style=\"$WEB_STYLE\"></textarea>")
            WEB_TERMINAL -> webPage("file:///android_asset/fieldtest/terminal.html")
            TERMINAL -> TerminalView(this)
            else -> error("unknown field $kind")
        }
        setContentView(fieldView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        save()
    }

    /** The test closes the activity by starting it again with [EXTRA_FINISH]. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_FINISH, false)) finish()
    }

    /** The field opens focused, with the keyboard up, once the window is on screen. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !focusedOnce) {
            focusedOnce = true
            // A web page lays its field out after loading; give it a moment.
            fieldView.postDelayed({ focusField() }, if (fieldView is WebView) 1_500L else 100L)
        }
    }

    /** Writes what the field holds and received, for the test in the keyboard's process. Main thread. */
    private fun save() {
        val json = org.json.JSONObject()
            .put("text", text())
            .put("actions", org.json.JSONArray(actions.toList()))
            .put("keys", keys.toString())
            .put("composingStart", composingStart())
            .put("selStart", editText?.selectionStart ?: -1)
            .put("selEnd", editText?.selectionEnd ?: -1)
        val f = stateFile(this)
        f.parentFile?.mkdirs()
        val tmp = java.io.File(f.path + ".tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(f)
    }

    /**
     * Focuses the field and asks for the keyboard, as an app whose field opens focused does. Main thread. The
     * test's taps did not reach the field under instrumentation, and how the keyboard is summoned is not what
     * these tests check.
     */
    private fun focusField() {
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        when (val v = fieldView) {
            is ComposeView -> composeFocus.requestFocus()
            is WebView -> {
                v.requestFocus()
                v.evaluateJavascript("(window.focusField || function () { document.getElementById('f').focus() })()", null)
                imm.showSoftInput(v, 0)
            }
            else -> {
                v.requestFocus()
                imm.showSoftInput(v, 0)
            }
        }
    }

    /** Where the keyboard's composing text starts in an EditText, or -1 when nothing is composing. Main thread. */
    private fun composingStart(): Int = editText?.let { BaseInputConnection.getComposingSpanStart(it.text) } ?: -1

    /** The field's text as the app holds it. Main thread. */
    private fun text(): String = editText?.text?.toString() ?: if (fieldView is WebView) webText else composeText.value

    private fun edit(type: Int, action: Int) = EditText(this).apply {
        inputType = type
        // The test's typing must not teach the keyboard anything on a real phone.
        imeOptions = action or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        textSize = 22f
        setOnEditorActionListener { _, id, _ ->
            actions += actionName(id)
            save()
            true
        }
        addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) = save()
        })
        // The composing span and the selection change without the text changing; keep them current.
        viewTreeObserver.addOnPreDrawListener { save(); true }
        editText = this
    }

    private fun compose(singleLine: Boolean, imeAction: ImeAction) = ComposeView(this).apply {
        setContent {
            BasicTextField(
                value = composeText.value,
                onValueChange = { composeText.value = it; save() },
                modifier = Modifier.fillMaxSize().focusRequester(composeFocus),
                singleLine = singleLine,
                textStyle = TextStyle(color = Color.White, fontSize = 22.sp),
                keyboardOptions = KeyboardOptions(imeAction = imeAction),
                keyboardActions = KeyboardActions(
                    onSearch = { actions += "search"; save() },
                    onGo = { actions += "go"; save() },
                    onSend = { actions += "send"; save() },
                    onDone = { actions += "done"; save() },
                ),
            )
        }
    }

    private fun web(body: String) = webView().apply {
        loadDataWithBaseURL("https://localhost/", "<html><body style=\"margin:0;background:#222\">$body</body></html>", "text/html", "utf-8", null)
    }

    /** A page from the debug build's assets (the xterm.js terminal). */
    private fun webPage(url: String) = webView().apply { loadUrl(url) }

    @SuppressLint("SetJavaScriptEnabled")
    private fun webView() = WebView(this).apply {
        settings.javaScriptEnabled = true
        addJavascriptInterface(object {
            @JavascriptInterface fun text(value: String) { webText = value; runOnUiThread { save() } }
            @JavascriptInterface fun action(name: String) { actions += name; runOnUiThread { save() } }
        }, "Android")
    }

    /** A terminal-like view: TYPE_NULL, so the keyboard sends key events, which are recorded as characters. */
    private inner class TerminalView(context: Context) : View(context) {
        init {
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundColor(android.graphics.Color.BLACK)
        }

        override fun onCheckIsTextEditor() = true

        override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
            outAttrs.inputType = InputType.TYPE_NULL
            outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN
            return BaseInputConnection(this, false)
        }

        override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
            when {
                keyCode == KeyEvent.KEYCODE_ENTER -> keys.append('\n')
                keyCode == KeyEvent.KEYCODE_DEL -> keys.append('\b')
                event.unicodeChar != 0 -> keys.append(event.unicodeChar.toChar())
            }
            save()
            return true
        }

        override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
            if (event.action == android.view.MotionEvent.ACTION_UP) {
                requestFocus()
                context.getSystemService(android.view.inputmethod.InputMethodManager::class.java).showSoftInput(this, 0)
            }
            return true
        }
    }

    companion object {
        const val EXTRA_FIELD = "field"
        const val EXTRA_FINISH = "finish"

        /** Where the activity writes what its field holds; the test reads it. Same app, so same files. */
        fun stateFile(context: Context) = java.io.File(context.filesDir, "fieldtest/state.json")
        const val MULTILINE = "multiline"
        /** A message box: multi-line, asking for a capital at the start of each sentence. */
        const val SENTENCES = "sentences"
        const val SEARCH = "search"
        const val URL = "url"
        const val EMAIL = "email"
        const val PASSWORD = "password"
        const val NUMBER = "number"
        const val COMPOSE_MULTILINE_SEARCH = "compose_multiline_search"
        const val COMPOSE_PLAIN = "compose_plain"
        const val WEB_SEARCH = "web_search"
        const val WEB_TEXTAREA = "web_textarea"
        /** xterm.js in a WebView: a browser terminal, whose field's text is everything the shell would get. */
        const val WEB_TERMINAL = "web_terminal"
        const val TERMINAL = "terminal"
        private const val WEB_STYLE = "width:100%;height:100vh;font-size:22px;box-sizing:border-box"

        fun actionName(id: Int) = when (id) {
            EditorInfo.IME_ACTION_SEARCH -> "search"
            EditorInfo.IME_ACTION_GO -> "go"
            EditorInfo.IME_ACTION_SEND -> "send"
            EditorInfo.IME_ACTION_DONE -> "done"
            EditorInfo.IME_ACTION_NEXT -> "next"
            else -> "action$id"
        }
    }
}
