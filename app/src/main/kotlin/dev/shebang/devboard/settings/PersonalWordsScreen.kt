package dev.shebang.devboard.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
    val scope = rememberCoroutineScope()
    var words by remember { mutableStateOf<List<PersonalWord>?>(null) }
    var glides by remember { mutableIntStateOf(0) }
    var corrections by remember { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            val (w, g, c) = withContext(Dispatchers.IO) {
                personal.load()
                adaptation.load()
                Triple(personal.list(), adaptation.glides, adaptation.corrections)
            }
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
            }
            refresh()
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Personal words") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
        )
    }) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item {
                Text(
                    "Learned on this phone from what you type and glide, and never sent anywhere. Nothing is learned in " +
                        "password, number, terminal or no-suggestion fields, or where an app asks for no learning. A new " +
                        "word is glidable after you use it twice.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Reset glide adaptation") },
                    supportingContent = {
                        Text("Learned from $glides glides and $corrections corrections. Resetting forgets how your swipes lean off each key.")
                    },
                    modifier = Modifier.clickable { confirmReset = true },
                )
            }
            item {
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
            item { HorizontalDivider() }
            val list = words
            when {
                list == null -> item { Text("Loading…", modifier = Modifier.padding(16.dp)) }
                list.isEmpty() -> item { Text("No learned words yet.", modifier = Modifier.padding(16.dp)) }
                else -> {
                    item {
                        ListItem(
                            headlineContent = { Text("Delete all learned words") },
                            supportingContent = { Text("${list.size} words") },
                            modifier = Modifier.clickable { confirmClear = true },
                        )
                    }
                    items(list, key = { it.lower }) { w ->
                        ListItem(
                            headlineContent = { Text(w.display) },
                            supportingContent = {
                                Text(if (w.known) "Used ${w.count} times" else "Used ${w.count} times · new word, glidable after 2")
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
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset glide adaptation?") },
            text = { Text("Gliding goes back to the keyboard's defaults and starts learning your swipes again.") },
            confirmButton = { TextButton(onClick = { confirmReset = false; change { adaptation.reset() } }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}
