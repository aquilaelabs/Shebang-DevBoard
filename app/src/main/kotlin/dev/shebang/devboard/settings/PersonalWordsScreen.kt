package dev.shebang.devboard.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import dev.shebang.devboard.ime.EmailMemory
import dev.shebang.devboard.dict.PersonalWord
import dev.shebang.devboard.dict.PersonalWords
import dev.shebang.devboard.dict.RemovedWords
import dev.shebang.devboard.glide.GlideAdaptation
import dev.shebang.devboard.glide.SpaceHabit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.shebang.devboard.R

/**
 * What the keyboard has learned on this phone: the words it learned from typing and gliding (each can be
 * deleted, or all at once), and how its glide model adapted to the user's swiping (resettable). Nothing here
 * ever leaves the phone. The keyboard is in the same process, so it sees deletions at once and rebuilds its
 * vocabulary the next time it opens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonalWordsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val personal = remember { PersonalWords.get(context.filesDir) }
    val adaptation = remember { GlideAdaptation.get(context.filesDir) }
    val taps = remember { GlideAdaptation.getTaps(context.filesDir) }
    val emailMemory = remember { EmailMemory.get(context.filesDir) }
    var emails by remember { mutableStateOf<List<EmailMemory.Address>>(emptyList()) }
    var confirmClearEmails by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var words by remember { mutableStateOf<List<PersonalWord>?>(null) }
    var glides by remember { mutableIntStateOf(0) }
    var corrections by remember { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var restoreDays by remember { mutableStateOf<List<Int>>(emptyList()) }
    var choosingDay by remember { mutableStateOf(false) }
    var confirmDay by remember { mutableStateOf<Int?>(null) }
    var query by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            val (w, g, c) = withContext(Dispatchers.IO) {
                personal.load()
                adaptation.load()
                Triple(personal.list(), adaptation.glides, adaptation.corrections)
            }
            emails = withContext(Dispatchers.IO) { emailMemory.list() }
            restoreDays = withContext(Dispatchers.IO) { (personal.restoreDays() + adaptation.restoreDays() + taps.restoreDays()).distinct().sortedDescending() }
            words = w
            glides = g
            corrections = c
        }
    }

    fun change(action: () -> Unit) {
        scope.launch {
            withContext(Dispatchers.IO) {
                action()
                personal.save()
                adaptation.save()
                taps.save()
            }
            refresh()
        }
    }

    LaunchedEffect(Unit) { refresh() }

    val searching = query.isNotBlank()
    Scaffold(topBar = {
        Column {
            TopAppBar(
                title = { Text(stringResource(R.string.personal_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
                actions = { IconButton(onClick = { adding = true }) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.personal_add_word)) } },
            )
            // Under the title, so the matches show below it while the keyboard is up.
            if (!words.isNullOrEmpty()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.personal_search)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.action_clear_search)) }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = PageMargin, end = PageMargin, top = 4.dp, bottom = 24.dp),
        ) {
            // While searching, only the matching words.
            if (!searching) {
                item {
                    PageNote(
                        stringResource(R.string.personal_note),
                    )
                }
                item {
                    SettingsGroup(stringResource(R.string.personal_words_group)) {
                        NavRow(stringResource(R.string.personal_add_word), stringResource(R.string.personal_add_word_text), opensPage = false) { adding = true }
                        RowDivider()
                        NavRow(stringResource(R.string.personal_android_dictionary), stringResource(R.string.personal_android_dictionary_text), opensPage = false) {
                            try {
                                context.startActivity(Intent(android.provider.Settings.ACTION_USER_DICTIONARY_SETTINGS))
                            } catch (_: ActivityNotFoundException) {
                                // Some phones do not ship the screen; the words are still read when present.
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(GroupGap)) }
                item {
                    val today = (System.currentTimeMillis() / 86_400_000L).toInt()
                    SettingsGroup(stringResource(R.string.personal_adaptation_group)) {
                        NavRow(
                            stringResource(R.string.personal_reset_adaptation),
                            stringResource(
                                R.string.personal_reset_adaptation_text,
                                pluralStringResource(R.plurals.personal_glides, glides, glides),
                                pluralStringResource(R.plurals.personal_corrections, corrections, corrections),
                            ),
                            opensPage = false,
                        ) { confirmReset = true }
                        RowDivider()
                        NavRow(
                            stringResource(R.string.personal_undo),
                            if (restoreDays.isEmpty()) pluralStringResource(R.plurals.personal_undo_nothing, PersonalWords.KEEP_DAYS, PersonalWords.KEEP_DAYS)
                            else stringResource(R.string.personal_undo_text),
                            opensPage = false,
                            enabled = restoreDays.isNotEmpty(),
                        ) { choosingDay = true }
                    }
                    if (choosingDay) {
                        AlertDialog(
                            onDismissRequest = { choosingDay = false },
                            title = { Text(stringResource(R.string.personal_go_back_to_start_of)) },
                            text = {
                                Column {
                                    for (d in restoreDays) {
                                        ListItem(
                                            headlineContent = { Text(dayLabel(resources, d, today)) },
                                            modifier = Modifier.clickable {
                                                choosingDay = false
                                                confirmDay = d
                                            },
                                        )
                                    }
                                }
                            },
                            confirmButton = {},
                            dismissButton = { TextButton(onClick = { choosingDay = false }) { Text(stringResource(R.string.action_cancel)) } },
                        )
                    }
                }
            }
            if (!searching && emails.isNotEmpty()) {
                item { Spacer(Modifier.height(GroupGap)) }
                item { GroupTitle(stringResource(R.string.personal_emails_group)) }
                val rows = emails.size + 1
                item {
                    CardRow(0, rows) {
                        CardListItem(
                            stringResource(R.string.personal_forget_emails),
                            stringResource(R.string.personal_forget_emails_text),
                            modifier = Modifier.clickable { confirmClearEmails = true },
                        )
                    }
                }
                itemsIndexed(emails, key = { _, e -> "email:" + e.address.lowercase() }) { i, e ->
                    CardRow(i + 1, rows) {
                        CardListItem(
                            e.address,
                            pluralStringResource(R.plurals.personal_email_entered, e.count, e.count),
                            trailing = {
                                IconButton(onClick = {
                                    scope.launch {
                                        emails = withContext(Dispatchers.IO) {
                                            emailMemory.delete(e.address)
                                            emailMemory.list()
                                        }
                                    }
                                }) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.personal_forget_email, e.address)) }
                            },
                        )
                    }
                }
            }
            if (!searching) item { Spacer(Modifier.height(GroupGap)) }
            val list = words
            when {
                list == null -> item { PageNote(stringResource(R.string.loading)) }
                list.isEmpty() -> {
                    item { GroupTitle(stringResource(R.string.personal_learned_group)) }
                    item { CardRow(0, 1) { CardListItem(stringResource(R.string.personal_none_learned), stringResource(R.string.personal_none_learned_text)) } }
                }
                else -> {
                    val q = query.trim().lowercase()
                    val shown = if (q.isEmpty()) list else list.filter { q in it.lower || q in it.display.lowercase() }
                    item { GroupTitle(if (searching) stringResource(R.string.personal_matching) else stringResource(R.string.personal_learned_count, list.size)) }
                    val head = if (searching) 0 else 1
                    if (!searching) item {
                        CardRow(0, shown.size + head) {
                            CardListItem(stringResource(R.string.personal_delete_all), pluralStringResource(R.plurals.personal_words_count, list.size, list.size), modifier = Modifier.clickable { confirmClear = true })
                        }
                    }
                    if (shown.isEmpty()) item { CardRow(0, 1) { CardListItem(stringResource(R.string.personal_none_match, query.trim())) } }
                    itemsIndexed(shown, key = { _, w -> w.lower }) { i, w ->
                        CardRow(i + head, shown.size + head) {
                            val used = pluralStringResource(R.plurals.personal_used, w.count, w.count)
                            CardListItem(
                                w.display,
                                if (w.known) used else stringResource(R.string.personal_new_word, used),
                                trailing = {
                                    IconButton(onClick = { change { personal.delete(w.lower) } }) {
                                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.personal_delete_word, w.display))
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    if (adding) {
        AddWordDialog(
            onDismiss = { adding = false },
            onAdd = { word ->
                adding = false
                change {
                    personal.add(word)
                    // A word deleted from the built-in dictionary comes back when added by hand.
                    val removed = RemovedWords.get(context.filesDir)
                    val lower = word.trim().lowercase()
                    removed.snapshot().filter { it.lowercase() == lower }.forEach { removed.restore(it) }
                }
            },
        )
    }
    if (confirmClearEmails) {
        AlertDialog(
            onDismissRequest = { confirmClearEmails = false },
            title = { Text(stringResource(R.string.personal_forget_emails_title)) },
            text = { Text(stringResource(R.string.personal_forget_emails_dialog)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearEmails = false
                    scope.launch {
                        emails = withContext(Dispatchers.IO) {
                            emailMemory.clear()
                            emailMemory.list()
                        }
                    }
                }) { Text(stringResource(R.string.personal_forget_all)) }
            },
            dismissButton = { TextButton(onClick = { confirmClearEmails = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.personal_delete_all_title)) },
            text = { Text(stringResource(R.string.personal_delete_all_text)) },
            confirmButton = { TextButton(onClick = { confirmClear = false; change { personal.clear() } }) { Text(stringResource(R.string.personal_delete_all_button)) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    confirmDay?.let { day ->
        val today = (System.currentTimeMillis() / 86_400_000L).toInt()
        AlertDialog(
            onDismissRequest = { confirmDay = null },
            title = { Text(stringResource(R.string.personal_go_back_title, dayLabel(resources, day, today).replaceFirstChar { it.lowercase() })) },
            text = { Text(stringResource(R.string.personal_go_back_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDay = null
                    change {
                        personal.restore(day)
                        adaptation.restore(day)
                        taps.restore(day)
                    }
                }) { Text(stringResource(R.string.personal_go_back)) }
            },
            dismissButton = { TextButton(onClick = { confirmDay = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.personal_reset_title)) },
            text = { Text(stringResource(R.string.personal_reset_text)) },
            confirmButton = { TextButton(onClick = { confirmReset = false; change { adaptation.reset(); taps.reset(); SpaceHabit.get(context.filesDir).reset(); dev.shebang.devboard.glide.GlideOutcomes.get(context.filesDir).reset() } }) { Text(stringResource(R.string.personal_reset)) } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** A word typed by hand, checked as the keyboard checks what it learns. */
@Composable
private fun AddWordDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var word by remember { mutableStateOf("") }
    val w = word.trim()
    val ok = PersonalWords.isLearnable(w)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.personal_add_word)) },
        text = {
            OutlinedTextField(
                value = word,
                onValueChange = { word = it },
                label = { Text(stringResource(R.string.personal_word_label)) },
                singleLine = true,
                isError = w.isNotEmpty() && !ok,
                supportingText = {
                    Text(
                        if (w.isNotEmpty() && !ok) stringResource(R.string.personal_word_invalid)
                        else stringResource(R.string.personal_word_hint),
                    )
                },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    autoCorrectEnabled = false,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { if (ok) onAdd(w) }),
            )
        },
        confirmButton = { TextButton(onClick = { onAdd(w) }, enabled = ok) { Text(stringResource(R.string.personal_add)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** "Today", "Yesterday", or the weekday and date of a day (days since 1970, UTC). */
private fun dayLabel(res: android.content.res.Resources, day: Int, today: Int): String = when (day) {
    today -> res.getString(R.string.day_today)
    today - 1 -> res.getString(R.string.day_yesterday)
    else -> java.text.SimpleDateFormat("EEEE d MMMM", java.util.Locale.getDefault())
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        .format(java.util.Date(day * 86_400_000L))
}
