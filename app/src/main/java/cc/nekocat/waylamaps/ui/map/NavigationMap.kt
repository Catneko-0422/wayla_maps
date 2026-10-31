package cc.nekocat.waylamaps.ui.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import cc.nekocat.waylamaps.R
import cc.nekocat.waylamaps.service.ThemeMode
import cc.nekocat.waylamaps.ui.theme.WaylaPalette
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.libraries.navigation.ForceNightMode
import com.google.android.libraries.navigation.NavigationView
import com.google.android.libraries.navigation.StylingOptions
import kotlinx.coroutines.delay

/**
 * The Navigation SDK's map and guidance UI. Forwards the host lifecycle to the view, which the SDK
 * requires. Keep it at one composition position (see MapScreen's movableContentOf) so rotating
 * does not recreate it.
 */
@Composable
fun NavigationMap(themeMode: ThemeMode, showCompass: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val configuration = LocalConfiguration.current
    val view = remember {
        NavigationView(context).apply {
            onCreate(null)
            // Speed is shown in our own panel.
            setSpeedometerEnabled(false)
            setStylingOptions(turnCardStyle())
        }
    }

    // The map's day or night look follows the SDK's own switch (forced or by time of day).
    var googleMap by remember { mutableStateOf<GoogleMap?>(null) }
    var bearing by remember { mutableFloatStateOf(0f) }
    var nightMode by remember { mutableStateOf(false) }

    DisposableEffect(view) {
        var map: GoogleMap? = null
        var night = false
        fun applyStyle() {
            map?.setMapStyle(MapStyleOptions.loadRawResourceStyle(context, if (night) R.raw.map_style_night else R.raw.map_style_day))
        }
        val listener = NavigationView.OnNightModeChangedListener { event ->
            night = event.inNightMode()
            nightMode = night
            applyStyle()
        }
        view.addOnNightModeChangedListener(listener)
        view.getMapAsync {
            map = it
            // Our own compass sits in the top right corner instead (see MapCompass).
            it.uiSettings.isCompassEnabled = false
            googleMap = it
            applyStyle()
        }
        onDispose { view.removeOnNightModeChangedListener(listener) }
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

    // The camera has no change listener to spare (the SDK uses it), so the bearing is polled.
    LaunchedEffect(googleMap) {
        val map = googleMap ?: return@LaunchedEffect
        while (true) {
            bearing = map.cameraPosition.bearing
            delay(COMPASS_POLL_MS)
        }
    }

    Box(modifier) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        if (showCompass) {
            MapCompass(
                bearing = bearing,
                night = nightMode,
                onClick = {
                    val map = googleMap ?: return@MapCompass
                    map.animateCamera(CameraUpdateFactory.newCameraPosition(CameraPosition.builder(map.cameraPosition).bearing(0f).build()))
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
            )
        }
    }
}

/** A north arrow that turns with the map; tapping it turns the map back to north up. */
@Composable
private fun MapCompass(bearing: Float, night: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val face = Color(if (night) WaylaPalette.INK else WaylaPalette.WHITE)
    val south = Color(if (night) WaylaPalette.GRAY else WaylaPalette.GRAPHITE)
    val north = Color(if (night) WaylaPalette.NIGHT_BLUE else WaylaPalette.BLUE)
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = face,
        shadowElevation = 4.dp,
        modifier = modifier.size(48.dp),
    ) {
        Canvas(Modifier.fillMaxSize().padding(10.dp).rotate(-bearing)) {
            val cx = size.width / 2
            val half = size.width * 0.22f
            drawPath(
                Path().apply {
                    moveTo(cx, 0f)
                    lineTo(cx + half, size.height / 2)
                    lineTo(cx - half, size.height / 2)
                    close()
                },
                north,
            )
            drawPath(
                Path().apply {
                    moveTo(cx, size.height)
                    lineTo(cx + half, size.height / 2)
                    lineTo(cx - half, size.height / 2)
                    close()
                },
                south,
            )
        }
    }
}

private const val COMPASS_POLL_MS = 200L

/** The SDK's turn card in the app palette: deep blue by day, near black by night, white type. */
private fun turnCardStyle() = StylingOptions()
    .primaryDayModeThemeColor(WaylaPalette.DEEP_BLUE)
    .secondaryDayModeThemeColor(WaylaPalette.BLUE)
    .primaryNightModeThemeColor(WaylaPalette.INK)
    .secondaryNightModeThemeColor(WaylaPalette.DEEP_BLUE)
    .headerLargeManeuverIconColor(WaylaPalette.WHITE)
    .headerSmallManeuverIconColor(WaylaPalette.WHITE)
    .headerInstructionsTextColor(WaylaPalette.WHITE)
    .headerNextStepTextColor(WaylaPalette.WHITE)
    .headerDistanceValueTextColor(WaylaPalette.WHITE)
    .headerDistanceUnitsTextColor(WaylaPalette.LIGHT_BLUE)
    .headerGuidanceRecommendedLaneColor(WaylaPalette.LIGHT_BLUE)
