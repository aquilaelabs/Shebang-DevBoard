package dev.shebang.devboard.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.unit.dp
import dev.shebang.devboard.dict.PersonalWord
import dev.shebang.devboard.dict.PersonalWords
import dev.shebang.devboard.glide.GlideAdaptation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val personal = remember { PersonalWords.get(context.filesDir) }
    val adaptation = remember { GlideAdaptation.get(context.filesDir) }
    val taps = remember { GlideAdaptation.getTaps(context.filesDir) }
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

    fun refresh() {
        scope.launch {
            val (w, g, c) = withContext(Dispatchers.IO) {
                personal.load()
                adaptation.load()
                Triple(personal.list(), adaptation.glides, adaptation.corrections)
            }
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
                title = { Text("Personal words") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            )
            // Under the title, so the matches show below it while the keyboard is up.
            if (!words.isNullOrEmpty()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search learned words") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, contentDescription = "Clear search") }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }
    }) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            // While searching, only the matching words.
            if (!searching) item {
                Text(
                    "Learned on this phone from what you type and glide, and never sent anywhere. Nothing is learned in " +
                        "password, number, email, web address, terminal or no-suggestion fields, or where an app asks for " +
                        "no learning. A new word is glidable after you use it twice.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (!searching) item {
                ListItem(
                    headlineContent = { Text("Reset glide and tap adaptation") },
                    supportingContent = {
                        Text("Learned from ${plural(glides, "glide")} and ${plural(corrections, "correction")}. Resetting forgets how your swipes lean off each key.")
                    },
                    modifier = Modifier.clickable { confirmReset = true },
                )
            }
            if (!searching) item {
                val today = (System.currentTimeMillis() / 86_400_000L).toInt()
                ListItem(
                    headlineContent = { Text("Undo recent learning") },
                    supportingContent = {
                        Text(
                            if (restoreDays.isEmpty()) "Nothing to undo yet. The keyboard keeps where things stood at the start of each of the last ${PersonalWords.KEEP_DAYS} days."
                            else "Go back to how learned words and swipe and tap adaptation stood at the start of a recent day, if a sloppy day taught the keyboard the wrong things."
                        )
                    },
                    modifier = Modifier.clickable(enabled = restoreDays.isNotEmpty()) { choosingDay = true },
                )
                if (choosingDay) {
                    AlertDialog(
                        onDismissRequest = { choosingDay = false },
                        title = { Text("Go back to the start of") },
                        text = {
                            Column {
                                for (d in restoreDays) {
                                    ListItem(
                                        headlineContent = { Text(dayLabel(d, today)) },
                                        modifier = Modifier.clickable {
                                            choosingDay = false
                                            confirmDay = d
                                        },
                                    )
                                }
                            }
                        },
                        confirmButton = {},
                        dismissButton = { TextButton(onClick = { choosingDay = false }) { Text("Cancel") } },
                    )
                }
            }
            if (!searching) item {
                ListItem(
                    headlineContent = { Text("Android personal dictionary") },
                    supportingContent = { Text("Words added there are also glidable. Tap to open it.") },
                    modifier = Modifier.clickable {
                        try {
                            context.startActivity(Intent(android.provider.Settings.ACTION_USER_DICTIONARY_SETTINGS))
                        } catch (_: ActivityNotFoundException) {
                            // Some phones do not ship the screen; the words are still read when present.
                        }
                    },
                )
            }
            if (!searching) item { HorizontalDivider() }
            val list = words
            when {
                list == null -> item { Text("Loading…", modifier = Modifier.padding(16.dp)) }
                list.isEmpty() -> item { Text("No learned words yet.", modifier = Modifier.padding(16.dp)) }
                else -> {
                    if (!searching) item {
                        ListItem(
                            headlineContent = { Text("Delete all learned words") },
                            supportingContent = { Text(plural(list.size, "word")) },
                            modifier = Modifier.clickable { confirmClear = true },
                        )
                    }
                    val q = query.trim().lowercase()
                    val shown = if (q.isEmpty()) list else list.filter { q in it.lower || q in it.display.lowercase() }
                    if (shown.isEmpty()) item { Text("No learned words match \"${query.trim()}\".", modifier = Modifier.padding(16.dp)) }
                    items(shown, key = { it.lower }) { w ->
                        ListItem(
                            headlineContent = { Text(w.display) },
                            supportingContent = {
                                val used = if (w.count == 1) "Used once" else "Used ${w.count} times"
                                Text(if (w.known) used else "$used · new word, glidable after 2 uses")
                            },
                            trailingContent = {
                                IconButton(onClick = { change { personal.delete(w.lower) } }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete ${w.display}")
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Delete all learned words?") },
            text = { Text("The keyboard forgets every word and word pair it learned. The Android personal dictionary is not touched.") },
            confirmButton = { TextButton(onClick = { confirmClear = false; change { personal.clear() } }) { Text("Delete all") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
    confirmDay?.let { day ->
        val today = (System.currentTimeMillis() / 86_400_000L).toInt()
        AlertDialog(
            onDismissRequest = { confirmDay = null },
            title = { Text("Go back to ${dayLabel(day, today).replaceFirstChar { it.lowercase() }}?") },
            text = { Text("Words, swipe and tap habits learned since then are forgotten. Words you deleted stay deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDay = null
                    change {
                        personal.restore(day)
                        adaptation.restore(day)
                        taps.restore(day)
                    }
                }) { Text("Go back") }
            },
            dismissButton = { TextButton(onClick = { confirmDay = null }) { Text("Cancel") } },
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset glide and tap adaptation?") },
            text = { Text("Gliding and autocorrect go back to the keyboard's defaults and start learning your swipes and taps again.") },
            confirmButton = { TextButton(onClick = { confirmReset = false; change { adaptation.reset(); taps.reset() } }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

private fun plural(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

/** "Today", "Yesterday", or the weekday and date of a day (days since 1970, UTC). */
private fun dayLabel(day: Int, today: Int): String = when (day) {
    today -> "Today"
    today - 1 -> "Yesterday"
    else -> java.text.SimpleDateFormat("EEEE d MMMM", java.util.Locale.getDefault())
        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        .format(java.util.Date(day * 86_400_000L))
}
