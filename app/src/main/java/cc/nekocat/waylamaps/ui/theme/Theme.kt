package cc.nekocat.waylamaps.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(WaylaPalette.BLUE),
    onPrimary = Color(WaylaPalette.WHITE),
    primaryContainer = Color(WaylaPalette.LIGHT_BLUE),
    onPrimaryContainer = Color(WaylaPalette.DEEP_BLUE),
    secondary = Color(WaylaPalette.DEEP_BLUE),
    onSecondary = Color(WaylaPalette.WHITE),
    secondaryContainer = Color(WaylaPalette.LIGHT_BLUE),
    onSecondaryContainer = Color(WaylaPalette.DEEP_BLUE),
    background = Color(WaylaPalette.WHITE),
    onBackground = Color(WaylaPalette.BLACK),
    surface = Color(WaylaPalette.WHITE),
    onSurface = Color(WaylaPalette.BLACK),
    surfaceVariant = Color(WaylaPalette.MIST),
    onSurfaceVariant = Color(WaylaPalette.GRAPHITE),
    surfaceContainerHighest = Color(WaylaPalette.MIST),
    surfaceContainerHigh = Color(WaylaPalette.PAPER),
    surfaceContainer = Color(WaylaPalette.PAPER),
    outline = Color(WaylaPalette.GRAY),
)

private val DarkColors = darkColorScheme(
    primary = Color(WaylaPalette.NIGHT_BLUE),
    onPrimary = Color(WaylaPalette.BLACK),
    primaryContainer = Color(WaylaPalette.DEEP_BLUE),
    onPrimaryContainer = Color(WaylaPalette.LIGHT_BLUE),
    secondary = Color(WaylaPalette.NIGHT_BLUE),
    onSecondary = Color(WaylaPalette.BLACK),
    secondaryContainer = Color(WaylaPalette.DEEP_BLUE),
    onSecondaryContainer = Color(WaylaPalette.LIGHT_BLUE),
    background = Color(WaylaPalette.BLACK),
    onBackground = Color(WaylaPalette.PAPER),
    surface = Color(WaylaPalette.BLACK),
    onSurface = Color(WaylaPalette.PAPER),
    surfaceVariant = Color(WaylaPalette.INK),
    onSurfaceVariant = Color(WaylaPalette.MIST),
    surfaceContainerHighest = Color(WaylaPalette.INK),
    surfaceContainerHigh = Color(WaylaPalette.INK),
    surfaceContainer = Color(WaylaPalette.INK),
    outline = Color(WaylaPalette.GRAPHITE),
)

/** Fixed black, white and blue colors (no wallpaper-based dynamic color), matching the map. */
@Composable
fun WaylaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}
