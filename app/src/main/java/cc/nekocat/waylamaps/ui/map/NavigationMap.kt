package cc.nekocat.waylamaps.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cc.nekocat.waylamaps.service.ThemeMode
import com.google.android.libraries.navigation.ForceNightMode
import com.google.android.libraries.navigation.NavigationView

/**
 * The Navigation SDK's map and guidance UI. Forwards the host lifecycle to the view, which the SDK
 * requires. Keep it at one composition position (see MapScreen's movableContentOf) so rotating
 * does not recreate it.
 */
@Composable
fun NavigationMap(themeMode: ThemeMode, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val configuration = LocalConfiguration.current
    val view = remember {
        NavigationView(context).apply {
            onCreate(null)
            // Speed is shown in our own panel.
            setSpeedometerEnabled(false)
        }
    }

    DisposableEffect(lifecycle, view) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> view.onStart()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_STOP -> view.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view.onPause()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) view.onStop()
            view.onDestroy()
        }
    }

    LaunchedEffect(themeMode) {
        view.setForceNightMode(
            when (themeMode) {
                ThemeMode.SYSTEM -> ForceNightMode.AUTO
                ThemeMode.LIGHT -> ForceNightMode.FORCE_DAY
                ThemeMode.DARK -> ForceNightMode.FORCE_NIGHT
            },
        )
    }

    // The activity handles rotation itself, so pass configuration changes on.
    LaunchedEffect(configuration) { view.onConfigurationChanged(configuration) }

    AndroidView(factory = { view }, modifier = modifier)
}
