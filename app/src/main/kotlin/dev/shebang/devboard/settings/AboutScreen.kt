package dev.shebang.devboard.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

/**
 * Shows one of the documents shipped in the app's assets (the licence, the credits) as readable text:
 * headings, bullets, the library table as lines, and links that open in the browser. The files are copied
 * from the project at build time, so this always shows the real list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutDocScreen(title: String, asset: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val blocks = remember(asset) {
        val text = runCatching { context.assets.open(asset).bufferedReader().use { it.readText() } }.getOrDefault("($asset is missing)")
        Markdownish.blocks(text)
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(title) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
        )
    }) { padding ->
        val link = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            items(blocks) { b ->
                when (b.kind) {
                    Markdownish.Kind.SPACE -> Spacer(Modifier.height(8.dp))
                    Markdownish.Kind.H1 -> Text(b.text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    Markdownish.Kind.H2 -> Text(b.text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
                    Markdownish.Kind.H3 -> Text(b.text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                    else -> Text(linked(b.text, link), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp))
                }
            }
        }
    }
}

/** The text with each URL in it a link. */
private fun linked(text: String, style: TextLinkStyles): AnnotatedString = buildAnnotatedString {
    var at = 0
    for (m in Markdownish.URL.findAll(text)) {
        append(text.substring(at, m.range.first))
        val url = m.value.trimEnd('.', ',', ';', ')')
        pushLink(LinkAnnotation.Url(url, style))
        append(url)
        pop()
        at = m.range.first + url.length
    }
    append(text.substring(at))
}

/** Just enough Markdown for the licence and the notices: headings, paragraphs, bullets, simple tables. */
object Markdownish {
    enum class Kind { H1, H2, H3, PARAGRAPH, BULLET, ROW, SPACE }

    class Block(val kind: Kind, val text: String)

    val URL = Regex("https?://[^\\s<>|)]+")

    fun blocks(md: String): List<Block> {
        val out = ArrayList<Block>()
        var current: StringBuilder? = null
        var currentKind = Kind.PARAGRAPH
        fun close() {
            current?.let { out += Block(currentKind, clean(it.toString())) }
            current = null
        }
        for (raw in md.lines()) {
            val line = raw.trimEnd()
            when {
                line.isBlank() -> {
                    close()
                    if (out.lastOrNull()?.kind != Kind.SPACE) out += Block(Kind.SPACE, "")
                }
                line.startsWith("### ") -> { close(); out += Block(Kind.H3, clean(line.drop(4))) }
                line.startsWith("## ") -> { close(); out += Block(Kind.H2, clean(line.drop(3))) }
                line.startsWith("# ") -> { close(); out += Block(Kind.H1, clean(line.drop(2))) }
                line.startsWith("|") -> {
                    close()
                    val cells = line.trim('|').split('|').map { it.trim() }
                    if (cells.all { c -> c.isNotEmpty() && c.all { it == '-' || it == ':' } }) continue
                    out += Block(Kind.ROW, clean(cells.filter { it.isNotEmpty() }.joinToString(" · ")))
                }
                line.startsWith("- ") || line.startsWith("* ") -> {
                    close()
                    current = StringBuilder("• ").append(line.drop(2))
                    currentKind = Kind.BULLET
                }
                line.startsWith("  ") && current != null -> current!!.append(' ').append(line.trim())
                else -> {
                    if (current == null) {
                        current = StringBuilder(line.trim())
                        currentKind = Kind.PARAGRAPH
                    } else {
                        current!!.append(' ').append(line.trim())
                    }
                }
            }
        }
        close()
        return out
    }

    /** Drops Markdown's marks: backticks, bold stars, angle brackets around links. */
    private fun clean(s: String): String = s.replace("`", "").replace("**", "").replace(Regex("<(https?://[^>]+)>"), "$1")
}
