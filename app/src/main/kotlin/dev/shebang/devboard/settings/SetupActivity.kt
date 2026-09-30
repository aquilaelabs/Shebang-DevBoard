package dev.shebang.devboard.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings as SysSettings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Launcher screen: three setup steps with their done state, and a way into the settings. */
class SetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = SettingsRepository.get(this)
        setContent {
            val settings by repo.settings.collectAsStateWithLifecycle(initialValue = Settings())
            DevBoardTheme(settings.palette) {
                SetupScreen(onOpenSettings = { startActivity(Intent(this, SettingsActivity::class.java)) })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    var status by remember { mutableStateOf(ImeStatus.check(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) status = ImeStatus.check(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // The system sends no broadcast when the keyboard is enabled or picked, so poll while visible.
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            status = ImeStatus.check(context)
        }
    }
    var testText by remember { mutableStateOf("") }

    Scaffold(topBar = { TopAppBar(title = { Text("Shebang DevBoard") }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("A keyboard for developers: text mode with glide typing, a code mode with every symbol, and a terminal bar.", style = MaterialTheme.typography.bodyMedium)
            StepCard(
                number = 1, title = "Enable the keyboard", done = status.enabled,
                description = "Turn on Shebang DevBoard in the system's keyboard list.",
                buttonLabel = "Open keyboard settings",
            ) { context.startActivity(Intent(SysSettings.ACTION_INPUT_METHOD_SETTINGS)) }
            StepCard(
                number = 2, title = "Select it", done = status.selected,
                description = "Pick Shebang DevBoard as the current keyboard.",
                buttonLabel = "Choose keyboard",
                enabled = status.enabled,
            ) { (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker() }
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StepHeader(3, "Try it", done = testText.isNotEmpty())
                    Text("Tap the field and type. Tap #! for code mode; glide across letters to write a word.", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = testText, onValueChange = { testText = it },
                        modifier = Modifier.fillMaxWidth(), label = { Text("Test field") }, minLines = 2,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) { Text("Open DevBoard settings") }
        }
    }
}

@Composable
private fun StepHeader(number: Int, title: String, done: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val bg = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
        val fg = if (done) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
        Box(
            modifier = Modifier.size(32.dp).background(bg, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Icon(Icons.Default.Check, contentDescription = "Done", tint = fg)
            else Text("$number", color = fg, style = MaterialTheme.typography.labelLarge)
        }
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (done) Text("Done", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun StepCard(
    number: Int, title: String, done: Boolean, description: String, buttonLabel: String,
    enabled: Boolean = true, onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StepHeader(number, title, done)
            Text(description, style = MaterialTheme.typography.bodyMedium)
            if (!done) Button(onClick = onClick, enabled = enabled) { Text(buttonLabel) }
        }
    }
}
