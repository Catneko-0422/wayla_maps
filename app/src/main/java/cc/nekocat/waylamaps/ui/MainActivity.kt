package cc.nekocat.waylamaps.ui

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.nekocat.waylamaps.character.CharacterRepository
import cc.nekocat.waylamaps.service.RidePreferences
import cc.nekocat.waylamaps.service.ThemeMode
import cc.nekocat.waylamaps.ui.legal.LegalScreen
import cc.nekocat.waylamaps.ui.map.MapScreen
import cc.nekocat.waylamaps.ui.settings.SettingsScreen
import cc.nekocat.waylamaps.ui.theme.WaylaTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

private enum class Screen { MAP, SETTINGS, LEGAL }

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var ridePreferences: RidePreferences
    @Inject lateinit var characterRepository: CharacterRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode by ridePreferences.theme.collectAsStateWithLifecycle()
            val dark = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            LaunchedEffect(dark) {
                val bars = if (dark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            WaylaTheme(darkTheme = dark) {
                var screen by rememberSaveable { mutableStateOf(Screen.MAP) }
                val back = { screen = Screen.MAP }
                when (screen) {
                    Screen.MAP -> MapScreen(
                        themeMode = themeMode,
                        onOpenSettings = { screen = Screen.SETTINGS },
                    )
                    Screen.SETTINGS -> {
                        BackHandler(onBack = back)
                        SettingsScreen(onBack = back, onOpenLegal = { screen = Screen.LEGAL })
                    }
                    Screen.LEGAL -> {
                        val toSettings = { screen = Screen.SETTINGS }
                        BackHandler(onBack = toSettings)
                        val characters by characterRepository.characters.collectAsStateWithLifecycle()
                        LegalScreen(credits = characters.mapNotNull { it.manifest.credit }, onBack = toSettings)
                    }
                }
            }
        }
    }
}
