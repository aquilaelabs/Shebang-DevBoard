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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
            DevBoardTheme(settings.palette) {
                if (personalWords) {
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
    onBack: () -> Unit,
) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("DevBoard settings") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
        )
    }) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item { SectionHeader("Appearance") }
            item { ThemePicker(settings.palette) { v -> update { it.copy(palette = v) } } }
            item {
                SliderRow("Keyboard height", "${(settings.heightScale * 100).toInt()}%", settings.heightScale, 0.7f..1.4f, steps = 6) { v -> update { it.copy(heightScale = v) } }
            }
            item { SwitchRow("Number row", "Digits above the letters in text mode", settings.numberRow) { v -> update { it.copy(numberRow = v) } } }
            item { SwitchRow("Key preview", "Pop up the character while a key is pressed", settings.keyPreview) { v -> update { it.copy(keyPreview = v) } } }
            item { SwitchRow("Flick up for symbols", "A quick flick up on a key types the character in its corner", settings.flickSymbols) { v -> update { it.copy(flickSymbols = v) } } }

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
            item { SectionHeader("Learning") }
            item { SwitchRow("Learn words I type", "Remember new words and the ones you use most, on this phone only", settings.learnWords) { v -> update { it.copy(learnWords = v) } } }
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
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
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
            itemsIndexed(bar.items, key = { i, item -> "$i-${item.label}" }) { index, item ->
                ListItem(
                    headlineContent = { Text(item.label) },
                    supportingContent = { Text(describe(item)) },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { onChange(BarConfig(bar.items.move(index, index - 1))) }, enabled = index > 0) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Move up")
                            }
                            IconButton(onClick = { onChange(BarConfig(bar.items.move(index, index + 1))) }, enabled = index < bar.items.size - 1) {
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Move down")
                            }
                            IconButton(onClick = { onChange(BarConfig(bar.items.filterIndexed { i, _ -> i != index })) }, enabled = bar.items.size > 1) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove")
                            }
                        }
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

private fun describe(item: BarItem): String = when {
    item.isModifier -> "Sticky modifier: ${item.mod}"
    item.isSnippet -> "Snippet: \"${item.text}\""
    else -> buildString {
        append("Key: ")
        if (item.mods.isNotEmpty()) append(item.mods.joinToString("+") { it.replaceFirstChar(Char::uppercase) }).append("+")
        append(item.code)
        if (item.repeat) append(" (repeats)")
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
                OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Label (optional)") }, singleLine = true)
                when (type) {
                    BarItem.TYPE_KEY -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(onClick = { codeMenu = true }) { Text("Key: $code") }
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
