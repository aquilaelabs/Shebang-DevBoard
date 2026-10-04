package dev.shebang.devboard.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.shebang.devboard.dict.Dictionary
import dev.shebang.devboard.dict.RemovedWords
import dev.shebang.devboard.ime.LanguageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The keyboard's built-in dictionary: every word it ships with, searchable, each with a delete button.
 * Deleted words go to [RemovedWordsList], where one or all can be restored. Android's personal dictionary and
 * the learned words have screens of their own; this one is only the bundled list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionaryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { RemovedWords.get(context.filesDir) }
    val scope = rememberCoroutineScope()
    var all by remember { mutableStateOf<List<String>?>(null) }
    var removed by remember { mutableStateOf<Set<String>>(emptySet()) }
    var query by remember { mutableStateOf("") }
    var showRemoved by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val (words, gone) = withContext(Dispatchers.IO) {
            val d = context.assets.open(LanguageLoader.DICTIONARY_ASSET).bufferedReader(Charsets.UTF_8).useLines { Dictionary.parse(it) }
            d.words.toList() to store.snapshot()
        }
        all = words
        removed = gone
    }

    fun change(action: () -> Unit) {
        scope.launch {
            removed = withContext(Dispatchers.IO) {
                action()
                store.snapshot()
            }
        }
    }

    if (showRemoved) {
        RemovedWordsList(
            removed = removed.sortedWith(compareBy({ it.lowercase() }, { it })),
            onRestore = { w -> change { store.restore(w) } },
            onRestoreAll = { change { store.restoreAll() } },
            onBack = { showRemoved = false },
        )
        androidx.activity.compose.BackHandler { showRemoved = false }
        return
    }

    Scaffold(topBar = {
        Column {
            TopAppBar(
                title = { Text("Built-in dictionary") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
                actions = { TextButton(onClick = { showRemoved = true }) { Text("Removed (${removed.size})") } },
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search the dictionary") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, contentDescription = "Clear search") }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }) { padding ->
        val words = all
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (words == null) {
                item { Text("Loading…", modifier = Modifier.padding(16.dp)) }
                return@LazyColumn
            }
            val q = query.trim().lowercase()
            val shown = words.filter { it !in removed && (q.isEmpty() || it.lowercase().startsWith(q)) }
            if (q.isEmpty()) item {
                Text(
                    "The ${"%,d".format(words.size - removed.size)} words this keyboard ships with. A word you delete is no longer offered, " +
                        "glided or used as a correction; restore it from Removed. Words you added to Android's personal " +
                        "dictionary and words the keyboard learned are not listed here.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (shown.isEmpty()) item { Text("No words start with \"${query.trim()}\".", modifier = Modifier.padding(16.dp)) }
            items(shown, key = { it }) { w ->
                ListItem(
                    headlineContent = { Text(w) },
                    trailingContent = {
                        IconButton(onClick = { change { store.remove(w) } }) { Icon(Icons.Filled.Delete, contentDescription = "Delete $w") }
                    },
                )
            }
        }
    }
}

/** Words removed from the built-in dictionary, each with Restore, and Restore all. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemovedWordsList(removed: List<String>, onRestore: (String) -> Unit, onRestoreAll: () -> Unit, onBack: () -> Unit) {
    var confirmAll by remember { mutableStateOf(false) }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Removed words") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
            actions = { if (removed.isNotEmpty()) TextButton(onClick = { confirmAll = true }) { Text("Restore all") } },
        )
    }) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (removed.isEmpty()) item { Text("No words removed. The built-in dictionary is as it shipped.", modifier = Modifier.padding(16.dp)) }
            else item {
                Text(
                    "Words you deleted from the built-in dictionary. Restore one to have it offered and glided again.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                HorizontalDivider()
            }
            items(removed, key = { it }) { w ->
                ListItem(
                    headlineContent = { Text(w) },
                    trailingContent = { TextButton(onClick = { onRestore(w) }) { Text("Restore") } },
                )
            }
        }
    }
    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text(if (removed.size == 1) "Restore the removed word?" else "Restore all ${removed.size} removed words?") },
            text = { Text("The built-in dictionary goes back to how it shipped.") },
            confirmButton = { TextButton(onClick = { confirmAll = false; onRestoreAll() }) { Text("Restore all") } },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Cancel") } },
        )
    }
}
