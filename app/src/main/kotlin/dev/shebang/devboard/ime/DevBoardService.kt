package dev.shebang.devboard.ime

import android.content.Context
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.shebang.devboard.dict.DictionaryLoader
import dev.shebang.devboard.dict.Suggester
import dev.shebang.devboard.glide.GlideDecoder
import dev.shebang.devboard.glide.IdealPathCache
import dev.shebang.devboard.glide.KeyLayoutModel
import dev.shebang.devboard.input.CharDispatch
import dev.shebang.devboard.input.KeyEventMapper
import dev.shebang.devboard.input.KeyEventPlan
import dev.shebang.devboard.input.KeySender
import dev.shebang.devboard.input.Modifier
import dev.shebang.devboard.input.ModifierState
import dev.shebang.devboard.layout.BarConfig
import dev.shebang.devboard.layout.BarItem
import dev.shebang.devboard.layout.FieldVariant
import dev.shebang.devboard.layout.Key
import dev.shebang.devboard.layout.KeyAction
import dev.shebang.devboard.layout.KeyCodeNames
import dev.shebang.devboard.layout.KeyboardGeometry
import dev.shebang.devboard.layout.LayoutDef
import dev.shebang.devboard.settings.Settings
import dev.shebang.devboard.settings.SettingsRepository
import dev.shebang.devboard.view.ImeRootView
import dev.shebang.devboard.view.KeyPopup
import dev.shebang.devboard.view.KeyboardTheme
import dev.shebang.devboard.view.KeyboardView
import dev.shebang.devboard.view.ShiftState
import dev.shebang.devboard.view.TerminalBarView
import dev.shebang.devboard.view.TopStripView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

class DevBoardService : InputMethodService(), KeyboardView.Listener, TerminalBarView.Listener, TextInputController.Ui {

    private enum class Mode { TEXT, CODE }

    private lateinit var layouts: LayoutRepository
    private lateinit var dictLoader: DictionaryLoader
    private lateinit var feedback: Feedback
    private lateinit var text: TextInputController
    private val main = Handler(Looper.getMainLooper())
    private val background = Executors.newSingleThreadExecutor { r -> Thread(r, "devboard-bg").apply { isDaemon = true } }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var settingsJob: Job? = null

    private var settings = Settings()
    private var theme: KeyboardTheme? = null
    private var barConfig: BarConfig? = null
    private val modifiers = ModifierState()

    private var root: ImeRootView? = null
    private var strip: TopStripView? = null
    private var keyboard: KeyboardView? = null
    private var popup: KeyPopup? = null

    private var mode = Mode.TEXT
    private var field: FieldInfo = FieldInfo.from(null)
    private var geometry: KeyboardGeometry? = null
    private var geometryVersion = 0
    private var glideModel: KeyLayoutModel? = null
    private val idealPaths = IdealPathCache()
    private var glideDecoder: GlideDecoder? = null
    private var autoShifted = false

    override fun onCreate() {
        super.onCreate()
        layouts = LayoutRepository(this)
        dictLoader = DictionaryLoader(this)
        feedback = Feedback(this)
        text = TextInputController({ currentInputConnection }, this, background, main)
        settingsJob = scope.launch {
            SettingsRepository.get(this@DevBoardService).settings.collectLatest { applySettings(it) }
        }
    }

    override fun onDestroy() {
        settingsJob?.cancel()
        scope.cancel()
        background.shutdownNow()
        super.onDestroy()
    }

    // ---- Settings ------------------------------------------------------------------------------------

    private fun applySettings(s: Settings) {
        val heightChanged = s.heightScale != settings.heightScale || s.numberRow != settings.numberRow
        val themeChanged = s.theme != settings.theme || s.dynamicColor != settings.dynamicColor || theme == null
        val barChanged = s.barJson != settings.barJson || barConfig == null
        settings = s
        feedback.settings = s
        text.settings = s
        if (themeChanged) applyTheme()
        if (barChanged) {
            barConfig = s.barJson?.let { runCatching { BarConfig.parse(it) }.getOrNull() } ?: layouts.defaultBar
            strip?.bar?.setConfig(barConfig!!)
        }
        strip?.setMode(s.stripMode)
        keyboard?.keyPreviewEnabled = s.keyPreview
        keyboard?.glideTrailEnabled = s.glideTrail
        if (heightChanged) rebuildGeometry()
    }

    private fun applyTheme() {
        val t = KeyboardTheme.build(this, settings)
        theme = t
        keyboard?.theme = t
        popup?.setTheme(t)
        strip?.setTheme(t)
        root?.setBackgroundColor(t.background)
    }

    // ---- Views ---------------------------------------------------------------------------------------

    override fun onCreateInputView(): View {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val s = TopStripView(this)
        val k = KeyboardView(this)
        val p = KeyPopup(this)
        k.popup = p
        k.listener = this
        s.bar.listener = this
        s.suggestions.onSuggestion = { word -> text.pickCandidate(word); afterEdit() }
        column.addView(s, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        column.addView(k, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        // The popup overlay covers strip and keys so a top-row preview can draw above its key.
        val container = ImeRootView(this, column, p)
        // The IME window hosts the system's navigation bar (back and IME-switcher buttons) at its bottom on
        // recent Android; systemBars() reports that height, so pad the keys above it.
        ViewCompat.setOnApplyWindowInsetsListener(container) { v, insets ->
            val bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
            if (v.paddingBottom != bottom) v.setPadding(0, 0, 0, bottom)
            insets
        }
        root = container
        strip = s
        keyboard = k
        popup = p
        applyTheme()
        barConfig?.let { s.bar.setConfig(it) }
        s.setMode(settings.stripMode)
        k.keyPreviewEnabled = settings.keyPreview
        k.glideTrailEnabled = settings.glideTrail
        rebuildGeometry()
        return container
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyTheme()
        rebuildGeometry()
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        // Only the keyboard area takes touches; the rest of the screen stays with the app.
        root?.let {
            outInsets.contentTopInsets = it.top
            outInsets.visibleTopInsets = it.top
            outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_CONTENT
        }
    }

    private fun currentLayout(): LayoutDef = when {
        mode == Mode.CODE -> layouts.code
        field.isNumeric -> layouts.numeric
        else -> layouts.text
    }

    private fun rebuildGeometry() {
        val k = keyboard ?: return
        val layout = currentLayout()
        val dm = resources.displayMetrics
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val baseRow = (if (landscape) 40f else 52f) * dm.density * settings.heightScale
        val rowScale = if (layout.mode == "code") 0.86f else 1f
        val numberRow = settings.numberRow && layout.mode == "text"
        val rows = layout.rows.size + (if (numberRow && layout.numberRow != null) 1 else 0)
        val maxHeight = dm.heightPixels * (if (landscape) 0.6f else 0.5f)
        val height = (rows * baseRow * rowScale).coerceAtMost(maxHeight).toInt()
        // The IME window can be narrower than the display (landscape cutout insets), so follow the view.
        val width = if (k.width > 0) k.width else dm.widthPixels
        val g = KeyboardGeometry(
            layout = layout,
            variant = field.variant,
            widthPx = width,
            heightPx = height,
            numberRow = numberRow,
            horizontalGapPx = 5f * dm.density,
            verticalGapPx = 8f * dm.density,
            version = ++geometryVersion,
        )
        geometry = g
        glideModel = null
        k.setGeometry(g)
        k.requestLayout()
        root?.let { ViewCompat.requestApplyInsets(it) }
        strip?.setCodeMode(mode == Mode.CODE)
    }

    private fun glideModelFor(g: KeyboardGeometry): KeyLayoutModel {
        glideModel?.let { if (it.version == g.version) return it }
        val model = KeyLayoutModel.build(g.version, g.letterKeyWidth, g.rowHeightPx) { c -> g.letterKey(c)?.let { it.centerX to it.centerY } }
        glideModel = model
        return model
    }

    // ---- Input lifecycle -----------------------------------------------------------------------------

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        field = FieldInfo.from(info)
        text.startInput(field)
        modifiers.clearAll()
        strip?.bar?.updateModifiers(modifiers)
        keyboard?.enterLabel = field.enterLabel
        keyboard?.setShift(ShiftState.OFF, notify = false)
        autoShifted = false
        rebuildGeometry()
        if (field.allowsComposing && glideDecoder == null) {
            dictLoader.ensureLoading { dict ->
                main.post {
                    if (glideDecoder == null) {
                        text.suggester = Suggester(dict)
                        glideDecoder = GlideDecoder(dict, idealPaths)
                    }
                }
            }
        }
        updateAutoCaps()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        text.finishComposing()
        keyboard?.cancelAllTouches()
        modifiers.clearAll()
        strip?.bar?.updateModifiers(modifiers)
        super.onFinishInputView(finishingInput)
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        text.onSelectionChanged(newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        // Mid-word the caps mode cannot change; asking the editor (an IPC) is only worth it at a boundary.
        if (!text.isComposing) updateAutoCaps()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    private val ic: InputConnection? get() = currentInputConnection

    private fun updateAutoCaps() {
        val k = keyboard ?: return
        if (!settings.autoCaps || !field.allowsAutoCaps || mode == Mode.CODE) {
            if (autoShifted) {
                autoShifted = false
                k.setShift(ShiftState.OFF, notify = false)
            }
            return
        }
        if (k.shiftState == ShiftState.LOCKED) return
        val caps = ic?.getCursorCapsMode(field.inputType) ?: 0
        if (caps != 0) {
            if (k.shiftState == ShiftState.OFF) {
                autoShifted = true
                k.setShift(ShiftState.ON, notify = false)
            }
        } else if (autoShifted) {
            autoShifted = false
            k.setShift(ShiftState.OFF, notify = false)
        }
    }

    /**
     * Housekeeping after an edit. The caps-mode query is an IPC to the editor, so it runs only at word
     * boundaries: never after a letter that is still being composed.
     */
    private fun afterEdit(wordBoundary: Boolean = true) {
        if (wordBoundary || !text.isComposing) updateAutoCaps()
    }

    // ---- KeyboardView.Listener -----------------------------------------------------------------------

    override fun onKeyDown(key: Key) {
        feedback.keyPress(key.action)
    }

    override fun onKeyTap(key: Key, shift: ShiftState) {
        when (key.action) {
            KeyAction.SHIFT -> return
            KeyAction.BACKSPACE -> {
                if (sendWithStickyModifiers(KeyEvent.KEYCODE_DEL)) return
                text.backspace()
            }
            KeyAction.ENTER -> {
                if (sendWithStickyModifiers(KeyEvent.KEYCODE_ENTER)) return
                text.enter()
            }
            KeyAction.SPACE -> {
                if (sendWithStickyModifiers(KeyEvent.KEYCODE_SPACE)) return
                if (mode == Mode.CODE || !field.allowsComposing) {
                    text.finishComposing()
                    ic?.commitText(" ", 1)
                } else text.space()
            }
            KeyAction.MODE_CODE -> switchMode(Mode.CODE)
            KeyAction.MODE_TEXT -> switchMode(Mode.TEXT)
            KeyAction.NONE -> {
                if (key.keyCode != 0) {
                    text.finishComposing()
                    KeySender.send(ic, KeyEventMapper.planForKey(key.keyCode, 0, modifiers.consume()))
                    strip?.bar?.updateModifiers(modifiers)
                } else {
                    val t = (if (shift != ShiftState.OFF && mode == Mode.TEXT) key.shiftedText else key.def.text) ?: return
                    typeFromKeyboard(t)
                    if (shift == ShiftState.ON && !autoShifted) keyboard?.releaseOneShotShift()
                    if (autoShifted) {
                        autoShifted = false
                        keyboard?.setShift(ShiftState.OFF, notify = false)
                    }
                    afterEdit(wordBoundary = !key.def.isLetter)
                    return
                }
            }
        }
        afterEdit()
    }

    /** Ctrl/Alt/Meta held on the bar turn a main-keyboard key into a KeyEvent. Returns true when handled. */
    private fun sendWithStickyModifiers(keyCode: Int): Boolean {
        if (!modifiers.anyActive || modifiers.isShiftOnly) return false
        text.finishComposing()
        KeySender.send(ic, KeyEventPlan(keyCode, modifiers.consume()))
        strip?.bar?.updateModifiers(modifiers)
        return true
    }

    private fun typeFromKeyboard(t: String) {
        if (modifiers.anyActive) {
            when (val d = KeyEventMapper.dispatchChar(t, modifiers.consume())) {
                is CharDispatch.Text -> text.typeText(d.text)
                is CharDispatch.Event -> {
                    text.finishComposing()
                    KeySender.send(ic, d.plan)
                }
            }
            strip?.bar?.updateModifiers(modifiers)
        } else {
            text.typeText(t)
        }
    }

    override fun onKeyRepeat(key: Key) {
        when (key.action) {
            KeyAction.BACKSPACE -> text.backspace()
            else -> if (key.keyCode != 0) {
                text.finishComposing()
                KeySender.sendPlain(ic, key.keyCode)
            }
        }
        feedback.keyPress(key.action)
        afterEdit()
    }

    override fun onAlternate(key: Key, text: String) {
        typeFromKeyboard(text)
        keyboard?.releaseOneShotShift()
        afterEdit()
    }

    override fun onGlide(points: FloatArray, count: Int) {
        val g = geometry ?: return
        val decoder = glideDecoder ?: return
        if (!settings.glide || !field.allowsGlide || mode != Mode.TEXT) return
        val copy = points.copyOf(2 * count)
        val model = glideModelFor(g)
        val capitalize = keyboard?.shiftState != ShiftState.OFF
        val started = SystemClock.uptimeMillis()
        background.execute {
            val results = decoder.decode(copy, count, model)
            val elapsed = SystemClock.uptimeMillis() - started
            if (elapsed > 100) Log.w(TAG, "glide decode took ${elapsed}ms")
            main.post {
                if (results.isNotEmpty()) {
                    text.commitGlide(results, capitalize)
                    keyboard?.releaseOneShotShift()
                    if (autoShifted) autoShifted = false
                    afterEdit()
                }
            }
        }
    }

    override fun onSpaceLongPress() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    override fun onCursorMove(steps: Int) {
        text.finishComposing()
        val code = if (steps > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        repeat(kotlin.math.abs(steps)) { KeySender.sendPlain(ic, code) }
    }

    override fun onShiftChanged(state: ShiftState) {
        autoShifted = false
    }

    override fun isGlideAllowed(): Boolean = settings.glide && mode == Mode.TEXT && field.allowsGlide && glideDecoder != null

    override fun onKeyboardWidthChanged(widthPx: Int) {
        rebuildGeometry()
    }

    private fun switchMode(m: Mode) {
        if (mode == m) return
        text.finishComposing()
        mode = m
        keyboard?.setShift(ShiftState.OFF, notify = false)
        autoShifted = false
        rebuildGeometry()
        updateAutoCaps()
    }

    // ---- TerminalBarView.Listener --------------------------------------------------------------------

    override fun onBarPress() {
        feedback.keyPress()
    }

    override fun onBarKey(item: BarItem) {
        val code = KeyCodeNames.lookup(item.code ?: return) ?: return
        text.finishComposing()
        KeySender.send(ic, KeyEventMapper.planForKey(code, KeyEventMapper.metaFor(item.mods), modifiers.consume()))
        strip?.bar?.updateModifiers(modifiers)
        afterEdit()
    }

    override fun onBarKeyRepeat(item: BarItem) {
        val code = KeyCodeNames.lookup(item.code ?: return) ?: return
        text.finishComposing()
        KeySender.send(ic, KeyEventMapper.planForKey(code, KeyEventMapper.metaFor(item.mods), modifiers.metaState()))
    }

    override fun onBarModifier(item: BarItem, modifier: Modifier) {
        modifiers.tap(modifier, SystemClock.uptimeMillis())
        strip?.bar?.updateModifiers(modifiers)
    }

    override fun onBarSnippet(item: BarItem) {
        text.finishComposing()
        ic?.commitText(item.text ?: return, 1)
        afterEdit()
    }

    // ---- TextInputController.Ui ----------------------------------------------------------------------

    override fun showCandidates(words: List<String>) {
        strip?.suggestions?.show(words)
    }

    override fun setComposing(composing: Boolean) {
        strip?.setComposing(composing)
    }

    companion object {
        private const val TAG = "DevBoard"
    }
}
