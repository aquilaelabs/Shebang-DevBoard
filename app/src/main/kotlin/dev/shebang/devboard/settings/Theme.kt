package dev.shebang.devboard.settings

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import dev.shebang.devboard.view.Palettes

/**
 * The setup and settings screens wear the keyboard's theme: its background, text and accent. Wallpaper takes
 * the system palette on Android 12+; Auto (and Wallpaper before 12) follow the system between Night and Day.
 */
@Composable
fun DevBoardTheme(palette: String = Palettes.AUTO, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()
    var dark = systemDark
    val scheme = if (palette == Palettes.WALLPAPER && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (systemDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        val p = when (palette) {
            Palettes.AUTO, Palettes.WALLPAPER -> Palettes.byId(if (systemDark) Palettes.NIGHT else Palettes.DAY)
            else -> Palettes.byId(palette) ?: Palettes.byId(if (systemDark) Palettes.NIGHT else Palettes.DAY)
        }!!
        dark = p.dark
        val accent = Color(p.accent)
        val onAccent = Color(p.onAccent)
        val bg = Color(p.background)
        val text = Color(p.keyText)
        val card = Color(p.key)
        val muted = Color(p.keyTextSecondary)
        if (p.dark) {
            darkColorScheme(
                primary = accent, onPrimary = onAccent,
                secondaryContainer = Color(p.modifierActive), onSecondaryContainer = text,
                background = bg, onBackground = text, surface = bg, onSurface = text,
                surfaceVariant = card, onSurfaceVariant = muted, outline = muted,
                surfaceContainer = card, surfaceContainerHigh = card, surfaceContainerHighest = card,
            )
        } else {
            lightColorScheme(
                primary = accent, onPrimary = onAccent,
                secondaryContainer = Color(p.modifierActive), onSecondaryContainer = text,
                background = bg, onBackground = text, surface = bg, onSurface = text,
                surfaceVariant = card, onSurfaceVariant = muted, outline = muted,
                surfaceContainer = card, surfaceContainerHigh = card, surfaceContainerHighest = card,
            )
        }
    }
    // Status and navigation bar icons that read on the theme's background.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
