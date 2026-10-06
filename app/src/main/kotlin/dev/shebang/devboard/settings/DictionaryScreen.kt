package dev.shebang.devboard.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import dev.shebang.devboard.dict.WordPacks
import dev.shebang.devboard.ime.LanguageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.shebang.devboard.R

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
    // The pack each word comes from, for the label under words that are not regular ones.
    var packOf by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var removed by remember { mutableStateOf<Set<String>>(emptySet()) }
    var query by remember { mutableStateOf("") }
    var showRemoved by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val (words, gone) = withContext(Dispatchers.IO) {
            val parts = listOf(context.assets.open(LanguageLoader.DICTIONARY_ASSET).bufferedReader(Charsets.UTF_8).useLines { Dictionary.parse(it) }) +
                WordPacks.builtIn.mapNotNull { p -> runCatching { context.assets.open(p.asset).bufferedReader(Charsets.UTF_8).useLines { Dictionary.parse(it, p.id) } }.getOrNull() }
            val d = Dictionary.merge(parts)
            val titles = WordPacks.builtIn.associate { it.id to it.title }
            packOf = (0 until d.size).filter { d.packs[it].toInt() != WordPacks.REGULAR }.associate { d.words[it] to (titles[d.packs[it].toInt()] ?: "") }
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
                title = { Text(stringResource(R.string.dictionary_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
                actions = { TextButton(onClick = { showRemoved = true }) { Text(stringResource(R.string.dictionary_removed_button, removed.size)) } },
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.dictionary_search)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.action_clear_search)) }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
    }) { padding ->
        val words = all
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = PageMargin, end = PageMargin, top = 4.dp, bottom = 24.dp),
        ) {
            if (words == null) {
                item { PageNote(stringResource(R.string.loading)) }
                return@LazyColumn
            }
            val q = query.trim().lowercase()
            val shown = words.filter { it !in removed && (q.isEmpty() || it.lowercase().startsWith(q)) }
            if (q.isEmpty()) item {
                PageNote(
                    stringResource(R.string.dictionary_note, "%,d".format(words.size - removed.size)),
                )
            }
            item { GroupTitle(if (q.isEmpty()) stringResource(R.string.dictionary_words) else stringResource(R.string.dictionary_starting_with, query.trim())) }
            if (shown.isEmpty()) item { CardRow(0, 1) { CardListItem(stringResource(R.string.dictionary_none_start_with, query.trim())) } }
            itemsIndexed(shown, key = { _, w -> w }) { i, w ->
                CardRow(i, shown.size) {
                    CardListItem(w, packOf[w], trailing = {
                        IconButton(onClick = { change { store.remove(w) } }) { Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.dictionary_delete_word, w)) }
                    })
                }
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
            title = { Text(stringResource(R.string.dictionary_removed_title)) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back)) } },
            actions = { if (removed.isNotEmpty()) TextButton(onClick = { confirmAll = true }) { Text(stringResource(R.string.dictionary_restore_all)) } },
        )
    }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = PageMargin, end = PageMargin, top = 4.dp, bottom = 24.dp),
        ) {
            if (removed.isEmpty()) item { PageNote(stringResource(R.string.dictionary_none_removed)) }
            else item { PageNote(stringResource(R.string.dictionary_removed_note)) }
            if (removed.isNotEmpty()) item { GroupTitle(stringResource(R.string.dictionary_removed_button, removed.size)) }
            itemsIndexed(removed, key = { _, w -> w }) { i, w ->
                CardRow(i, removed.size) {
                    CardListItem(w, trailing = { TextButton(onClick = { onRestore(w) }) { Text(stringResource(R.string.action_restore)) } })
                }
            }
        }
    }
    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text(pluralStringResource(R.plurals.dictionary_restore_all_title, removed.size, removed.size)) },
            text = { Text(stringResource(R.string.dictionary_restore_all_text)) },
            confirmButton = { TextButton(onClick = { confirmAll = false; onRestoreAll() }) { Text(stringResource(R.string.dictionary_restore_all)) } },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
