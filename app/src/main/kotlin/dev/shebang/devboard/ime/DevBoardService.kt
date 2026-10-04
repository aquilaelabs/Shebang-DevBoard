package dev.shebang.devboard.ime

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.inline.InlinePresentationSpec
import androidx.annotation.RequiresApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.ImageViewStyle
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import dev.shebang.devboard.dict.Dictionary
import dev.shebang.devboard.dict.PersonalWords
import dev.shebang.devboard.glide.SpaceHabit
import dev.shebang.devboard.glide.GlideAdaptation
import dev.shebang.devboard.glide.TapModel
import dev.shebang.devboard.glide.PathMatch
import dev.shebang.devboard.glide.GlideLanguage
import dev.shebang.devboard.glide.GlideResult
import dev.shebang.devboard.glide.GlideSession
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
import dev.shebang.devboard.settings.AppProfiles
import dev.shebang.devboard.settings.Settings
import dev.shebang.devboard.settings.SettingsRepository
import dev.shebang.devboard.view.MicButton
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

class DevBoardService : InputMethodService(), KeyboardView.Listener, TerminalBarView.Listener, TextInputController.Ui,
    GlideSession.Listener, TextInputController.Learner, dev.shebang.devboard.view.EmojiPanelView.Listener,
    dev.shebang.devboard.view.ClipboardPanelView.Listener {

    private enum class Mode { TEXT, CODE }

    private lateinit var layouts: LayoutRepository
    private lateinit var languageLoader: LanguageLoader
    private lateinit var glideSession: GlideSession
    private lateinit var feedback: Feedback
    /** Whether letters turning into spaces helps this user, and a space made so whose fate the next key tells. */
    private lateinit var spaceHabit: SpaceHabit
    private var spaceCheckPending = false
    private lateinit var text: TextInputController
    private val main = Handler(Looper.getMainLooper())
    private val background = Executors.newSingleThreadExecutor { r -> Thread(r, "devboard-bg").apply { isDaemon = true } }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var settingsJob: Job? = null

    private var settings = Settings()
    private var theme: KeyboardTheme? = null
    /** Counts autofill responses, so chips still inflating for an older one are dropped. */
    private var autofillGeneration = 0
    private var barConfig: BarConfig? = null
    private lateinit var appProfiles: AppProfiles
    /** The app the current field belongs to (its package name), for its own mode and bar. */
    private var currentApp = ""
    private val modifiers = ModifierState()

    private var root: ImeRootView? = null
    private var strip: TopStripView? = null
    private var keyboard: KeyboardView? = null
    private var popup: KeyPopup? = null
    /** The emoji panel, shown in place of the keys while open. */
    private var emojiPanel: dev.shebang.devboard.view.EmojiPanelView? = null
    private var emojiGroups: List<Pair<String, List<String>>>? = null
    /** The clipboard history panel, likewise. */
    private var clipPanel: dev.shebang.devboard.view.ClipboardPanelView? = null
    private lateinit var clipHistory: ClipboardHistory

    private var mode = Mode.TEXT
    private var field: FieldInfo = FieldInfo.from(null)
    private var geometry: KeyboardGeometry? = null
    private var geometryVersion = 0
    private var glideModel: KeyLayoutModel? = null
    private var glideLanguage: GlideLanguage? = null
    /** The words the keyboard knows right now; replaced when the learned vocabulary is rebuilt. */
    private var bundle: LanguageBundle? = null
    /** A rebuilt bundle waiting for a moment when no glide is in flight and nothing is staged. */
    private var pendingBundle: LanguageBundle? = null
    private lateinit var personal: PersonalWords
    private lateinit var adaptation: GlideAdaptation
    /** Where this user's taps land on each key, for weighing typing slips. */
    private lateinit var tapAdaptation: GlideAdaptation
    /** The glide in progress (its session id), or -1. */
    private var glideId = -1
    private var glideCapitalize = false
    private var glideTrailingSpace = false
    private var autoShifted = false

    /** Voice typing through the Shebang Voice add-on, when it is installed. */
    private lateinit var voice: VoiceClient

    /** What the clipboard chip offers, if anything. */
    private lateinit var clipChip: ClipboardChip
    private var clipOffer: ClipboardChip.Offer? = null

    override fun onCreate() {
        super.onCreate()
        clipChip = ClipboardChip(this)
        clipHistory = ClipboardHistory(java.io.File(filesDir, ClipboardHistory.FILE))
        voice = VoiceClient(this, object : VoiceClient.Listener {
            override fun onVoiceState(state: Int, level: Int) = showVoiceState(state, level)
            override fun onVoiceText(text: String) {
                val t = this@DevBoardService.text
                if (settings.tidyDictation) {
                    val tidy = DictationCleanup.tidy(text)
                    if (tidy.dropPrevious) t.dropLastDictation()
                    t.insertDictation(tidy.text)
                } else {
                    t.insertDictation(text)
                }
                afterEdit()
            }
            override fun onVoiceError(code: Int) = voiceError(code)
        })
        clipChip.listen {
            // Every copy the keyboard sees goes in the history (never a sensitive one).
            clipChip.textToKeep()?.let { t -> background.execute { clipHistory.add(t) } }
            clipChip.imageToKeep()?.let { (uri, mime) -> background.execute { keepImage(uri, mime) } }
            if (isInputViewShown) updateClipChip()
            if (clipPanel?.visibility == View.VISIBLE) refreshClipPanel()
        }
        layouts = LayoutRepository(this)
        glideSession = GlideSession(this)
        personal = PersonalWords.get(filesDir)
        appProfiles = AppProfiles(this)
        adaptation = GlideAdaptation.get(filesDir)
        tapAdaptation = GlideAdaptation.getTaps(filesDir)
        background.execute { adaptation.load() }
        // Where this user's taps land, learned in earlier sessions: the tap model picks it up once read.
        background.execute {
            tapAdaptation.load()
            main.post { refreshTapModel() }
        }
        languageLoader = LanguageLoader(
            this,
            personal,
            onDictionary = { suggester -> main.post { if (bundle == null) text.suggester = suggester } },
            onLanguage = { b -> main.post { offerBundle(b) } },
        )
        feedback = Feedback(this)
        spaceHabit = SpaceHabit.get(filesDir)
        text = TextInputController({ currentInputConnection }, this, background, main, this)
        // Identifiers from the text are scored against a glide on the current key layout.
        text.identifierScorer = { stroke, letters -> geometry?.let { PathMatch.cost(glideModelFor(it), stroke, letters) } }
        settingsJob = scope.launch {
            SettingsRepository.get(this@DevBoardService).settings.collectLatest { applySettings(it) }
        }
    }

    override fun onDestroy() {
        settingsJob?.cancel()
        scope.cancel()
        glideSession.release()
        background.shutdownNow()
        super.onDestroy()
    }

    // ---- Settings ------------------------------------------------------------------------------------

    /** The terminal bar for the current app: its own when it has one, else the bar for all apps. */
    private fun applyBar() {
        val json = settings.appBars[currentApp] ?: settings.barJson
        barConfig = json?.let { runCatching { BarConfig.parse(it) }.getOrNull() } ?: layouts.defaultBar
        strip?.bar?.setConfig(barConfig!!)
    }

    private fun applySettings(s: Settings) {
        val heightChanged = s.heightScale != settings.heightScale || s.numberRow != settings.numberRow
        val themeChanged = s.palette != settings.palette || theme == null
        val barChanged = s.barJson != settings.barJson || s.appBars != settings.appBars || barConfig == null
        settings = s
        feedback.settings = s
        text.settings = s
        text.emails = if (s.rememberEmails) EmailMemory.get(filesDir) else null
        if (themeChanged) applyTheme()
        if (barChanged) applyBar()
        strip?.setMode(s.stripMode)
        keyboard?.keyPreviewEnabled = s.keyPreview
        keyboard?.holdDeletesWords = s.holdDeletesWords
        keyboard?.glideTrailEnabled = s.glideTrail
        keyboard?.phraseGlideEnabled = s.phraseGlide
        if (heightChanged) rebuildGeometry()
        if (languageLoader.learnWords != s.learnWords) {
            languageLoader.learnWords = s.learnWords
            languageLoader.rebuild()
        }
    }

    // ---- Language and learning -----------------------------------------------------------------------

    /** A new bundle is ready: use it now if that is safe, otherwise as soon as it is. */
    private fun offerBundle(b: LanguageBundle) {
        if (glideId >= 0) pendingBundle = b else applyBundle(b)
    }

    private fun applyBundle(b: LanguageBundle) {
        val first = bundle == null
        pendingBundle = null
        bundle = b
        glideSession.language = b.glide
        glideLanguage = b.glide
        text.suggester = b.suggester
        text.predictionModel = b.dictionary to b.lm
        text.nextWordModel = b.glide.nextWord?.let { dev.shebang.devboard.dict.NextWordModel.copyOf(it) }
        // Dictionary positions changed: remembered glides and the targeted word go.
        if (!first) text.onLanguageChanged()
    }

    private fun applyPendingBundle() {
        val b = pendingBundle ?: return
        if (glideId < 0) applyBundle(b)
    }

    /** Saves what was learned and rebuilds the vocabulary if it changed. Runs when the keyboard hides. */
    private fun persistLearning() {
        background.execute {
            personal.save()
            adaptation.save()
            tapAdaptation.save()
            spaceHabit.save()
        }
        val b = bundle ?: return
        if (personal.vocabularyVersion != b.vocabularyVersion || personal.countsVersion - b.countsVersion >= REBUILD_AFTER_WORDS) {
            languageLoader.rebuild()
        }
    }

    override fun learnWord(word: String, previous: String?, sentenceStart: Boolean) {
        if (!settings.learnWords) return
        val dictionary = bundle?.dictionary ?: return
        background.execute { personal.learn(word, previous, sentenceStart) { dictionary.indexOfLower(it) >= 0 } }
    }

    override fun learnTaps(observations: FloatArray) {
        if (!settings.adaptTaps) return
        background.execute { tapAdaptation.learn(observations) }
    }

    override fun learnGlide(observations: FloatArray) {
        if (!settings.adaptGlide) return
        background.execute { adaptation.learn(observations) }
    }

    override fun correction(stroke: FloatArray?, word: Int, dictionary: Dictionary) {
        background.execute { adaptation.recordCorrection() }
        // A retyped word the dictionary lacks has no letters path to re-align the stroke to.
        if (!settings.adaptGlide || stroke == null || word < 0) return
        val lang = glideLanguage ?: return
        if (lang.dictionary !== dictionary) return
        val g = geometry ?: return
        // The original stroke, re-aligned to the word the user meant, teaches twice as much as a kept glide
        // (when it plausibly was that word).
        glideSession.observeCorrection(lang, glideModelFor(g), adaptation.offsets(), stroke, word) { obs ->
            if (obs != null) background.execute { adaptation.learnCorrection(obs) }
        }
    }

    private fun applyTheme() {
        val t = KeyboardTheme.build(this, settings)
        theme = t
        keyboard?.theme = t
        emojiPanel?.setTheme(t)
        clipPanel?.setTheme(t)
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
        s.suggestions.onSuggestion = { word ->
            // Picking from the strip feels like a key press.
            feedback.keyPress()
            text.pickCandidate(word)
            afterEdit()
        }
        column.addView(s, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        column.addView(k, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val e = dev.shebang.devboard.view.EmojiPanelView(this)
        e.listener = this
        e.visibility = View.GONE
        column.addView(e, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0))
        emojiPanel = e
        emojiGroups?.let { e.setEmoji(it) }
        val cp = dev.shebang.devboard.view.ClipboardPanelView(this)
        cp.listener = this
        cp.imageFile = { clipHistory.imageFile(it) }
        cp.visibility = View.GONE
        column.addView(cp, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0))
        clipPanel = cp
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
        s.onChipsDismissed = { clipOffer?.let { clipChip.markHandled(it.stamp) }; clipOffer = null }
        s.mic.setOnClickListener {
            feedback.keyPress()
            if (voice.active) voice.stop() else voice.start()
        }
        s.setMode(settings.stripMode)
        k.keyPreviewEnabled = settings.keyPreview
        k.holdDeletesWords = settings.holdDeletesWords
        k.glideTrailEnabled = settings.glideTrail
        k.phraseGlideEnabled = settings.phraseGlide
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
        text.codeMode = mode == Mode.CODE
        val k = keyboard ?: return
        val layout = currentLayout()
        val dm = resources.displayMetrics
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val baseRow = KeyboardSizing.rowHeightPx(resources, settings.heightScale)
        val numberRow = settings.numberRow && layout.mode == "text"
        // Every mode is as tall as text mode (with its number row when that is on): switching to code mode or
        // a number pad never moves the strip or the app above. Code mode's five rows share that height.
        val textLayout = layouts.text
        val rows = textLayout.rows.size + (if (settings.numberRow && textLayout.numberRow != null) 1 else 0)
        val maxHeight = dm.heightPixels * (if (landscape) 0.6f else 0.5f)
        val height = (rows * baseRow).coerceAtMost(maxHeight).toInt()
        // The IME window can be narrower than the display (landscape cutout insets), so follow the view.
        val width = if (k.width > 0) k.width else dm.widthPixels
        val g = KeyboardGeometry(
            layout = layout,
            variant = field.variant,
            widthPx = width,
            heightPx = height,
            numberRow = numberRow,
            horizontalGapPx = KeyboardSizing.horizontalGapPx(resources),
            verticalGapPx = KeyboardSizing.verticalGapPx(resources),
            version = ++geometryVersion,
        )
        geometry = g
        glideModel = null
        k.setGeometry(g)
        k.requestLayout()
        refreshTapModel()
        root?.let { ViewCompat.requestApplyInsets(it) }
        strip?.setCodeMode(mode == Mode.CODE)
    }

    /** The tap model for the current keys and this user's learned tap offsets (read once per field). */
    private fun refreshTapModel() {
        val g = geometry ?: return
        val model = TapModel(glideModelFor(g), resources.displayMetrics.density, if (settings.adaptTaps) tapAdaptation.offsets() else null)
        text.tapModel = model
        keyboard?.tapModel = model
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
        if (!restarting) closePanels()
        field = FieldInfo.from(info)
        text.startInput(field)
        val app = info?.packageName.orEmpty()
        if (app != currentApp) {
            // Another app: its own mode (the one last used there) and its own bar.
            currentApp = app
            if (app != packageName) appProfiles.noteApp(app)
            mode = if (appProfiles.modeFor(app) == AppProfiles.CODE) Mode.CODE else Mode.TEXT
            applyBar()
        }
        // Words deleted in settings (same process) take effect the next time the keyboard opens.
        bundle?.let { if (it.vocabularyVersion != personal.vocabularyVersion) languageLoader.rebuild() }
        modifiers.clearAll()
        strip?.bar?.updateModifiers(modifiers)
        keyboard?.setShift(ShiftState.OFF, notify = false)
        autoShifted = false
        rebuildGeometry()
        if (field.allowsComposing) languageLoader.ensureLoading()
        applyPendingBundle()
        updateAutoCaps()
        text.refreshLetterPrior()
        text.refreshEmails(edited = false)
        updateClipChip()
        updateMic()
    }

    /** The mic shows where words are typed (text mode, not passwords or terminals) once the add-on is installed. */
    private fun updateMic() {
        strip?.setMicShown(mode == Mode.TEXT && field.allowsComposing && voice.installed())
    }

    /** The mic and the strip follow the add-on: listening (with the voice level), writing, or idle. */
    private fun showVoiceState(state: Int, level: Int) {
        val s = strip ?: return
        s.mic.state = when (state) {
            VoiceClient.STATE_LISTENING, VoiceClient.STATE_HEARING -> MicButton.State.LISTENING
            VoiceClient.STATE_TRANSCRIBING -> MicButton.State.WRITING
            else -> MicButton.State.IDLE
        }
        s.mic.level = if (state == VoiceClient.STATE_HEARING) level else 0
        if (state == VoiceClient.STATE_IDLE) {
            s.suggestions.clear()
            s.setComposing(text.isComposing)
            return
        }
        s.setComposing(true)
        s.suggestions.showPreview(
            when (state) {
                VoiceClient.STATE_TRANSCRIBING -> "Writing\u2026"
                else -> "Listening \u2014 tap the mic to stop"
            }
        )
    }

    private fun voiceError(code: Int) {
        showVoiceState(VoiceClient.STATE_IDLE, 0)
        when (code) {
            VoiceClient.ERROR_NO_PERMISSION -> voice.askForMicrophone()
            else -> android.widget.Toast.makeText(
                this,
                when (code) {
                    VoiceClient.ERROR_MIC -> "The microphone is in use or unavailable"
                    VoiceClient.ERROR_MODEL -> "Shebang Voice could not load its speech model"
                    VoiceClient.ERROR_NOT_ALLOWED -> "Shebang Voice only works with the DevBoard it was released with"
                    else -> "Shebang Voice is not available"
                },
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    /** Offers a chip for pasting text copied in the last few minutes (not in terminals). */
    private fun updateClipChip() {
        val s = strip ?: return
        val offer = if (field.isTerminal) null else clipChip.offer(masked = field.isPassword)
        clipOffer = offer
        if (offer == null) {
            s.setClip(null)
            return
        }
        val t = theme ?: return
        val height = s.rowHeight - (8 * resources.displayMetrics.density).toInt()
        s.setClip(clipChip.chipView(offer, t, height) {
            feedback.keyPress()
            text.finishComposing()
            ic?.commitText(offer.text, 1)
            clipChip.markHandled(offer.stamp)
            clipOffer = null
            s.setClip(null)
            afterEdit()
        })
    }

    override fun onFinishInput() {
        // Leaving a field: an email field's addresses are remembered.
        text.rememberEmails()
        super.onFinishInput()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        closePanels()
        // The chips were for that field; the next one gets its own response.
        autofillGeneration++
        strip?.resetAutofill()
        strip?.setClip(null)
        if (::voice.isInitialized) voice.cancel()
        clipOffer = null
        text.finishComposing()
        persistLearning()
        keyboard?.cancelAllTouches()
        modifiers.clearAll()
        strip?.bar?.updateModifiers(modifiers)
        super.onFinishInputView(finishingInput)
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        text.onSelectionChanged(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        text.refreshLetterPrior()
        // Mid-word the caps mode cannot change; asking the editor (an IPC) is only worth it at a boundary.
        if (!text.isComposing) updateAutoCaps()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    // ---- Autofill (Android 11+) ----------------------------------------------------------------------

    /**
     * Asks the autofill service (the user's password manager) for suggestions to show in the strip, drawn in
     * the keyboard's colours at the strip's height. The system decides when there are any.
     */
    // Lint reads the style builders' public setters as their restricted generic base class's.
    @SuppressLint("RestrictedApi")
    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? {
        val t = theme ?: KeyboardTheme.build(this, settings)
        val d = resources.displayMetrics.density
        val pad = (10 * d).toInt()
        val chip = ViewStyle.Builder()
            .setBackground(Icon.createWithResource(this, dev.shebang.devboard.R.drawable.autofill_chip).setTint(t.keyFunctional))
            .setPadding(pad, 0, pad, 0)
            .build()
        val style = InlineSuggestionUi.newStyleBuilder()
            .setSingleIconChipStyle(chip)
            .setChipStyle(chip)
            .setTitleStyle(TextViewStyle.Builder().setTextColor(t.stripText).setTextSize(15f).build())
            .setSubtitleStyle(TextViewStyle.Builder().setTextColor(t.keyTextSecondary).setTextSize(13f).build())
            .setStartIconStyle(ImageViewStyle.Builder().setPadding(0, 0, (6 * d).toInt(), 0).build())
            .build()
        val styles = UiVersions.newStylesBuilder().addStyle(style).build()
        val h = strip?.rowHeight ?: (44 * d).toInt()
        val chipHeight = h - (8 * d).toInt()
        val spec = InlinePresentationSpec.Builder(Size((48 * d).toInt(), chipHeight), Size(resources.displayMetrics.widthPixels, chipHeight))
            .setStyle(styles)
            .build()
        return InlineSuggestionsRequest.Builder(listOf(spec)).setMaxSuggestionCount(MAX_AUTOFILL).build()
    }

    /** Puts the service's chips in the strip; an empty response clears them. */
    @RequiresApi(Build.VERSION_CODES.R)
    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        val suggestions = response.inlineSuggestions
        val s = strip ?: return false
        val generation = ++autofillGeneration
        if (suggestions.isEmpty()) {
            s.setAutofill(emptyList())
            return true
        }
        val chipHeight = s.rowHeight - (8 * resources.displayMetrics.density).toInt()
        // Pinned chips (the service's own entry points) go last, as the service expects them to stay put.
        val ordered = suggestions.sortedBy { it.info.isPinned }
        val views = arrayOfNulls<View>(ordered.size)
        var remaining = ordered.size
        ordered.forEachIndexed { i, suggestion ->
            suggestion.inflate(this, Size(LinearLayout.LayoutParams.WRAP_CONTENT, chipHeight), mainExecutor) { v ->
                // The chip is the service's surface; above the keyboard's window, or the strip's background hides it.
                v?.setZOrderedOnTop(true)
                views[i] = v
                if (--remaining == 0 && generation == autofillGeneration) s.setAutofill(views.filterNotNull())
            }
        }
        return true
    }

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
        text.refreshLetterPrior()
        text.refreshEmails()
    }

    // ---- KeyboardView.Listener -----------------------------------------------------------------------

    override fun onKeyDown(key: Key) {
        feedback.keyPress(key.action)
    }

    override fun onKeyTap(key: Key, shift: ShiftState) {
        // Typing while listening ends the dictation; what was said is still written.
        if (voice.active) voice.stop()
        // A space made from a letter tap is taken back if this key is backspace, else kept.
        settleSpaceCheck(undone = key.action == KeyAction.BACKSPACE)
        if (key.action == KeyAction.SPACE && keyboard?.lastSpaceFromLetter == true) spaceCheckPending = true
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
                    // A letter keeps where its tap came down, for weighing slips.
                    val kb = keyboard
                    if (key.def.isLetter && t.length == 1 && kb != null) typeFromKeyboard(t, kb.lastTapX, kb.lastTapY) else typeFromKeyboard(t)
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

    private fun typeFromKeyboard(t: String, tapX: Float = Float.NaN, tapY: Float = Float.NaN) {
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
            text.typeText(t, tapX, tapY)
        }
    }

    override fun onKeyRepeat(key: Key) {
        settleSpaceCheck(undone = key.action == KeyAction.BACKSPACE)
        when (key.action) {
            KeyAction.BACKSPACE -> text.backspace()
            else -> if (key.keyCode != 0) {
                text.finishComposing()
                KeySender.sendPlain(ic, key.keyCode)
            }
        }
        feedback.keyRepeat(key.action)
        afterEdit()
    }

    override fun onBackspaceWordRepeat() {
        if (sendWithStickyModifiers(KeyEvent.KEYCODE_DEL)) return
        text.backspaceWord()
        feedback.keyRepeat(KeyAction.BACKSPACE)
        afterEdit()
    }

    override fun onAlternate(key: Key, text: String) {
        typeFromKeyboard(text)
        keyboard?.releaseOneShotShift()
        afterEdit()
    }

    private fun settleSpaceCheck(undone: Boolean) {
        if (!spaceCheckPending) return
        spaceCheckPending = false
        if (!spaceHabit.record(undone)) keyboard?.spaceFromLetters = false
    }

    override fun onGlideStart(points: FloatArray, times: LongArray, count: Int) {
        settleSpaceCheck(undone = false)
        if (voice.active) voice.stop()
        val g = geometry ?: return
        val lang = glideLanguage ?: return
        if (!isGlideAllowed() || count == 0) return
        val context = text.glideContext(lang.dictionary, lang.lm)
        glideCapitalize = keyboard?.shiftState != ShiftState.OFF
        glideTrailingSpace = false
        val offsets = if (settings.adaptGlide) adaptation.offsets() else null
        glideId = glideSession.start(glideModelFor(g), context, times[0], settings.phraseGlide, offsets)
        for (i in 0 until count) glideSession.point(points[2 * i], points[2 * i + 1], times[i])
        background.execute { adaptation.recordGlide() }
        strip?.setComposing(true)
        strip?.suggestions?.showPreview("")
    }

    override fun onGlidePoint(x: Float, y: Float, t: Long) {
        if (glideId >= 0) glideSession.point(x, y, t)
    }

    override fun onGlideBoundary() {
        if (glideId >= 0) glideSession.boundary()
    }

    override fun onGlideEnd(x: Float, y: Float, t: Long, trailingSpace: Boolean) {
        if (glideId < 0) return
        glideTrailingSpace = trailingSpace
        // Lifting inside the space bar after a dip: that point belongs to no word.
        if (trailingSpace) glideSession.end(Float.NaN, Float.NaN, t) else glideSession.end(x, y, t)
    }

    override fun onGlideCancel() {
        if (glideId < 0) return
        glideSession.cancel()
        glideId = -1
        showCandidates(emptyList())
        setComposing(text.isComposing)
        applyPendingBundle()
    }

    override fun onGlidePreview(id: Int, result: GlideResult) {
        if (id != glideId || keyboard?.isGliding != true) return
        val lang = glideLanguage ?: return
        strip?.suggestions?.showPreview(text.previewText(result, lang.dictionary, glideCapitalize))
    }

    override fun onGlideResult(id: Int, result: GlideResult?, decodeMs: Float, language: GlideLanguage) {
        if (id != glideId) return
        glideId = -1
        Log.d(TAG, "glide decoded in %.1f ms after lift".format(decodeMs))
        if (result == null) {
            showCandidates(emptyList())
            setComposing(text.isComposing)
            applyPendingBundle()
            return
        }
        // The words index the dictionary they were decoded with.
        text.commitGlide(result, language.dictionary, glideCapitalize, glideTrailingSpace)
        if (language !== glideLanguage) text.onLanguageChanged()
        keyboard?.releaseOneShotShift()
        if (autoShifted) autoShifted = false
        afterEdit()
        applyPendingBundle()
    }

    override fun onSpaceLongPress() {
        // With the cursor in a word, holding space moves past it; elsewhere a hold is just a space.
        if (!text.spaceHeld()) text.space()
        afterEdit()
    }

    override fun onShiftLongPress() {
        // A second buzz says the hold took: caps lock is on.
        feedback.keyPress(KeyAction.SHIFT)
    }

    override fun onModeLongPress() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }

    override fun onCursorMove(steps: Int, select: Boolean) {
        text.finishComposing()
        val code = if (steps > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        // With shift on, shift+arrow grows the selection, as on a hardware keyboard.
        val meta = if (select) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
        repeat(kotlin.math.abs(steps)) { KeySender.send(ic, KeyEventPlan(code, meta)) }
    }

    override fun onDeleteWordsPreview(words: Int) {
        feedback.keyPress()
        text.previewDeleteWords(words)
    }

    override fun onDeleteWords(words: Int) {
        text.deleteWords(words)
        afterEdit()
    }

    override fun onShiftChanged(state: ShiftState) {
        autoShifted = false
    }

    override fun isGlideAllowed(): Boolean = settings.glide && mode == Mode.TEXT && field.allowsGlide && glideLanguage != null

    override fun onKeyboardWidthChanged(widthPx: Int) {
        rebuildGeometry()
    }

    private fun switchMode(m: Mode) {
        if (mode == m) return
        text.finishComposing()
        mode = m
        appProfiles.setMode(currentApp, if (m == Mode.CODE) AppProfiles.CODE else AppProfiles.TEXT)
        keyboard?.setShift(ShiftState.OFF, notify = false)
        autoShifted = false
        rebuildGeometry()
        updateAutoCaps()
        if (m == Mode.CODE) voice.cancel()
        updateMic()
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

    override fun onBarPanel(item: BarItem) {
        when (item.type) {
            BarItem.TYPE_EMOJI -> if (emojiPanel?.visibility == View.VISIBLE) closePanels() else openEmoji()
            BarItem.TYPE_CLIPBOARD -> if (clipPanel?.visibility == View.VISIBLE) closePanels() else openClipboard()
        }
    }

    /** The emoji panel in place of the keys, the keyboard's height; the list is read once, off the main thread. */
    private fun openEmoji() {
        val k = keyboard ?: return
        val e = emojiPanel ?: return
        text.finishComposing()
        val groups = emojiGroups
        if (groups == null) {
            background.execute {
                val loaded = e.loadEmoji()
                main.post {
                    emojiGroups = loaded
                    e.setEmoji(loaded)
                }
            }
        }
        val height = keysHeight()
        clipPanel?.visibility = View.GONE
        e.layoutParams = e.layoutParams.apply { this.height = height }
        e.open()
        k.visibility = View.GONE
        e.visibility = View.VISIBLE
    }

    /** The clipboard history in place of the keys; what is on the clipboard now is recorded first. */
    private fun openClipboard() {
        val k = keyboard ?: return
        val cp = clipPanel ?: return
        text.finishComposing()
        val height = keysHeight()
        emojiPanel?.visibility = View.GONE
        cp.layoutParams = cp.layoutParams.apply { this.height = height }
        val image = clipChip.imageToKeep()
        if (image != null) background.execute { keepImage(image.first, image.second) }
        refreshClipPanel(clipChip.textToKeep())
        k.visibility = View.GONE
        cp.visibility = View.VISIBLE
    }

    /** The panel's height: the keys', remembered while they show (a panel may already hide them). */
    private var panelHeight = 0

    private fun keysHeight(): Int {
        val k = keyboard ?: return panelHeight
        if (k.visibility == View.VISIBLE && k.height > 0) panelHeight = k.height
        return if (panelHeight > 0) panelHeight else k.measuredHeight
    }

    /** Copies a picture from the clipboard into the history (bigger than the cap: not kept). */
    private fun keepImage(uri: android.net.Uri, mime: String) {
        runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                    if (buf.size() > ClipboardHistory.MAX_IMAGE_BYTES) return
                }
                clipHistory.addImage(buf.toByteArray(), mime)
            }
        }.onFailure { Log.w(TAG, "could not keep a copied picture", it) }
    }

    /** Re-reads the history (recording [current], the clip now, first) and shows it. */
    private fun refreshClipPanel(current: String? = null) {
        val cp = clipPanel ?: return
        background.execute {
            current?.let { clipHistory.add(it) }
            val items = clipHistory.list()
            main.post { cp.show(items) }
        }
    }

    /** Back to the keys. */
    private fun closePanels() {
        emojiPanel?.visibility = View.GONE
        clipPanel?.visibility = View.GONE
        keyboard?.visibility = View.VISIBLE
    }

    override fun onClipPaste(item: ClipboardHistory.Item) {
        feedback.keyPress()
        text.finishComposing()
        if (!item.isImage) {
            ic?.commitText(item.text, 1)
            afterEdit()
            return
        }
        // A picture goes in through the editor's content insertion, where the field takes that kind of image.
        val file = clipHistory.imageFile(item) ?: return
        val mime = item.mime ?: "image/png"
        val info = currentInputEditorInfo
        val accepted = info != null && androidx.core.view.inputmethod.EditorInfoCompat.getContentMimeTypes(info)
            .any { android.content.ClipDescription.compareMimeTypes(mime, it) }
        val conn = ic
        if (!accepted || conn == null) {
            android.widget.Toast.makeText(this, "This field doesn't take pictures", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(this, CLIP_AUTHORITY, file)
        val content = androidx.core.view.inputmethod.InputContentInfoCompat(uri, android.content.ClipDescription("Picture", arrayOf(mime)), null)
        androidx.core.view.inputmethod.InputConnectionCompat.commitContent(
            conn, info, content, androidx.core.view.inputmethod.InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null,
        )
        afterEdit()
    }

    override fun onClipPinned(key: String, pinned: Boolean) {
        feedback.keyPress()
        background.execute { clipHistory.setPinned(key, pinned) }
        refreshClipPanel()
    }

    override fun onClipRemove(key: String) {
        feedback.keyPress()
        background.execute { clipHistory.remove(key) }
        refreshClipPanel()
    }

    override fun onClipClear() {
        feedback.keyPress()
        background.execute { clipHistory.clear() }
        refreshClipPanel()
    }

    override fun onEmoji(emoji: String) {
        feedback.keyPress()
        text.typeText(emoji)
        afterEdit()
    }

    override fun onPanelSpace() {
        feedback.keyPress()
        text.space()
        afterEdit()
    }

    override fun onPanelBackspace(first: Boolean) {
        // One buzz per hold, as on the keyboard: the repeats only click.
        if (first) feedback.keyPress(KeyAction.BACKSPACE) else feedback.keyRepeat(KeyAction.BACKSPACE)
        text.backspace()
        afterEdit()
    }

    override fun onPanelClose() {
        feedback.keyPress()
        closePanels()
    }

    // ---- TextInputController.Ui ----------------------------------------------------------------------

    override fun showCandidates(words: List<String>) {
        strip?.suggestions?.show(words)
    }

    override fun setLetterPrior(prior: FloatArray?) {
        keyboard?.letterPrior = prior
    }

    override fun setSpaceFromLetters(on: Boolean) {
        keyboard?.spaceFromLetters = on && spaceHabit.enabled()
    }

    override fun showCorrection(typed: String, fix: String, other: String?) {
        strip?.suggestions?.showCorrection(typed, fix, other)
    }

    override fun setComposing(composing: Boolean) {
        strip?.setComposing(composing)
    }

    companion object {
        /** Learned uses since the last build after which hiding the keyboard rebuilds the frequencies. */
        private const val REBUILD_AFTER_WORDS = 50
        private const val TAG = "DevBoard"
        /** The file provider that hands clipboard pictures to the app they are inserted into (manifest). */
        private const val CLIP_AUTHORITY = "dev.shebang.devboard.clips"
        /** Autofill chips asked of the service at most. */
        private const val MAX_AUTOFILL = 6
    }
}
