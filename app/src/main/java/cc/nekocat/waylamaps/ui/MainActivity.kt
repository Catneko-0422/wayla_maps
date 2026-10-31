package cc.nekocat.waylamaps.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import cc.nekocat.waylamaps.ui.legal.LegalScreen
import cc.nekocat.waylamaps.ui.map.MapScreen
import cc.nekocat.waylamaps.ui.settings.SettingsScreen
import cc.nekocat.waylamaps.ui.theme.WaylaTheme
import dagger.hilt.android.AndroidEntryPoint

private enum class Screen { MAP, SETTINGS, LEGAL }

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WaylaTheme {
                var screen by rememberSaveable { mutableStateOf(Screen.MAP) }
                val back = { screen = Screen.MAP }
                when (screen) {
                    Screen.MAP -> MapScreen(
                        onOpenSettings = { screen = Screen.SETTINGS },
                        onOpenLegal = { screen = Screen.LEGAL },
                    )
                    Screen.SETTINGS -> {
                        BackHandler(onBack = back)
                        SettingsScreen(onBack = back)
                    }
                    Screen.LEGAL -> {
                        BackHandler(onBack = back)
                        LegalScreen(onBack = back)
                    }
                }
            }
        }
    }
}
