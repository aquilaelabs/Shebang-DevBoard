package dev.shebang.devboard.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.shebang.devboard.view.KeyboardTheme
import dev.shebang.devboard.view.Palettes

/**
 * A row of swatches, one per theme: a tiny keyboard in the theme's own colours (keys with their keycap edge,
 * a function key and the accent key) above its name. The chosen one is outlined in the accent.
 */
@Composable
fun ThemePicker(selected: String, onSelect: (String) -> Unit) {
    val context = LocalContext.current
    val previews = remember(context) {
        Palettes.choices.map { (id, name) -> Triple(id, name, KeyboardTheme.build(context, Settings(palette = id))) }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text("Theme", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(8.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(previews, key = { it.first }) { (id, name, t) ->
                val isSelected = id == selected
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .width(88.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.RadioButton) { onSelect(id) }
                        .semantics {
                            this.selected = isSelected
                            contentDescription = "$name theme"
                        }
                        .padding(4.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 80.dp, height = 52.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(t.background))
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                shape = RoundedCornerShape(8.dp),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                repeat(4) { MiniKey(Color(t.key), Color(t.keyEdge)) }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                MiniKey(Color(t.keyFunctional), Color(t.keyFunctionalEdge))
                                MiniKey(Color(t.key), Color(t.keyEdge), wide = true)
                                MiniKey(Color(t.accent), Color(t.accentEdge))
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        name,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniKey(face: Color, edge: Color, wide: Boolean = false) {
    Box(
        Modifier
            .size(width = if (wide) 30.dp else 12.dp, height = 12.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(edge),
    ) {
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(2.dp)).background(face))
    }
}
