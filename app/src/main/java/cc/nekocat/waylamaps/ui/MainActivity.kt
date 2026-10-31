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
import cc.nekocat.waylamaps.ui.theme.WaylaTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WaylaTheme {
                var showLegal by rememberSaveable { mutableStateOf(false) }
                if (showLegal) {
                    BackHandler { showLegal = false }
                    LegalScreen(onBack = { showLegal = false })
                } else {
                    MapScreen(onOpenLegal = { showLegal = true })
                }
            }
        }
    }
}
