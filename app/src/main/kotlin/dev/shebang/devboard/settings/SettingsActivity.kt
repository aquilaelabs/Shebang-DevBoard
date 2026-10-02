package dev.shebang.devboard.settings

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.shebang.devboard.ime.VoiceClient
import dev.shebang.devboard.layout.BarConfig
import dev.shebang.devboard.layout.BarItem
import dev.shebang.devboard.layout.KeyCodeNames
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = SettingsRepository.get(this)
        val defaultBar = BarConfig.parse(assets.open("bar/default.json").bufferedReader().use { it.readText() })
        setContent {
            val settings by repo.settings.collectAsStateWithLifecycle(initialValue = Settings())
            val scope = rememberCoroutineScope()
            var editingBar by remember { mutableStateOf(false) }
            var barApp by remember { mutableStateOf<String?>(null) }
            val profiles = remember { AppProfiles(this@SettingsActivity) }
            var recording by remember { mutableStateOf(false) }
            var personalWords by remember { mutableStateOf(false) }
            // A document from the About section (asset path and title), while it is open.
            var aboutDoc by remember { mutableStateOf<Pair<String, String>?>(null) }
            DevBoardTheme(settings.palette) {
                val doc = aboutDoc
                if (doc != null) {
                    BackHandler { aboutDoc = null }
                    AboutDocScreen(title = doc.second, asset = doc.first, onBack = { aboutDoc = null })
                } else if (personalWords) {
                    BackHandler { personalWords = false }
                    PersonalWordsScreen(onBack = { personalWords = false })
                } else if (recording) {
                    BackHandler { recording = false }
                    GlideRecorderScreen(settings = settings, onBack = { recording = false })
                } else if (editingBar) {
                    BackHandler { editingBar = false }
                    val allAppsBar = settings.barJson?.let { runCatching { BarConfig.parse(it) }.getOrNull() } ?: defaultBar
                    val app = barApp
                    val bar = app?.let { a -> settings.appBars[a]?.let { runCatching { BarConfig.parse(it) }.getOrNull() } } ?: allAppsBar
                    BarEditorScreen(
                        bar = bar,
                        apps = profiles.seenApps(),
                        app = app,
                        appHasOwnBar = app != null && app in settings.appBars,
                        onPickApp = { barApp = it },
                        onChange = { newBar ->
                            scope.launch {
                                repo.update { s -> if (app == null) s.copy(barJson = newBar.toJson()) else s.copy(appBars = s.appBars + (app to newBar.toJson())) }
                            }
                        },
                        onReset = {
                            scope.launch { repo.update { s -> if (app == null) s.copy(barJson = null) else s.copy(appBars = s.appBars - app) } }
                        },
                        onBack = { editingBar = false },
                    )
                } else {
                    SettingsScreen(
                        settings = settings,
                        update = { f -> scope.launch { repo.update(f) } },
                        onEditBar = { editingBar = true },
                        onRecordGlides = { recording = true },
                        onPersonalWords = { personalWords = true },
                        onDoc = { asset, title -> aboutDoc = asset to title },
                        onBack = { finish() },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: Settings,
    update: ((Settings) -> Settings) -> Unit,
    onEditBar: () -> Unit,
    onRecordGlides: () -> Unit,
    onPersonalWords: () -> Unit,
    onDoc: (asset: String, title: String) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    fun open(url: String) = runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse(url))) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("DevBoard settings") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
        )
    }) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).imePadding()) {
            item { SectionHeader("Appearance") }
            item { ThemePicker(settings.palette) { v -> update { it.copy(palette = v) } } }
            item {
                // Rounded, not cut off: 1.1 is stored as 1.0999999 and read as 109%. Stored snapped to the
                // slider's 10% steps.
                SliderRow("Keyboard height", "${kotlin.math.round(settings.heightScale * 100).toInt()}%", settings.heightScale, 0.7f..1.4f, steps = 6) { v ->
                    update { it.copy(heightScale = kotlin.math.round(v * 10) / 10f) }
                }
            }
            item { SwitchRow("Number row", "Digits above the letters in text mode", settings.numberRow) { v -> update { it.copy(numberRow = v) } } }
            item { SwitchRow("Key preview", "Pop up the character while a key is pressed", settings.keyPreview) { v -> update { it.copy(keyPreview = v) } } }

            item { SectionHeader("Feedback") }
            item { SwitchRow("Haptics", "Vibrate on key press", settings.haptics) { v -> update { it.copy(haptics = v) } } }
            item {
                ChoiceRow("Haptic strength", listOf(1 to "Light", 2 to "Medium", 3 to "Strong"), settings.hapticStrength, enabled = settings.haptics) { v -> update { it.copy(hapticStrength = v) } }
            }
            item { SwitchRow("Key sounds", "System key-click sounds", settings.keySounds) { v -> update { it.copy(keySounds = v) } } }

            item { SectionHeader("Typing") }
            item { SwitchRow("Glide typing", "Slide across letters to write a word", settings.glide) { v -> update { it.copy(glide = v) } } }
            item { SwitchRow("Glide trail", "Draw the path while gliding", settings.glideTrail, enabled = settings.glide) { v -> update { it.copy(glideTrail = v) } } }
            item {
                SwitchRow("Phrase gliding", "Dip into the space bar mid-glide to start the next word", settings.phraseGlide, enabled = settings.glide) { v ->
                    update { it.copy(phraseGlide = v) }
                }
            }
            item {
                ListItem(
                    headlineContent = { Text("Record glides") },
                    supportingContent = { Text("Glide prompted words to measure accuracy on your own fingers. Kept on this phone.") },
                    modifier = Modifier.clickable(onClick = onRecordGlides),
                )
            }
            item {
                // Voice typing is a separate app (it holds the microphone permission; the keyboard does not).
                val context = LocalContext.current
                val installed = remember {
                    context.packageManager.queryIntentServices(
                        android.content.Intent("dev.shebang.devboard.voice.LISTEN").setPackage(VoiceClient.PACKAGE), 0,
                    ).isNotEmpty()
                }
                ListItem(
                    headlineContent = { Text("Voice typing") },
                    supportingContent = {
                        Text(
                            if (installed) "Shebang Voice is installed: tap the mic at the end of the strip. Speech stays on this phone."
                            else "Get the Shebang Voice add-on from GitHub (about 60 MB). It turns speech into text on this phone; the keyboard itself never uses the microphone."
                        )
                    },
                    modifier = if (installed) Modifier else Modifier.clickable {
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(VoiceClient.RELEASES_URL)))
                    },
                )
            }
            item {
                SwitchRow("Tidy dictation", "Drop um and uh, stutters and repeats, and act on spoken corrections like \"no wait\" and \"scratch that\"", settings.tidyDictation) { v ->
                    update { it.copy(tidyDictation = v) }
                }
            }
            item { SectionHeader("Learning") }
            item { SwitchRow("Learn words I type", "Remember new words and the ones you use most, on this phone only", settings.learnWords) { v -> update { it.copy(learnWords = v) } } }
            item { SwitchRow("Adapt autocorrect to my taps", "Learn where your taps land on each key, from the words you type right", settings.adaptTaps) { v -> update { it.copy(adaptTaps = v) } } }
            item { SwitchRow("Adapt glide to my swiping", "Learn how your glides lean off each key, most from the words you correct", settings.adaptGlide, enabled = settings.glide) { v -> update { it.copy(adaptGlide = v) } } }
            item {
                ListItem(
                    headlineContent = { Text("Personal words") },
                    supportingContent = { Text("Review or delete what was learned, or reset glide adaptation") },
                    modifier = Modifier.clickable(onClick = onPersonalWords),
                )
            }

            item { SectionHeader("Corrections") }
            item { SwitchRow("Fix the last glided word", "When the next glide makes it unlikely, the word glided just before is corrected; tap it to change it back", settings.fixPreviousGlide) { v -> update { it.copy(fixPreviousGlide = v) } } }
            item { SwitchRow("Next-word suggestions", "After a space, the strip offers the words likely to come next", settings.nextWord) { v -> update { it.copy(nextWord = v) } } }
            item { SwitchRow("Autocorrect", "Fix the word when you press space", settings.autocorrect) { v -> update { it.copy(autocorrect = v) } } }
            item { SwitchRow("Auto-capitalize", "Shift at the start of sentences", settings.autoCaps) { v -> update { it.copy(autoCaps = v) } } }
            item { SwitchRow("Double-space period", "Two spaces insert \". \"", settings.doubleSpacePeriod) { v -> update { it.copy(doubleSpacePeriod = v) } } }
            item { SwitchRow("Pair brackets and quotes", "In code mode, ( [ { and quotes come in pairs, and typing the closing one steps over it", settings.pairBrackets) { v -> update { it.copy(pairBrackets = v) } } }

            item { SectionHeader("Terminal bar") }
            item {
                ChoiceRow(
                    "Strip behavior",
                    listOf(StripMode.AUTO to "Auto", StripMode.ALWAYS_BAR to "Always bar", StripMode.TWO_ROWS to "Two rows"),
                    settings.stripMode,
                ) { v -> update { it.copy(stripMode = v) } }
            }
            item {
                ListItem(
                    headlineContent = { Text("Edit terminal bar") },
                    supportingContent = { Text(if (settings.barJson == null) "Default bar" else "Customized") },
                    modifier = Modifier.clickable(onClick = onEditBar),
                )
            }
            item { SectionHeader("About") }
            item {
                val version = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "" }
                ListItem(
                    headlineContent = { Text("Shebang DevBoard $version") },
                    supportingContent = { Text("A keyboard for developers. Everything it learns stays on this phone; it has no network access. MIT licence.") },
                    modifier = Modifier.clickable { onDoc("about/LICENSE", "Licence") },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Check for updates") },
                    supportingContent = { Text("Opens the releases on GitHub. Install a newer APK over this one to update; your settings and words stay.") },
                    modifier = Modifier.clickable { open(VoiceClient.RELEASES_URL) },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Source code") },
                    supportingContent = { Text(REPO_URL.removePrefix("https://")) },
                    modifier = Modifier.clickable { open(REPO_URL) },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Credits") },
                    supportingContent = { Text("The word lists, sentences, swipe and tap data, models and libraries this keyboard is built on, with their licences") },
                    modifier = Modifier.clickable { onDoc("about/THIRD_PARTY_NOTICES.md", "Credits") },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange, enabled = enabled) },
        modifier = Modifier.clickable(enabled = enabled) { onChange(!checked) },
    )
}

@Composable
private fun <T> ChoiceRow(title: String, options: List<Pair<T, String>>, value: T, enabled: Boolean = true, onChange: (T) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            for ((v, label) in options) {
                FilterChip(selected = v == value, onClick = { onChange(v) }, label = { Text(label) }, enabled = enabled)
            }
        }
    }
}

@Composable
private fun SliderRow(title: String, valueLabel: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Float) -> Unit) {
    var local by remember(value) { mutableStateOf(value) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(valueLabel, style = MaterialTheme.typography.bodyMedium)
        }
        Slider(value = local, onValueChange = { local = it }, onValueChangeFinished = { onChange(local) }, valueRange = range, steps = steps)
    }
}

// ---- Bar editor --------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BarEditorScreen(
    bar: BarConfig,
    apps: List<String>,
    app: String?,
    appHasOwnBar: Boolean,
    onPickApp: (String?) -> Unit,
    onChange: (BarConfig) -> Unit,
    onReset: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var addDialog by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var appMenuOpen by remember { mutableStateOf(false) }
    fun appName(pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(bar.toJson().toByteArray()) }
        }.onFailure { Toast.makeText(context, "Export failed: ${it.message}", Toast.LENGTH_LONG).show() }
            .onSuccess { Toast.makeText(context, "Bar exported", Toast.LENGTH_SHORT).show() }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: error("empty file")
            BarConfig.parse(text)
        }.onFailure { Toast.makeText(context, "Import failed: ${it.message}", Toast.LENGTH_LONG).show() }
            .onSuccess { onChange(it); Toast.makeText(context, "Bar imported", Toast.LENGTH_SHORT).show() }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Terminal bar") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            actions = {
                IconButton(onClick = { addDialog = true }) { Icon(Icons.Default.Add, contentDescription = "Add item") }
                TextButton(onClick = { menuOpen = true }) { Text("More") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Export JSON") }, onClick = { menuOpen = false; exportLauncher.launch("devboard-bar.json") })
                    DropdownMenuItem(text = { Text("Import JSON") }, onClick = { menuOpen = false; importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) })
                    DropdownMenuItem(
                        text = { Text(if (app == null) "Reset to default" else "Use the bar for all apps") },
                        enabled = app == null || appHasOwnBar,
                        onClick = { menuOpen = false; onReset() },
                    )
                }
            },
        )
    }) { padding ->
        // The items as they stand while one is dragged, each with an id that follows it through the moves and
        // survives saving (a fresh id only for an item that is new), so the list never mistakes one row for
        // another. The new order is saved when the finger lifts.
        val ids = remember { IdSource() }
        var order by remember { mutableStateOf(ids.assign(bar.items, emptyList())) }
        LaunchedEffect(bar) { if (bar.items != order.map { it.second }) order = ids.assign(bar.items, order) }
        // The row being dragged, and where its top should be: under the finger, in the list's own pixels.
        var dragging by remember { mutableStateOf<Long?>(null) }
        var dragTop by remember { mutableFloatStateOf(0f) }
        val listState = rememberLazyListState()
        val edge = with(LocalDensity.current) { 72.dp.toPx() }
        fun commit(items: List<Pair<Long, BarItem>>) = onChange(BarConfig(items.map { it.second }))
        fun rowOf(id: Long) = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }
        // The dragged row swaps with a neighbour once its middle passes the neighbour's; the list keeps its
        // scroll position rather than following the first visible row when that one moves.
        fun swapIfPassed() {
            val id = dragging ?: return
            val row = rowOf(id) ?: return
            val middle = dragTop + row.size / 2f
            val at = order.indexOfFirst { it.first == id }
            val next = order.getOrNull(at + 1)?.let { rowOf(it.first) }
            val prev = order.getOrNull(at - 1)?.let { rowOf(it.first) }
            val to = when {
                next != null && middle > next.offset + next.size / 2f -> at + 1
                prev != null && middle < prev.offset + prev.size / 2f -> at - 1
                else -> return
            }
            val first = listState.firstVisibleItemIndex
            val firstOffset = listState.firstVisibleItemScrollOffset
            order = order.move(at, to)
            listState.requestScrollToItem(first, firstOffset)
        }
        // Held near the top or bottom edge, the list scrolls, faster the closer to the edge.
        LaunchedEffect(dragging) {
            val id = dragging ?: return@LaunchedEffect
            while (dragging == id) {
                withFrameNanos { }
                val row = rowOf(id) ?: continue
                val info = listState.layoutInfo
                val top = info.viewportStartOffset + edge
                val bottom = info.viewportEndOffset - edge
                val step = when {
                    dragTop < top -> -(top - dragTop)
                    dragTop + row.size > bottom -> dragTop + row.size - bottom
                    else -> 0f
                }.coerceIn(-edge, edge) * 0.25f
                if (step != 0f && listState.scrollBy(step) != 0f) swapIfPassed()
            }
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding).imePadding(), state = listState) {
            item {
                ListItem(
                    headlineContent = { Text(if (app == null) "Bar for all apps" else "Bar for ${appName(app)}") },
                    supportingContent = {
                        Text(
                            when {
                                app == null -> "Tap to give an app you have typed in a bar of its own"
                                appHasOwnBar -> "This app's own bar"
                                else -> "Uses the bar for all apps until you change it here"
                            }
                        )
                    },
                    modifier = Modifier.clickable { appMenuOpen = true },
                )
                DropdownMenu(expanded = appMenuOpen, onDismissRequest = { appMenuOpen = false }) {
                    DropdownMenuItem(text = { Text("All apps") }, onClick = { appMenuOpen = false; onPickApp(null) })
                    for (pkg in apps) {
                        DropdownMenuItem(text = { Text(appName(pkg)) }, onClick = { appMenuOpen = false; onPickApp(pkg) })
                    }
                }
                HorizontalDivider()
            }
            itemsIndexed(order, key = { _, entry -> entry.first }) { index, (id, item) ->
                val lifted = dragging == id
                ListItem(
                    leadingContent = {
                        // Drag the handle to move the item.
                        Icon(
                            Icons.Default.Menu,
                            contentDescription = "Drag to reorder",
                            modifier = Modifier.pointerInput(id) {
                                detectDragGestures(
                                    onDragStart = { rowOf(id)?.let { dragTop = it.offset.toFloat(); dragging = id } },
                                    onDragEnd = { dragging = null; commit(order) },
                                    onDragCancel = { dragging = null; order = ids.assign(bar.items, order) },
                                ) { change, amount ->
                                    change.consume()
                                    val info = listState.layoutInfo
                                    val size = rowOf(id)?.size ?: 0
                                    dragTop = (dragTop + amount.y).coerceIn(info.viewportStartOffset - size / 2f, info.viewportEndOffset - size / 2f)
                                    swapIfPassed()
                                }
                            },
                        )
                    },
                    headlineContent = {
                        if (item.isPanel) {
                            // The same single-colour glyph the bar draws, with a name.
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                BarGlyph(if (item.type == BarItem.TYPE_EMOJI) dev.shebang.devboard.view.KeyIcons.emoji else dev.shebang.devboard.view.KeyIcons.clipboard)
                                Spacer(Modifier.width(10.dp))
                                Text(if (item.type == BarItem.TYPE_EMOJI) "Emoji" else "Clipboard")
                            }
                        } else {
                            Text(item.label)
                        }
                    },
                    supportingContent = { Text(describe(item)) },
                    trailingContent = {
                        IconButton(onClick = { commit(order.filter { it.first != id }) }, enabled = order.size > 1) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove")
                        }
                    },
                    tonalElevation = if (lifted) 6.dp else 0.dp,
                    modifier = Modifier
                        .zIndex(if (lifted) 1f else 0f)
                        // The lifted row is drawn under the finger wherever the list has laid it out; the others
                        // slide to their new places.
                        .then(
                            if (lifted) Modifier.graphicsLayer { translationY = rowOf(id)?.let { dragTop - it.offset } ?: 0f }.shadow(6.dp)
                            else Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)
                        )
                        .semantics {
                            // Without dragging (TalkBack): move up and down from the item's actions.
                            customActions = listOfNotNull(
                                if (index > 0) CustomAccessibilityAction("Move up") { commit(order.move(index, index - 1)); true } else null,
                                if (index < order.size - 1) CustomAccessibilityAction("Move down") { commit(order.move(index, index + 1)); true } else null,
                            )
                        },
                )
                HorizontalDivider()
            }
        }
    }
    if (addDialog) AddItemDialog(onDismiss = { addDialog = false }) { item ->
        addDialog = false
        onChange(BarConfig(bar.items + item))
    }
}

/** One of the keyboard's glyphs, in the text colour, 22 dp square. */
@Composable
private fun BarGlyph(path: android.graphics.Path) {
    val color = androidx.compose.material3.LocalContentColor.current
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { 22.dp.toPx() }
    androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
        val d = dev.shebang.devboard.view.IconDrawable(path, color.toArgb(), px)
        d.setBounds(0, 0, size.width.toInt(), size.height.toInt())
        drawIntoCanvas { d.draw(it.nativeCanvas) }
    }
}

private fun describe(item: BarItem): String = when {
    item.type == BarItem.TYPE_EMOJI -> "Opens the emoji panel"
    item.type == BarItem.TYPE_CLIPBOARD -> "Opens the clipboard history"
    item.isModifier -> "Sticky modifier: ${item.mod}"
    item.isSnippet -> "Snippet: \"${item.text}\""
    else -> buildString {
        append("Key: ")
        if (item.mods.isNotEmpty()) append(item.mods.joinToString("+") { it.replaceFirstChar(Char::uppercase) }).append("+")
        append(item.code)
        if (item.repeat) append(" (repeats)")
    }
}

/**
 * Ids for bar items in the editor: an item keeps its id when the list is saved and comes back the same, and
 * an item that is new gets one never used before.
 */
private class IdSource {
    private var next = 0L

    fun assign(items: List<BarItem>, previous: List<Pair<Long, BarItem>>): List<Pair<Long, BarItem>> {
        val free = previous.toMutableList()
        return items.map { item ->
            val i = free.indexOfFirst { it.second == item }
            if (i >= 0) free.removeAt(i) else (next++ to item)
        }
    }
}

private fun <T> List<T>.move(from: Int, to: Int): List<T> {
    if (to !in indices || from !in indices) return this
    val m = toMutableList()
    val v = m.removeAt(from)
    m.add(to, v)
    return m
}

@Composable
private fun AddItemDialog(onDismiss: () -> Unit, onAdd: (BarItem) -> Unit) {
    var type by remember { mutableStateOf(BarItem.TYPE_KEY) }
    var label by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("ESCAPE") }
    var codeMenu by remember { mutableStateOf(false) }
    var ctrl by remember { mutableStateOf(false) }
    var alt by remember { mutableStateOf(false) }
    var shift by remember { mutableStateOf(false) }
    var repeat by remember { mutableStateOf(false) }
    var mod by remember { mutableStateOf("ctrl") }
    var text by remember { mutableStateOf("") }

    val item: BarItem? = runCatching {
        when (type) {
            BarItem.TYPE_KEY -> BarItem.key(label.ifBlank { code }, code, *listOfNotNull(if (ctrl) "ctrl" else null, if (alt) "alt" else null, if (shift) "shift" else null).toTypedArray(), repeat = repeat)
            BarItem.TYPE_MODIFIER -> BarItem.modifier(label.ifBlank { mod.replaceFirstChar(Char::uppercase) }, mod)
            BarItem.TYPE_EMOJI -> if (label.isBlank()) BarItem.emoji() else BarItem.emoji(label)
            BarItem.TYPE_CLIPBOARD -> if (label.isBlank()) BarItem.clipboard() else BarItem.clipboard(label)
            else -> BarItem.snippet(label.ifBlank { text.trim() }, text)
        }.also { it.validate() }
    }.getOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add bar item") },
        confirmButton = { Button(onClick = { item?.let(onAdd) }, enabled = item != null) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((t, l) in listOf(BarItem.TYPE_KEY to "Key", BarItem.TYPE_MODIFIER to "Modifier", BarItem.TYPE_SNIPPET to "Snippet")) {
                        FilterChip(selected = type == t, onClick = { type = t }, label = { Text(l) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((t, l) in listOf(BarItem.TYPE_EMOJI to "Emoji", BarItem.TYPE_CLIPBOARD to "Clipboard")) {
                        FilterChip(selected = type == t, onClick = { type = t }, label = { Text(l) })
                    }
                }
                OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Label (optional)") }, singleLine = true)
                when (type) {
                    BarItem.TYPE_KEY -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Looks like the button it is: an outlined field-like button with a drop-down arrow.
                            androidx.compose.material3.OutlinedButton(onClick = { codeMenu = true }, modifier = Modifier.fillMaxWidth()) {
                                Text("Key: $code", modifier = Modifier.weight(1f))
                                Icon(Icons.Default.ArrowDropDown, contentDescription = "Choose a key")
                            }
                            DropdownMenu(expanded = codeMenu, onDismissRequest = { codeMenu = false }) {
                                for (name in KeyCodeNames.names) DropdownMenuItem(text = { Text(name) }, onClick = { code = name; codeMenu = false })
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = ctrl, onClick = { ctrl = !ctrl }, label = { Text("Ctrl") })
                            FilterChip(selected = alt, onClick = { alt = !alt }, label = { Text("Alt") })
                            FilterChip(selected = shift, onClick = { shift = !shift }, label = { Text("Shift") })
                            FilterChip(selected = repeat, onClick = { repeat = !repeat }, label = { Text("Repeat") })
                        }
                    }
                    BarItem.TYPE_MODIFIER -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (m in listOf("ctrl", "alt", "shift", "meta")) FilterChip(selected = mod == m, onClick = { mod = m }, label = { Text(m.replaceFirstChar(Char::uppercase)) })
                    }
                    BarItem.TYPE_EMOJI -> Text("Opens the emoji panel in place of the keys.")
                    BarItem.TYPE_CLIPBOARD -> Text("Opens your recent copies in place of the keys.")
                    else -> OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Text to insert") })
                }
                Spacer(Modifier.width(1.dp))
            }
        },
    )
}

// ---- Glide recorder ----------------------------------------------------------------------------------

/** The most frequent glide-able words, shuffled: the prompts for recording. */
private fun recorderPrompts(context: android.content.Context): List<String> {
    val dictionary = context.assets.open(dev.shebang.devboard.ime.LanguageLoader.DICTIONARY_ASSET).bufferedReader()
        .useLines { dev.shebang.devboard.dict.Dictionary.parse(it) }
    val lm = context.assets.open(dev.shebang.devboard.dict.NgramModel.ASSET).use { dev.shebang.devboard.dict.NgramModel.load(it, dictionary) }
    val seq = IntArray(64)
    val words = (0 until dictionary.size)
        .filter { dev.shebang.devboard.glide.LexiconTrie.keySequence(dictionary.lower[it], seq) >= 2 }
        .sortedBy { lm.unigramCost(it) }
        .map { dictionary.lower[it] }
        .distinct()
        .take(1000)
    return words.shuffled()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlideRecorderScreen(settings: Settings, onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { GlideTraceStore(context) }
    val scope = rememberCoroutineScope()
    var count by remember { mutableStateOf(0) }
    var prompts by remember { mutableStateOf<List<String>>(emptyList()) }
    var index by remember { mutableStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        count = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store.count() }
        prompts = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { recorderPrompts(context) }
    }
    val prompt = prompts.getOrNull(index % maxOf(1, prompts.size))
    val currentPrompt = androidx.compose.runtime.rememberUpdatedState(prompt)
    val density = androidx.compose.ui.platform.LocalDensity.current.density

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { store.exportTo(it) } }.isSuccess
            }
            Toast.makeText(context, if (ok) "Glides exported" else "Export failed", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Record glides") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            actions = {
                TextButton(onClick = { exportLauncher.launch("devboard-glides.jsonl") }, enabled = count > 0) { Text("Export") }
                IconButton(onClick = { confirmClear = true }, enabled = count > 0) { Icon(Icons.Default.Delete, contentDescription = "Delete all") }
            },
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                "Glide each word on the keyboard below. Nothing else is recorded, and samples stay on this phone until you export them.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
            Text(
                prompt ?: "Loading words\u2026",
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Text(
                "$count recorded",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = { index++ }, enabled = prompt != null) { Text("Skip") }
                TextButton(onClick = {
                    scope.launch {
                        count = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store.removeLast(); store.count() }
                    }
                }, enabled = count > 0) { Text("Undo last") }
            }
            Spacer(Modifier.weight(1f))
            androidx.compose.ui.viewinterop.AndroidView(
                factory = { ctx ->
                    GlideRecorderView(ctx, dev.shebang.devboard.ime.LayoutRepository(ctx).text, settings) { xs, ys, ts, g ->
                        val word = currentPrompt.value ?: return@GlideRecorderView
                        val trace = GlideRecorderView.trace(word, xs, ys, ts, g, density)
                        index++
                        scope.launch {
                            count = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store.add(trace); store.count() }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete all recorded glides?") },
            text = { Text("This removes the $count samples kept on this phone.") },
            confirmButton = {
                Button(onClick = {
                    confirmClear = false
                    scope.launch { count = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store.clear(); 0 } }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

/** Where the project lives. */
private const val REPO_URL = "https://github.com/aquilaelabs/Shebang-DevBoard"
