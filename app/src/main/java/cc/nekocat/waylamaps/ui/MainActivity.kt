package cc.nekocat.waylamaps.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import cc.nekocat.waylamaps.ui.main.MainScreen
import cc.nekocat.waylamaps.ui.theme.WaylaTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WaylaTheme {
                MainScreen()
            }
        }
    }
}
