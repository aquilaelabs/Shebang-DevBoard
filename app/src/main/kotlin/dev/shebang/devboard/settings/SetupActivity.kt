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
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
    val done = listOf(status.enabled, status.selected, testText.isNotEmpty()).count { it }

    // No app bar: the mark and name head the page, under the status bar.
    Scaffold { padding ->
        Column(
            // Padded for the keyboard: the page scrolls the test field into view instead of the window
            // panning up under the status bar.
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().verticalScroll(rememberScrollState())
                .padding(horizontal = PageMargin, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(GroupGap),
        ) {
            AppMark(72.dp)
            Spacer(Modifier.height(4.dp))
            Text("Shebang DevBoard", style = MaterialTheme.typography.headlineMedium)
            Text(
                "A keyboard for developers: glide typing, a code mode with every symbol, and a terminal bar. " +
                    "Everything stays on this phone.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(
                    progress = { done / 3f },
                    modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)),
                    trackColor = MaterialTheme.colorScheme.surfaceContainer,
                    drawStopIndicator = {},
                )
                Spacer(Modifier.width(12.dp))
                Text(if (done == 3) "All set" else "$done of 3 done", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(4.dp))
            StepCard(
                number = 1, title = "Turn it on", done = status.enabled,
                description = "Switch on Shebang DevBoard in the system's keyboard list. Android warns that keyboards can see what you type: this one has no network access, so nothing leaves the phone.",
                buttonLabel = "Open keyboard list",
            ) { context.startActivity(Intent(SysSettings.ACTION_INPUT_METHOD_SETTINGS)) }
            StepCard(
                number = 2, title = "Make it your keyboard", done = status.selected,
                description = "Pick Shebang DevBoard as the keyboard to use.",
                buttonLabel = "Choose keyboard",
                enabled = status.enabled,
            ) { (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker() }
            StepSurface {
                StepHeader(3, "Try it", done = testText.isNotEmpty())
                Text(
                    "Type here, glide across the letters to write a word, and tap #! for every symbol.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = testText, onValueChange = { testText = it },
                    modifier = Modifier.fillMaxWidth(), placeholder = { Text("Test field") }, minLines = 3,
                    shape = RoundedCornerShape(6.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            val ready = status.enabled && status.selected
            if (ready) {
                Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(6.dp)) { Text("Open settings") }
            } else {
                OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(6.dp)) { Text("Open settings") }
            }
        }
    }
}

/** A step's card: the theme's key colour, 8 dp corners, the design's padding. */
@Composable
private fun StepSurface(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainer, contentColor = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

@Composable
private fun StepHeader(number: Int, title: String, done: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val bg = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        val fg = if (done) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
        Box(
            modifier = Modifier.size(32.dp).background(bg, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Icon(Icons.Default.Check, contentDescription = "Done", tint = fg, modifier = Modifier.size(20.dp))
            else Text("$number", color = fg, style = MaterialTheme.typography.titleSmall)
        }
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (done) Text("Done", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun StepCard(
    number: Int, title: String, done: Boolean, description: String, buttonLabel: String,
    enabled: Boolean = true, onClick: () -> Unit,
) {
    StepSurface {
        StepHeader(number, title, done)
        // Once done, the step shrinks to its header.
        if (!done) {
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(6.dp)) { Text(buttonLabel) }
        }
    }
}
