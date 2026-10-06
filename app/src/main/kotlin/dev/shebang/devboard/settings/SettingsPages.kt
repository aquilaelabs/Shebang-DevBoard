package dev.shebang.devboard.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import dev.shebang.devboard.ime.VoiceClient
import dev.shebang.devboard.view.Palettes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The settings pages the home page opens, in its order. */
enum class SettingsPageId(val title: String) {
    APPEARANCE("Appearance"),
    TYPING("Typing and glide"),
    CORRECTIONS("Corrections and suggestions"),
    DICTIONARIES("Dictionaries"),
    SOUND("Sound and vibration"),
    BAR("Terminal bar"),
    VOICE("Voice typing"),
    LEARNING("Learning and privacy"),
    ABOUT("About"),
}

/** What the settings pages open beyond themselves. */
class SettingsActions(
    val update: ((Settings) -> Settings) -> Unit,
    val onEditBar: () -> Unit,
    val onRecordGlides: () -> Unit,
    val onPersonalWords: () -> Unit,
    val onDictionary: () -> Unit,
    val onDoc: (asset: String, title: String) -> Unit,
    val onSetup: () -> Unit,
)

internal const val REPO_URL = "https://github.com/aquilaelabs/Shebang-DevBoard"

private fun voiceInstalled(context: Context): Boolean = context.packageManager.queryIntentServices(
    Intent("dev.shebang.devboard.voice.LISTEN").setPackage(VoiceClient.PACKAGE), 0,
).isNotEmpty()

private fun appVersion(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""

private fun onOff(on: Boolean) = if (on) "on" else "off"

/**
 * The first settings page: the app and whether it is the keyboard in use, then one row per page, each
 * saying how things stand on it.
 */
@Composable
fun SettingsHome(settings: Settings, actions: SettingsActions, onOpen: (SettingsPageId) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val version = remember { appVersion(context) }
    val voice = remember { voiceInstalled(context) }
    var status by remember { mutableStateOf(ImeStatus.check(context)) }
    LaunchedEffect(Unit) {
        // The system sends no broadcast when the keyboard is enabled or picked.
        while (true) {
            delay(1500)
            status = ImeStatus.check(context)
        }
    }
    val themeName = Palettes.choices.firstOrNull { it.first == settings.palette }?.second ?: settings.palette
    val packStore = remember { dev.shebang.devboard.dict.WordPackStore.get(context.filesDir) }
    val dictionariesSummary = remember(status) {
        val on = dev.shebang.devboard.dict.WordPacks.builtIn.count { packStore.isEnabled(it.key) }
        val lists = packStore.lists().count { it.enabled }
        "Regular words and $on of ${dev.shebang.devboard.dict.WordPacks.builtIn.size} packs" + if (lists > 0) " · ${plural(lists, "list")} of yours" else ""
    }
    fun summary(page: SettingsPageId): String = when (page) {
        SettingsPageId.APPEARANCE -> buildString {
            append(themeName).append(" theme · ").append(kotlin.math.round(settings.heightScale * 100).toInt()).append("% height")
            if (settings.oneHanded) append(" · one-handed")
        }
        SettingsPageId.TYPING -> if (settings.glide) "Glide on · trail ${onOff(settings.glideTrail)}" else "Glide off"
        SettingsPageId.CORRECTIONS -> "Autocorrect ${onOff(settings.autocorrect)} · next word ${onOff(settings.nextWord)}"
        SettingsPageId.SOUND -> {
            val buzz = if (!settings.haptics) "Vibration off" else "Vibration " + listOf("light", "medium", "strong")[settings.hapticStrength - 1]
            "$buzz · key sounds ${onOff(settings.keySounds)}"
        }
        SettingsPageId.BAR -> (if (settings.barJson == null) "Default bar" else "Your own bar") + " · strip: " + stripLabel(settings.stripMode)
        SettingsPageId.VOICE -> if (voice) "Shebang Voice is installed" else "Add-on not installed"
        SettingsPageId.DICTIONARIES -> dictionariesSummary
        SettingsPageId.LEARNING -> (if (settings.learnWords) "Learns your words" else "Not learning words") + ", on this phone only"
        SettingsPageId.ABOUT -> "Version $version · licence, credits, privacy, diagnostics"
    }
    fun icon(page: SettingsPageId) = when (page) {
        SettingsPageId.APPEARANCE -> SettingsIcons.keyboard
        SettingsPageId.TYPING -> SettingsIcons.glide
        SettingsPageId.CORRECTIONS -> SettingsIcons.correct
        SettingsPageId.SOUND -> SettingsIcons.sound
        SettingsPageId.BAR -> SettingsIcons.terminal
        SettingsPageId.VOICE -> dev.shebang.devboard.view.KeyIcons.mic
        SettingsPageId.DICTIONARIES -> SettingsIcons.book
        SettingsPageId.LEARNING -> SettingsIcons.lock
        SettingsPageId.ABOUT -> SettingsIcons.info
    }

    SettingsPage(title = "Settings", onBack = onBack) {
        item { HomeHeader(version, status, actions.onSetup) }
        val groups = listOf(
            "Keyboard" to listOf(
                SettingsPageId.APPEARANCE, SettingsPageId.TYPING, SettingsPageId.CORRECTIONS, SettingsPageId.DICTIONARIES,
                SettingsPageId.SOUND, SettingsPageId.BAR, SettingsPageId.VOICE,
            ),
            "Your data" to listOf(SettingsPageId.LEARNING),
            null to listOf(SettingsPageId.ABOUT),
        )
        for ((title, pages) in groups) item {
            SettingsGroup(title) {
                pages.forEachIndexed { i, page ->
                    if (i > 0) RowDivider()
                    NavRow(page.title, summary(page), icon(page)) { onOpen(page) }
                }
            }
        }
    }
}

/** The app's mark, name and version, and whether it is set up as the keyboard (with a way to finish if not). */
@Composable
private fun HomeHeader(version: String, status: ImeStatus, onSetup: () -> Unit) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainer, contentColor = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppMark(56.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Shebang DevBoard", style = MaterialTheme.typography.titleLarge)
                    Text("Version $version · on-device, no network", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.padding(top = 12.dp))
            val ready = status.enabled && status.selected
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (ready) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                    contentDescription = null,
                    tint = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    when {
                        ready -> "It is your current keyboard"
                        status.enabled -> "Turned on, but not your current keyboard"
                        else -> "Not turned on in the system's keyboard list"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (!ready) Button(onClick = onSetup, shape = RoundedCornerShape(6.dp)) { Text("Set up") }
            }
        }
    }
}

private fun plural(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

/**
 * The word lists the keyboard uses: the regular words (always on), the built-in packs, each with a switch, the
 * lists the user imported (on or off, or deleted), and importing another from a text file.
 */
@Composable
private fun DictionariesGroups(actions: SettingsActions) {
    val context = LocalContext.current
    val store = remember { dev.shebang.devboard.dict.WordPackStore.get(context.filesDir) }
    val scope = rememberCoroutineScope()
    // Bumped after every change, so the rows read the store again.
    var changes by remember { mutableStateOf(0) }
    var counts by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var deleting by remember { mutableStateOf<dev.shebang.devboard.dict.WordPackStore.ImportedList?>(null) }
    LaunchedEffect(Unit) {
        counts = withContext(Dispatchers.IO) {
            (listOf(dev.shebang.devboard.dict.WordPacks.REGULAR_ASSET) + dev.shebang.devboard.dict.WordPacks.builtIn.map { it.asset }).associateWith { asset ->
                runCatching { context.assets.open(asset).bufferedReader().useLines { lines -> lines.count { it.isNotBlank() } } }.getOrDefault(0)
            }
        }
    }
    fun change(action: () -> Unit) {
        scope.launch {
            withContext(Dispatchers.IO) { action() }
            changes++
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val name = runCatching {
                    context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    }
                }.getOrNull()?.substringBeforeLast('.') ?: "Word list"
                runCatching { context.contentResolver.openInputStream(uri)?.use { store.import(name, it) } }.getOrNull()
                    ?: dev.shebang.devboard.dict.WordPackStore.ImportResult(null, 0, "The file could not be read.")
            }
            changes++
            val list = result.list
            val message = if (list == null) result.error ?: "No words found." else buildString {
                append("Added ").append("%,d".format(list.words)).append(if (list.words == 1) " word" else " words").append(" from ").append(list.name)
                if (result.skipped > 0) append(" (").append("%,d".format(result.skipped)).append(" lines left out)")
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
    val lists = remember(changes) { store.lists() }
    Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
        PageNote(
            "Where two words are about as likely, glide and suggestions prefer regular words, then brands, names and your " +
                "own lists, then development words, then computer terms. Autocorrect only ever corrects to everyday words, and a word typed exactly as a " +
                "pack spells it is left as typed. Changes apply the next time the keyboard opens.",
        )
        SettingsGroup("Built in") {
            // Always on: a label, not a switch that cannot move (a disabled switch reads as off).
            androidx.compose.material3.ListItem(
                headlineContent = { Text("Regular words") },
                supportingContent = { Text("${countLabel(counts[dev.shebang.devboard.dict.WordPacks.REGULAR_ASSET])}everyday English") },
                trailingContent = { Text("Always on", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) },
                colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            )
            for (p in dev.shebang.devboard.dict.WordPacks.builtIn) {
                RowDivider()
                val on = remember(changes) { store.isEnabled(p.key) }
                SwitchRow(p.title, "${countLabel(counts[p.asset])}${p.summary}", on) { v -> change { store.setEnabled(p.key, v) } }
            }
        }
        SettingsGroup("Your word lists") {
            for (l in lists) {
                androidx.compose.material3.ListItem(
                    headlineContent = { Text(l.name) },
                    supportingContent = { Text("%,d".format(l.words) + if (l.words == 1) " word" else " words") },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.IconButton(onClick = { deleting = l }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete ${l.name}")
                            }
                            androidx.compose.material3.Switch(checked = l.enabled, onCheckedChange = { v -> change { store.setListEnabled(l.id, v) } })
                        }
                    },
                    colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                )
                RowDivider()
            }
            NavRow(
                "Import a word list",
                "A text file with one word per line, such as names, project terms or another language's words. A CSV works " +
                    "too (its first column). Kept on this phone.",
                opensPage = false,
            ) { importer.launch(arrayOf("text/*", "application/csv", "application/octet-stream")) }
        }
        SettingsGroup("Edit") {
            NavRow("Built-in words", "Browse every built-in word, remove ones you never want offered, and restore them any time", onClick = actions.onDictionary)
        }
    }
    deleting?.let { l ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${l.name}?") },
            text = { Text("Its words are no longer offered or glided. The file you imported it from is not touched.") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { deleting = null; change { store.delete(l.id) } }) { Text("Delete") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

private fun countLabel(n: Int?): String = if (n == null || n == 0) "" else "%,d words · ".format(n)

private fun stripLabel(m: StripMode) = when (m) {
    StripMode.AUTO -> "Auto"
    StripMode.ALWAYS_BAR -> "Always bar"
    StripMode.TWO_ROWS -> "Two rows"
}

/** One settings page's groups. */
@Composable
fun SettingsCategory(page: SettingsPageId, settings: Settings, actions: SettingsActions, onBack: () -> Unit) {
    val update = actions.update
    SettingsPage(title = page.title, onBack = onBack) {
        when (page) {
            SettingsPageId.APPEARANCE -> {
                item {
                    SettingsGroup("Theme") {
                        ThemePicker(settings.palette) { v -> update { it.copy(palette = v) } }
                    }
                }
                item {
                    SettingsGroup("Size and layout") {
                        // Rounded, not cut off: 1.1 is stored as 1.0999999 and read as 109%. Stored snapped to the
                        // slider's 10% steps.
                        SliderRow("Keyboard height", "${kotlin.math.round(settings.heightScale * 100).toInt()}%", settings.heightScale, 0.7f..1.4f, steps = 6) { v ->
                            update { it.copy(heightScale = kotlin.math.round(v * 10) / 10f) }
                        }
                        RowDivider()
                        // Also from the bar's one-handed item, and the side panel's arrows while it is on.
                        ChoiceRow(
                            "One-handed mode",
                            "Narrower keys on one side, within a thumb's reach",
                            listOf("off" to "Off", "left" to "Left", "right" to "Right"),
                            if (!settings.oneHanded) "off" else if (settings.oneHandedLeft) "left" else "right",
                        ) { v -> update { if (v == "off") it.copy(oneHanded = false) else it.copy(oneHanded = true, oneHandedLeft = v == "left") } }
                        RowDivider()
                        SwitchRow("Number row", "Digits above the letters in text mode", settings.numberRow) { v -> update { it.copy(numberRow = v) } }
                        RowDivider()
                        SwitchRow("Key preview", "Pop up the character while a key is pressed", settings.keyPreview) { v -> update { it.copy(keyPreview = v) } }
                    }
                }
            }
            SettingsPageId.TYPING -> {
                item {
                    SettingsGroup("Glide typing") {
                        SwitchRow("Glide typing", "Slide across letters to write a word", settings.glide) { v -> update { it.copy(glide = v) } }
                        RowDivider()
                        SwitchRow("Glide trail", "Draw the path while gliding", settings.glideTrail, enabled = settings.glide) { v -> update { it.copy(glideTrail = v) } }
                        RowDivider()
                        SwitchRow("Phrase gliding", "Dip into the space bar mid-glide to start the next word", settings.phraseGlide, enabled = settings.glide) { v ->
                            update { it.copy(phraseGlide = v) }
                        }
                        RowDivider()
                        NavRow("Record glides", "Glide prompted words to measure accuracy on your own fingers. Kept on this phone.", onClick = actions.onRecordGlides)
                    }
                }
                item {
                    SettingsGroup("Keys") {
                        SwitchRow("Hold backspace for whole words", "After a second of holding backspace, it deletes a word at a time", settings.holdDeletesWords) { v ->
                            update { it.copy(holdDeletesWords = v) }
                        }
                    }
                }
            }
            SettingsPageId.CORRECTIONS -> {
                item {
                    SettingsGroup("As you type") {
                        SwitchRow("Autocorrect", "Fix the word when you press space", settings.autocorrect) { v -> update { it.copy(autocorrect = v) } }
                        RowDivider()
                        SwitchRow("Auto-capitalize", "Shift at the start of sentences", settings.autoCaps) { v -> update { it.copy(autoCaps = v) } }
                        RowDivider()
                        SwitchRow("Double-space period", "Two spaces insert \". \"", settings.doubleSpacePeriod) { v -> update { it.copy(doubleSpacePeriod = v) } }
                    }
                }
                item {
                    SettingsGroup("Suggestions") {
                        SwitchRow("Next-word suggestions", "After a space, the strip offers the words likely to come next", settings.nextWord) { v -> update { it.copy(nextWord = v) } }
                        RowDivider()
                        SwitchRow("Fix the last glided word", "When the next glide makes it unlikely, the word glided just before is corrected; tap it to change it back", settings.fixPreviousGlide) { v ->
                            update { it.copy(fixPreviousGlide = v) }
                        }
                    }
                }
                item {
                    SettingsGroup("Code mode") {
                        SwitchRow("Pair brackets and quotes", "( [ { and quotes come in pairs, and typing the closing one steps over it", settings.pairBrackets) { v ->
                            update { it.copy(pairBrackets = v) }
                        }
                    }
                }
            }
            SettingsPageId.SOUND -> {
                item {
                    SettingsGroup("Vibration") {
                        SwitchRow("Vibrate on key press", null, settings.haptics) { v -> update { it.copy(haptics = v) } }
                        RowDivider()
                        ChoiceRow("Strength", null, listOf(1 to "Light", 2 to "Medium", 3 to "Strong"), settings.hapticStrength, enabled = settings.haptics) { v ->
                            update { it.copy(hapticStrength = v) }
                        }
                    }
                }
                item {
                    SettingsGroup("Sound") {
                        SwitchRow("Key sounds", "The system's own key clicks", settings.keySounds) { v -> update { it.copy(keySounds = v) } }
                    }
                }
            }
            SettingsPageId.BAR -> {
                item {
                    SettingsGroup {
                        ChoiceRow(
                            "Strip above the keys",
                            when (settings.stripMode) {
                                StripMode.AUTO -> "Suggestions while you type a word, the terminal bar otherwise"
                                StripMode.ALWAYS_BAR -> "Always the terminal bar; no suggestions"
                                StripMode.TWO_ROWS -> "The bar and the suggestions, one above the other"
                            },
                            listOf(StripMode.AUTO to "Auto", StripMode.ALWAYS_BAR to "Always bar", StripMode.TWO_ROWS to "Two rows"),
                            settings.stripMode,
                        ) { v -> update { it.copy(stripMode = v) } }
                        RowDivider()
                        NavRow(
                            "Edit terminal bar",
                            if (settings.barJson == null) "The default bar. Add keys, snippets and actions, reorder, or give an app its own bar." else "Your own bar. Add, reorder, or give an app its own bar.",
                            onClick = actions.onEditBar,
                        )
                    }
                }
            }
            SettingsPageId.VOICE -> {
                item {
                    val context = LocalContext.current
                    val installed = remember { voiceInstalled(context) }
                    SettingsGroup {
                        // Voice typing is a separate app (it holds the microphone permission; the keyboard does not).
                        NavRow(
                            if (installed) "Shebang Voice is installed" else "Get Shebang Voice",
                            if (installed) "Tap the mic at the end of the strip. Speech is turned into text on this phone."
                            else "A separate add-on from GitHub (about 60 MB) that turns speech into text on this phone. The keyboard itself never uses the microphone.",
                            opensPage = !installed,
                            onClick = if (installed) null else ({ runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(VoiceClient.RELEASES_URL))) } }),
                        )
                        RowDivider()
                        SwitchRow("Tidy dictation", "Drop um and uh, stutters and repeats, and act on spoken corrections like \"no wait\" and \"scratch that\"", settings.tidyDictation) { v ->
                            update { it.copy(tidyDictation = v) }
                        }
                    }
                }
            }
            SettingsPageId.LEARNING -> {
                item { PageNote("Everything here stays on this phone. The keyboard has no network access.") }
                item {
                    SettingsGroup("What it learns") {
                        SwitchRow("Learn words I type", "New words and the ones you use most", settings.learnWords) { v -> update { it.copy(learnWords = v) } }
                        RowDivider()
                        SwitchRow("Remember email addresses", "Offer the addresses you entered in email fields as you type them again", settings.rememberEmails) { v ->
                            update { it.copy(rememberEmails = v) }
                        }
                        RowDivider()
                        SwitchRow("Adapt autocorrect to my taps", "Learn where your taps land on each key, from the words you type right", settings.adaptTaps) { v -> update { it.copy(adaptTaps = v) } }
                        RowDivider()
                        SwitchRow("Adapt glide to my swiping", "Learn how your glides lean off each key, most from the words you correct", settings.adaptGlide, enabled = settings.glide) { v ->
                            update { it.copy(adaptGlide = v) }
                        }
                    }
                }
                item {
                    SettingsGroup("Words") {
                        NavRow("Personal words", "Add words, review or delete what was learned and the addresses remembered, or reset adaptation", onClick = actions.onPersonalWords)
                    }
                }
            }
            SettingsPageId.ABOUT -> {
                item { AboutGroups(settings, actions) }
            }
            SettingsPageId.DICTIONARIES -> {
                item { DictionariesGroups(actions) }
            }
        }
    }
}

@Composable
private fun AboutGroups(settings: Settings, actions: SettingsActions) {
    val context = LocalContext.current
    val version = remember { appVersion(context) }
    val scope = rememberCoroutineScope()
    // Off each time the page opens: including the recent fields is chosen for one export, not kept.
    var includeFields by remember { mutableStateOf(false) }
    val diagnosticsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)?.use { DiagnosticsExport.write(context, settings, it, includeFields) } }.isSuccess
            }
            Toast.makeText(context, if (ok) "Diagnostics saved" else "Couldn't save diagnostics", Toast.LENGTH_SHORT).show()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
        SettingsGroup {
            NavRow("Shebang DevBoard $version", "A keyboard for developers. MIT licence.", opensPage = true) { actions.onDoc("about/LICENSE", "Licence") }
            RowDivider()
            NavRow("Source code", REPO_URL.removePrefix("https://"), opensPage = false) {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL))) }
            }
            RowDivider()
            NavRow("Credits", "The word lists, data, models and libraries this keyboard is built on, with their licences") { actions.onDoc("about/THIRD_PARTY_NOTICES.md", "Credits") }
            RowDivider()
            NavRow("Privacy policy", "Nothing you type or say leaves your phone. What the keyboard keeps, and how to delete it") { actions.onDoc("about/PRIVACY.md", "Privacy policy") }
        }
        SettingsGroup("Help") {
            SwitchRow(
                "Include recent fields",
                "Add the last ${dev.shebang.devboard.ime.RecentFields.MAX} fields the keyboard opened in: which app, and what kind of field " +
                    "it said it was (search box, email, password...). Never their text. Helps when typing goes wrong in one app.",
                includeFields,
            ) { includeFields = it }
            RowDivider()
            NavRow(
                "Export diagnostics",
                "Save a file to send to the developer if typing or gliding isn't working well: settings, how your taps and glides lean, " +
                    "how recent glides ended up, recorded glides, and where the app crashed. Learned words, email addresses, the clipboard, " +
                    "your terminal bar and anything you typed are left out.",
                opensPage = false,
            ) { diagnosticsLauncher.launch("devboard-diagnostics.json") }
        }
    }
}
