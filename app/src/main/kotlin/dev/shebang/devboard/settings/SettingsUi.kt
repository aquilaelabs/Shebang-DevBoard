package dev.shebang.devboard.settings

import android.graphics.Path
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.PathParser
import dev.shebang.devboard.R
import dev.shebang.devboard.view.IconDrawable

/*
 * The settings app's building blocks, in the design language's terms: rows grouped on cards of the theme's key
 * colour (8 dp corners, as boxes are), page margins of 14 dp and 10 dp between groups, single-colour glyphs of
 * the keyboard's own drawing for the categories, and segmented buttons for small choices.
 */

/** Space around and between the groups on a page. */
internal val PageMargin = 14.dp
internal val GroupGap = 10.dp
private val BoxShape = RoundedCornerShape(8.dp)

/** Glyphs for the settings categories, original to this project, in a 24x24 box like [dev.shebang.devboard.view.KeyIcons]. */
internal object SettingsIcons {
    private const val KEYBOARD = "M2 6 H22 V18.5 H2 Z M4 8 V16.5 H20 V8 Z M5.5 9.5 H7.5 V11.5 H5.5 Z M9 9.5 H11 V11.5 H9 Z M12.5 9.5 H14.5 V11.5 H12.5 Z M16 9.5 H18 V11.5 H16 Z M7.5 13 H16.5 V15 H7.5 Z"
    private const val GLIDE = "M3.4 17.2 A2.2 2.2 0 1 1 7.8 17.2 A2.2 2.2 0 1 1 3.4 17.2 Z M4.6 15.4 C6 9 8.4 6.2 10.6 6.2 C13.2 6.2 13.6 10.4 14.6 13.2 C15.4 15.4 16.4 15.6 17.6 13.2 L19 9.8 L20.8 10.6 L19.4 14 C17.6 18.2 14.6 18.6 12.8 14 C11.8 11.4 11.6 8.2 10.6 8.2 C9.2 8.2 7.6 10.4 6.5 15.8 Z"
    private const val CORRECT = "M3 5 H15 V7 H3 Z M3 9.5 H11 V11.5 H3 Z M3 14 H8 V16 H3 Z M11 16.2 L12.4 14.8 L15 17.4 L20.4 12 L21.8 13.4 L15 20.2 Z"
    private const val SOUND = "M3 9 H7 L12 5 V19 L7 15 H3 Z M14.8 8.8 A4.5 4.5 0 0 1 14.8 15.2 L13.4 13.8 A2.5 2.5 0 0 0 13.4 10.2 Z M17.6 6 A8.5 8.5 0 0 1 17.6 18 L16.2 16.6 A6.5 6.5 0 0 0 16.2 7.4 Z"
    private const val LOCK = "M5 10 H19 V21 H5 Z M11 13.5 H13 V17.5 H11 Z M7 10 V7.5 A5 5 0 0 1 17 7.5 V10 H15 V7.5 A3 3 0 0 0 9 7.5 V10 Z"
    private const val TERMINAL = "M3.5 7.4 L4.9 6 L10.9 12 L4.9 18 L3.5 16.6 L8.1 12 Z M12 16 H20.5 V18 H12 Z"
    private const val INFO = "M12 2.5 A9.5 9.5 0 1 0 12 21.5 A9.5 9.5 0 1 0 12 2.5 Z M12 4.5 A7.5 7.5 0 1 1 12 19.5 A7.5 7.5 0 1 1 12 4.5 Z M11 10.5 H13 V17 H11 Z M11 7 H13 V9 H11 Z"
    private const val BOOK = "M2.5 5.2 C5.2 4.2 8.4 4.4 11 6 V20 C8.4 18.5 5.2 18.4 2.5 19.3 Z M13 6 C15.6 4.4 18.8 4.2 21.5 5.2 V19.3 C18.8 18.4 15.6 18.5 13 20 Z"
    private const val CHEVRON = "M9.4 5.5 L15.9 12 L9.4 18.5 L8 17.1 L13.1 12 L8 6.9 Z"

    private fun even(d: String): Path = PathParser.createPathFromPathData(d).apply { fillType = Path.FillType.EVEN_ODD }

    /** A keyboard: Appearance. */
    val keyboard = even(KEYBOARD)
    /** A stroke starting from a key: Typing and glide. */
    val glide: Path = PathParser.createPathFromPathData(GLIDE)
    /** Lines of text and a check: Corrections and suggestions. */
    val correct = even(CORRECT)
    /** A speaker: Sound and vibration. */
    val sound = even(SOUND)
    /** A padlock: Learning and privacy. */
    val lock = even(LOCK)
    /** A prompt and cursor: Terminal bar. */
    val terminal = even(TERMINAL)
    /** An open book: Dictionaries. */
    val book: Path = PathParser.createPathFromPathData(BOOK)
    /** An i in a ring: About. */
    val info = even(INFO)
    /** A chevron: a row that opens a page. */
    val chevron = even(CHEVRON)
}

/** One of the keyboard's glyphs as a Compose icon, in the current content colour unless [tint] is given. */
@Composable
internal fun Glyph(path: Path, size: Dp = 24.dp, tint: Color = LocalContentColor.current, modifier: Modifier = Modifier) {
    val px = with(LocalDensity.current) { size.toPx() }
    Canvas(modifier.size(size)) {
        val d = IconDrawable(path, tint.toArgb(), px)
        d.setBounds(0, 0, this.size.width.toInt(), this.size.height.toInt())
        drawIntoCanvas { d.draw(it.nativeCanvas) }
    }
}

/** The Shebang mark on its background, as the launcher draws it, with rounded corners. */
@Composable
internal fun AppMark(size: Dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(size * 0.28f)
    // A hairline keeps the mark's own dark square visible on the Night theme, whose background it shares.
    Box(modifier.size(size).clip(shape).border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.35f), shape)) {
        Image(painterResource(R.drawable.ic_launcher_background), contentDescription = null, modifier = Modifier.fillMaxSize())
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.fillMaxSize())
    }
}

/**
 * A settings page: a large title that collapses as the list scrolls, a back arrow, and the page's groups
 * with the design's margins. The list pads for the keyboard, so a field near the bottom stays visible.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsPage(
    title: String,
    onBack: (() -> Unit)?,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = actions,
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
            contentPadding = PaddingValues(start = PageMargin, end = PageMargin, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(GroupGap),
            content = content,
        )
    }
}

/** A titled group of rows on one card; rows are separated by hairlines. */
@Composable
internal fun SettingsGroup(title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 6.dp),
            )
        }
        Surface(shape = BoxShape, color = MaterialTheme.colorScheme.surfaceContainer, contentColor = MaterialTheme.colorScheme.onSurface, modifier = Modifier.fillMaxWidth()) {
            Column(content = content)
        }
    }
}

/** The hairline between two rows of a group. */
@Composable
internal fun RowDivider() {
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
}

private val rowColors @Composable get() = ListItemDefaults.colors(containerColor = Color.Transparent)

/** A setting that is on or off: the whole row toggles it. */
@Composable
internal fun SwitchRow(title: String, subtitle: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange, enabled = enabled) },
        colors = rowColors,
        modifier = Modifier.clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) },
    )
}

/** A row that opens a page or does something: an optional glyph, a title and a summary, and a chevron when it opens a page. */
@Composable
internal fun NavRow(
    title: String,
    summary: String?,
    icon: Path? = null,
    opensPage: Boolean = true,
    enabled: Boolean = true,
    onClick: (() -> Unit)?,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = summary?.let { { Text(it) } },
        leadingContent = icon?.let {
            {
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) { Glyph(it, 22.dp, MaterialTheme.colorScheme.primary) }
            }
        },
        trailingContent = if (opensPage && onClick != null) {
            { Glyph(SettingsIcons.chevron, 20.dp, MaterialTheme.colorScheme.onSurfaceVariant) }
        } else null,
        colors = rowColors,
        modifier = if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier,
    )
}

/** A small choice of two to four options, as one segmented row under the title. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> ChoiceRow(title: String, subtitle: String? = null, options: List<Pair<T, String>>, value: T, enabled: Boolean = true, onChange: (T) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (v, label) ->
                SegmentedButton(
                    selected = v == value,
                    onClick = { onChange(v) },
                    enabled = enabled,
                    shape = SegmentedButtonDefaults.itemShape(i, options.size, RoundedCornerShape(6.dp)),
                    label = { Text(label, maxLines = 1) },
                )
            }
        }
    }
}

/** A value on a scale, saved when the finger lifts, with its value shown on the right of the title. */
@Composable
internal fun SliderRow(title: String, valueLabel: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Float) -> Unit) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(valueLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(value = local, onValueChange = { local = it }, onValueChangeFinished = { onChange(local) }, valueRange = range, steps = steps)
    }
}

/** A paragraph of explanation under a page's title or between groups. */
@Composable
internal fun PageNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

/**
 * One row of a group that runs across several lazy-list items (a long list of words): the first row takes the
 * card's top corners, the last its bottom ones, and a hairline separates each from the one above.
 */
@Composable
internal fun CardRow(index: Int, count: Int, modifier: Modifier = Modifier, lifted: Boolean = false, content: @Composable () -> Unit) {
    val r = 8.dp
    // A row being dragged is a card of its own, with all four corners.
    val top = index == 0 || lifted
    val bottom = index == count - 1 || lifted
    val shape = RoundedCornerShape(
        topStart = if (top) r else 0.dp, topEnd = if (top) r else 0.dp,
        bottomStart = if (bottom) r else 0.dp, bottomEnd = if (bottom) r else 0.dp,
    )
    Surface(shape = shape, color = MaterialTheme.colorScheme.surfaceContainer, contentColor = MaterialTheme.colorScheme.onSurface, modifier = modifier.fillMaxWidth()) {
        Column {
            if (index > 0 && !lifted) RowDivider()
            content()
        }
    }
}

/** The title over a group built from [CardRow]s. */
@Composable
internal fun GroupTitle(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 6.dp),
    )
}

/** A list row inside a card: transparent, so the card shows through. */
@Composable
internal fun CardListItem(
    headline: String,
    supporting: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = { Text(headline) },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier,
    )
}
