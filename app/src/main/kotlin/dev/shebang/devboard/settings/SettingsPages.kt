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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import dev.shebang.devboard.ime.VoiceClient
import dev.shebang.devboard.view.Palettes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.shebang.devboard.R

/** The settings pages the home page opens, in its order. */
enum class SettingsPageId(@androidx.annotation.StringRes val title: Int) {
    APPEARANCE(R.string.page_appearance),
    TYPING(R.string.page_typing),
    CORRECTIONS(R.string.page_corrections),
    DICTIONARIES(R.string.page_dictionaries),
    SOUND(R.string.page_sound),
    BAR(R.string.page_bar),
    VOICE(R.string.page_voice),
    LEARNING(R.string.page_learning),
    ABOUT(R.string.page_about),
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

private fun onOff(res: android.content.res.Resources, on: Boolean) = res.getString(if (on) R.string.summary_on else R.string.summary_off)

/**
 * The first settings page: the app and whether it is the keyboard in use, then one row per page, each
 * saying how things stand on it.
 */
@Composable
fun SettingsHome(settings: Settings, actions: SettingsActions, onOpen: (SettingsPageId) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
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
        val packs = dev.shebang.devboard.dict.WordPacks.builtIn.size
        if (lists > 0) resources.getQuantityString(R.plurals.summary_dictionaries_lists, packs, on, packs, resources.getQuantityString(R.plurals.summary_lists, lists, lists))
        else resources.getQuantityString(R.plurals.summary_dictionaries, packs, on, packs)
    }
    fun summary(page: SettingsPageId): String = when (page) {
        SettingsPageId.APPEARANCE -> resources.getString(
            if (settings.oneHanded) R.string.summary_appearance_one_handed else R.string.summary_appearance,
            themeName, kotlin.math.round(settings.heightScale * 100).toInt(),
        )
        SettingsPageId.TYPING -> if (settings.glide) resources.getString(R.string.summary_typing, onOff(resources, settings.glideTrail)) else resources.getString(R.string.summary_typing_off)
        SettingsPageId.CORRECTIONS -> resources.getString(R.string.summary_corrections, onOff(resources, settings.autocorrect), onOff(resources, settings.nextWord))
        SettingsPageId.SOUND -> {
            val strength = listOf(R.string.vibration_light, R.string.vibration_medium, R.string.vibration_strong)[settings.hapticStrength - 1]
            val buzz = if (!settings.haptics) resources.getString(R.string.summary_vibration_off) else resources.getString(R.string.summary_vibration, resources.getString(strength))
            resources.getString(R.string.summary_sound, buzz, onOff(resources, settings.keySounds))
        }
        SettingsPageId.BAR -> resources.getString(if (settings.barJson == null) R.string.summary_bar_default else R.string.summary_bar_own, stripLabel(resources, settings.stripMode))
        SettingsPageId.VOICE -> resources.getString(if (voice) R.string.summary_voice_installed else R.string.summary_voice_missing)
        SettingsPageId.DICTIONARIES -> dictionariesSummary
        SettingsPageId.LEARNING -> resources.getString(if (settings.learnWords) R.string.summary_learning_on else R.string.summary_learning_off)
        SettingsPageId.ABOUT -> resources.getString(R.string.summary_about, version)
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

    SettingsPage(title = stringResource(R.string.settings_home_title), onBack = onBack) {
        item { HomeHeader(version, status, actions.onSetup) }
        val groups = listOf(
            R.string.home_group_keyboard to listOf(
                SettingsPageId.APPEARANCE, SettingsPageId.TYPING, SettingsPageId.CORRECTIONS, SettingsPageId.DICTIONARIES,
                SettingsPageId.SOUND, SettingsPageId.BAR, SettingsPageId.VOICE,
            ),
            R.string.home_group_data to listOf(SettingsPageId.LEARNING),
            null to listOf(SettingsPageId.ABOUT),
        )
        for ((title, pages) in groups) item {
            SettingsGroup(title?.let { stringResource(it) }) {
                pages.forEachIndexed { i, page ->
                    if (i > 0) RowDivider()
                    NavRow(stringResource(page.title), summary(page), icon(page)) { onOpen(page) }
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
                    Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.home_version, version), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                        ready -> stringResource(R.string.home_ready)
                        status.enabled -> stringResource(R.string.home_enabled_only)
                        else -> stringResource(R.string.home_not_enabled)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                if (!ready) Button(onClick = onSetup, shape = RoundedCornerShape(6.dp)) { Text(stringResource(R.string.home_set_up)) }
            }
        }
    }
}


/**
 * The word lists the keyboard uses: the regular words (always on), the built-in packs, each with a switch, the
 * lists the user imported (on or off, or deleted), and importing another from a text file.
 */
@Composable
private fun DictionariesGroups(actions: SettingsActions) {
    val context = LocalContext.current
    val resources = LocalResources.current
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
                }.getOrNull()?.substringBeforeLast('.') ?: resources.getString(R.string.dict_default_list_name)
                runCatching { context.contentResolver.openInputStream(uri)?.use { store.import(name, it) } }.getOrNull()
                    ?: dev.shebang.devboard.dict.WordPackStore.ImportResult(null, 0, resources.getString(R.string.dict_unreadable))
            }
            changes++
            val list = result.list
            val message = if (list == null) result.error ?: resources.getString(R.string.dict_no_words) else {
                val added = resources.getQuantityString(R.plurals.dict_added, list.words, "%,d".format(list.words), list.name)
                if (result.skipped > 0) resources.getQuantityString(R.plurals.dict_left_out, result.skipped, added, "%,d".format(result.skipped)) else added
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
    val lists = remember(changes) { store.lists() }
    Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
        PageNote(
            stringResource(R.string.dict_note),
        )
        SettingsGroup(stringResource(R.string.dict_group_built_in)) {
            // Always on: a label, not a switch that cannot move (a disabled switch reads as off).
            androidx.compose.material3.ListItem(
                headlineContent = { Text(stringResource(R.string.dict_regular)) },
                supportingContent = { Text(stringResource(R.string.dict_regular_text, countLabel(counts[dev.shebang.devboard.dict.WordPacks.REGULAR_ASSET]))) },
                trailingContent = { Text(stringResource(R.string.dict_always_on), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) },
                colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            )
            for (p in dev.shebang.devboard.dict.WordPacks.builtIn) {
                RowDivider()
                val on = remember(changes) { store.isEnabled(p.key) }
                SwitchRow(p.title, "${countLabel(counts[p.asset])}${p.summary}", on) { v -> change { store.setEnabled(p.key, v) } }
            }
        }
        SettingsGroup(stringResource(R.string.dict_group_lists)) {
            for (l in lists) {
                androidx.compose.material3.ListItem(
                    headlineContent = { Text(l.name) },
                    supportingContent = { Text(pluralStringResource(R.plurals.dict_list_words, l.words, "%,d".format(l.words))) },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.IconButton(onClick = { deleting = l }) {
                                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.dictionary_delete_word, l.name))
                            }
                            androidx.compose.material3.Switch(checked = l.enabled, onCheckedChange = { v -> change { store.setListEnabled(l.id, v) } })
                        }
                    },
                    colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                )
                RowDivider()
            }
            NavRow(
                stringResource(R.string.dict_import),
                stringResource(R.string.dict_import_text),
                opensPage = false,
            ) { importer.launch(arrayOf("text/*", "application/csv", "application/octet-stream")) }
        }
        SettingsGroup(stringResource(R.string.dict_group_edit)) {
            NavRow(stringResource(R.string.dict_built_in_words), stringResource(R.string.dict_built_in_words_text), onClick = actions.onDictionary)
        }
    }
    deleting?.let { l ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.dict_delete_list_title, l.name)) },
            text = { Text(stringResource(R.string.dict_delete_list_text)) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { deleting = null; change { store.delete(l.id) } }) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun countLabel(n: Int?): String = if (n == null || n == 0) "" else pluralStringResource(R.plurals.dict_count_prefix, n, "%,d".format(n))

private fun stripLabel(res: android.content.res.Resources, m: StripMode): String = res.getString(
    when (m) {
        StripMode.AUTO -> R.string.strip_auto
        StripMode.ALWAYS_BAR -> R.string.strip_always_bar
        StripMode.TWO_ROWS -> R.string.strip_two_rows
    },
)

/** One settings page's groups. */
@Composable
fun SettingsCategory(page: SettingsPageId, settings: Settings, actions: SettingsActions, onBack: () -> Unit) {
    val update = actions.update
    SettingsPage(title = stringResource(page.title), onBack = onBack) {
        when (page) {
            SettingsPageId.APPEARANCE -> {
                item {
                    SettingsGroup(stringResource(R.string.group_theme)) {
                        ThemePicker(settings.palette) { v -> update { it.copy(palette = v) } }
                    }
                }
                item {
                    SettingsGroup(stringResource(R.string.group_size)) {
                        // Rounded, not cut off: 1.1 is stored as 1.0999999 and read as 109%. Stored snapped to the
                        // slider's 10% steps.
                        SliderRow(stringResource(R.string.keyboard_height), stringResource(R.string.percent, kotlin.math.round(settings.heightScale * 100).toInt()), settings.heightScale, 0.7f..1.4f, steps = 6) { v ->
                            update { it.copy(heightScale = kotlin.math.round(v * 10) / 10f) }
                        }
                        RowDivider()
                        // Also from the bar's one-handed item, and the side panel's arrows while it is on.
                        ChoiceRow(
                            stringResource(R.string.one_handed),
                            stringResource(R.string.one_handed_text),
                            listOf("off" to stringResource(R.string.one_handed_off), "left" to stringResource(R.string.one_handed_left), "right" to stringResource(R.string.one_handed_right)),
                            if (!settings.oneHanded) "off" else if (settings.oneHandedLeft) "left" else "right",
                        ) { v -> update { if (v == "off") it.copy(oneHanded = false) else it.copy(oneHanded = true, oneHandedLeft = v == "left") } }
                        RowDivider()
                        SwitchRow(stringResource(R.string.number_row), stringResource(R.string.number_row_text), settings.numberRow) { v -> update { it.copy(numberRow = v) } }
                        RowDivider()
                        SwitchRow(stringResource(R.string.key_preview), stringResource(R.string.key_preview_text), settings.keyPreview) { v -> update { it.copy(keyPreview = v) } }
                    }
                }
            }
            SettingsPageId.TYPING -> {
                item {
                    SettingsGroup(stringResource(R.string.group_glide)) {
                        SwitchRow(stringResource(R.string.glide), stringResource(R.string.glide_text), settings.glide) { v -> update { it.copy(glide = v) } }
                        RowDivider()
                        SwitchRow(stringResource(R.string.glide_trail), stringResource(R.string.glide_trail_text), settings.glideTrail, enabled = settings.glide) { v -> update { it.copy(glideTrail = v) } }
                        RowDivider()
                        SwitchRow(stringResource(R.string.phrase_glide), stringResource(R.string.phrase_glide_text), settings.phraseGlide, enabled = settings.glide) { v ->
                            update { it.copy(phraseGlide = v) }
                        }
                        RowDivider()
                        NavRow(stringResource(R.string.record_glides), stringResource(R.string.record_glides_text), onClick = actions.onRecordGlides)
                    }
                }
                item {
                    SettingsGroup(stringResource(R.string.group_keys)) {
                        SwitchRow(stringResource(R.string.hold_backspace), stringResource(R.string.hold_backspace_text), settings.holdDeletesWords) { v ->
                            update { it.copy(holdDeletesWords = v) }
                        }
                    }
                }
            }
            SettingsPageId.CORRECTIONS -> {
                item {
                    SettingsGroup(stringResource(R.string.group_as_you_type)) {
                        SwitchRow(stringResource(R.string.autocorrect), stringResource(R.string.autocorrect_text), settings.autocorrect) { v -> update { it.copy(autocorrect = v) } }
                        RowDivider()
                        SwitchRow(stringResource(R.string.auto_caps), stringResource(R.string.auto_caps_text), settings.autoCaps) { v -> update { it.copy(autoCaps = v) } }
                        RowDivider()
                        SwitchRow(stringResource(R.string.double_space), stringResource(R.string.double_space_text), settings.doubleSpacePeriod) { v -> update { it.copy(doubleSpacePeriod = v) } }
                    }
                }
                item {
                    SettingsGroup(stringResource(R.string.group_suggestions)) {
                        SwitchRow(stringResource(R.string.next_word), stringResource(R.string.next_word_text), settings.nextWord) { v -> update { it.copy(nextWord = v) } }
                        RowDivider()
                        SwitchRow(stringResource(R.string.fix_previous_glide), stringResource(R.string.fix_previous_glide_text), settings.fixPreviousGlide) { v ->
                            update { it.copy(fixPreviousGlide = v) }
                        }
                    }
                }
                item {
                    SettingsGroup(stringResource(R.string.group_code_mode)) {
                        SwitchRow(stringResource(R.string.pair_brackets), stringResource(R.string.pair_brackets_text), settings.pairBrackets) { v ->
                            update { it.copy(pairBrackets = v) }
                        }
                    }
                }
            }
            SettingsPageId.SOUND -> {
                item {
                    SettingsGroup(stringResource(R.string.group_vibration)) {
                        SwitchRow(stringResource(R.string.vibrate), null, settings.haptics) { v -> update { it.copy(haptics = v) } }
                        RowDivider()
                        ChoiceRow(stringResource(R.string.strength), null, listOf(1 to stringResource(R.string.strength_light), 2 to stringResource(R.string.strength_medium), 3 to stringResource(R.string.strength_strong)), settings.hapticStrength, enabled = settings.haptics) { v ->
                            update { it.copy(hapticStrength = v) }
                        }
                    }
                }
                item {
                    SettingsGroup(stringResource(R.string.group_sound)) {
                        SwitchRow(stringResource(R.string.key_sounds), stringResource(R.string.key_sounds_text), settings.keySounds) { v -> update { it.copy(keySounds = v) } }
                    }
                }
            }
            SettingsPageId.BAR -> {
                item {
                    SettingsGroup {
                        ChoiceRow(
                            stringResource(R.string.strip_mode),
                            when (settings.stripMode) {
                                StripMode.AUTO -> stringResource(R.string.strip_auto_text)
                                StripMode.ALWAYS_BAR -> stringResource(R.string.strip_always_bar_text)
                                StripMode.TWO_ROWS -> stringResource(R.string.strip_two_rows_text)
                            },
                            listOf(StripMode.AUTO to stringResource(R.string.strip_auto), StripMode.ALWAYS_BAR to stringResource(R.string.strip_always_bar), StripMode.TWO_ROWS to stringResource(R.string.strip_two_rows)),
                            settings.stripMode,
                        ) { v -> update { it.copy(stripMode = v) } }
                        RowDivider()
                        NavRow(
                            stringResource(R.string.edit_bar),
                            if (settings.barJson == null) stringResource(R.string.edit_bar_default) else stringResource(R.string.edit_bar_own),
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
                            if (installed) stringResource(R.string.summary_voice_installed) else stringResource(R.string.voice_get),
                            if (installed) stringResource(R.string.voice_installed_text)
                            else stringResource(R.string.voice_get_text),
                            opensPage = !installed,
                            onClick = if (installed) null else ({ runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(VoiceClient.RELEASES_URL))) } }),
                        )
                        RowDivider()
                        SwitchRow(stringResource(R.string.tidy_dictation), stringResource(R.string.tidy_dictation_text), settings.tidyDictation) { v ->
                            update { it.copy(tidyDictation = v) }
                        }
                    }
                }
            }
            SettingsPageId.LEARNING -> {
                item { PageNote(stringResource(R.string.learning_note)) }
                item {
                    SettingsGroup(stringResource(R.string.group_learns)) {
                        SwitchRow(stringResource(R.string.learn_words), stringResource(R.string.learn_words_text), settings.learnWords) { v -> update { it.copy(learnWords = v) } }
                        RowDivider()
                        SwitchRow(stringResource(R.string.remember_emails), stringResource(R.string.remember_emails_text), settings.rememberEmails) { v ->
                            update { it.copy(rememberEmails = v) }
                        }
                        RowDivider()
                        SwitchRow(stringResource(R.string.adapt_taps), stringResource(R.string.adapt_taps_text), settings.adaptTaps) { v -> update { it.copy(adaptTaps = v) } }
                        RowDivider()
                        SwitchRow(stringResource(R.string.adapt_glide), stringResource(R.string.adapt_glide_text), settings.adaptGlide, enabled = settings.glide) { v ->
                            update { it.copy(adaptGlide = v) }
                        }
                    }
                }
                item {
                    SettingsGroup(stringResource(R.string.group_words)) {
                        NavRow(stringResource(R.string.personal_title), stringResource(R.string.personal_words_text), onClick = actions.onPersonalWords)
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
    val resources = LocalResources.current
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
            Toast.makeText(context, if (ok) R.string.diagnostics_saved else R.string.diagnostics_failed, Toast.LENGTH_SHORT).show()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(GroupGap)) {
        SettingsGroup {
            NavRow(stringResource(R.string.about_app, version), stringResource(R.string.about_app_text), opensPage = true) { actions.onDoc("about/LICENSE", resources.getString(R.string.about_licence)) }
            RowDivider()
            NavRow(stringResource(R.string.about_source), REPO_URL.removePrefix("https://"), opensPage = false) {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL))) }
            }
            RowDivider()
            NavRow(stringResource(R.string.about_credits), stringResource(R.string.about_credits_text)) { actions.onDoc("about/THIRD_PARTY_NOTICES.md", resources.getString(R.string.about_credits)) }
            RowDivider()
            NavRow(stringResource(R.string.about_privacy), stringResource(R.string.about_privacy_text)) { actions.onDoc("about/PRIVACY.md", resources.getString(R.string.about_privacy)) }
        }
        SettingsGroup(stringResource(R.string.group_help)) {
            SwitchRow(
                stringResource(R.string.include_fields),
                pluralStringResource(R.plurals.include_fields_text, dev.shebang.devboard.ime.RecentFields.MAX, dev.shebang.devboard.ime.RecentFields.MAX),
                includeFields,
            ) { includeFields = it }
            RowDivider()
            NavRow(
                stringResource(R.string.export_diagnostics),
                stringResource(R.string.export_diagnostics_text),
                opensPage = false,
            ) { diagnosticsLauncher.launch("devboard-diagnostics.json") }
        }
    }
}
