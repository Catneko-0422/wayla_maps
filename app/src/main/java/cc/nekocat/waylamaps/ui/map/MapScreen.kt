package cc.nekocat.waylamaps.ui.map

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.nekocat.waylamaps.BuildConfig
import cc.nekocat.waylamaps.R
import cc.nekocat.waylamaps.navigation.GuidanceState
import cc.nekocat.waylamaps.navigation.PlaceSuggestion
import cc.nekocat.waylamaps.navigation.RouteFailure
import cc.nekocat.waylamaps.navigation.TravelMode
import cc.nekocat.waylamaps.service.ThemeMode
import cc.nekocat.waylamaps.ui.character.CharacterView
import cc.nekocat.waylamaps.ui.labelRes
import cc.nekocat.waylamaps.ui.main.NavigationSetupCard
import cc.nekocat.waylamaps.ui.main.NextCamera
import cc.nekocat.waylamaps.ui.main.Speed
import cc.nekocat.waylamaps.ui.permissions.openAppSettings
import cc.nekocat.waylamaps.ui.permissions.rememberLocationPermissionRequest
import cc.nekocat.waylamaps.voice.Speech
import java.util.Locale
import kotlinx.coroutines.withTimeoutOrNull

private class PanelActions(
    val onEnableNavigation: (Activity?) -> Unit,
    val onAlertsChange: (Boolean) -> Unit,
    val onQueryChange: (String) -> Unit,
    val onSelect: (PlaceSuggestion) -> Unit,
    val onClearSearch: () -> Unit,
    val onTravelMode: (TravelMode) -> Unit,
    val onStart: (simulate: Boolean) -> Unit,
    val onStop: () -> Unit,
    val onAddPointHere: () -> Unit,
    val onUnlock: () -> Unit,
    val onOpenAppSettings: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onOpenLegal: () -> Unit,
)

/**
 * Main screen: the navigation map plus a control panel. Portrait puts the panel under the map;
 * landscape (handlebar mounts, car dashboards) puts it beside the map.
 */
@Composable
fun MapScreen(
    themeMode: ThemeMode,
    onOpenSettings: () -> Unit,
    onOpenLegal: () -> Unit,
    viewModel: MapViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val query = viewModel.query
    val context = LocalContext.current
    var locationDenied by remember { mutableStateOf(false) }
    val enableAlerts = rememberLocationPermissionRequest { granted ->
        locationDenied = !granted
        if (granted) viewModel.setAlertsEnabled(true)
    }
    val actions = PanelActions(
        onEnableNavigation = viewModel::initializeNavigation,
        onAlertsChange = { on -> if (on) enableAlerts() else viewModel.setAlertsEnabled(false) },
        onQueryChange = viewModel::onQueryChange,
        onSelect = viewModel::select,
        onClearSearch = viewModel::clearSearch,
        onTravelMode = viewModel::setTravelMode,
        onStart = viewModel::startNavigation,
        onStop = viewModel::stopNavigation,
        onAddPointHere = viewModel::addPointHere,
        onUnlock = viewModel::unlockManually,
        onOpenAppSettings = { context.openAppSettings() },
        onOpenSettings = onOpenSettings,
        onOpenLegal = onOpenLegal,
    )
    // One map instance moved between the two layouts, so rotating keeps the route on screen.
    val currentTheme by rememberUpdatedState(themeMode)
    // The turn card covers the map's top edge while guiding, so the compass shows only otherwise.
    val currentGuiding by rememberUpdatedState(state.guiding)
    val map = remember { movableContentOf { NavigationMap(currentTheme, !currentGuiding, Modifier.fillMaxSize()) } }
    val characterModel by viewModel.characterModel.collectAsStateWithLifecycle()
    val speech by viewModel.speech.collectAsStateWithLifecycle()
    val characterName by viewModel.selectedCharacterName.collectAsStateWithLifecycle()

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            val landscape = maxWidth > maxHeight
            val portraitPanelMaxHeight = maxHeight * PORTRAIT_PANEL_FRACTION
            when {
                !state.navigatorReady -> ControlPanel(state, query, locationDenied, actions, Modifier.fillMaxSize(), large = true)
                landscape -> Row(Modifier.fillMaxSize()) {
                    ControlPanel(
                        state,
                        query,
                        locationDenied,
                        actions,
                        Modifier
                            .width(PANEL_WIDTH)
                            .fillMaxHeight(),
                    )
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        map()
                        // The character's upper body rises from the bottom right corner, cut off by
                        // the screen edge over the empty right half of the SDK's trip summary. It
                        // stays below the turn card even when lane guidance makes the card taller.
                        characterModel?.let { model ->
                            FloatingCharacter(
                                model,
                                speech,
                                Modifier.align(Alignment.BottomEnd).padding(end = 16.dp),
                            )
                        }
                    }
                }
                else -> Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        map()
                        // Portrait has no room for the 3D character; the sentence appears bottom right,
                        // above the trip summary, clear of the vehicle (center) and Google logo (left).
                        speech?.let {
                            SpeechBubble(
                                characterName,
                                it.text,
                                Modifier
                                    .align(Alignment.BottomEnd)
                                    .widthIn(max = BUBBLE_MAX_WIDTH)
                                    .padding(end = 12.dp, bottom = TRIP_SUMMARY_HEIGHT),
                            )
                        }
                    }
                    ControlPanel(
                        state,
                        query,
                        locationDenied,
                        actions,
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = portraitPanelMaxHeight),
                    )
                }
            }
        }
    }
}

@Composable
private fun ControlPanel(
    state: MapUiState,
    query: String,
    locationDenied: Boolean,
    actions: PanelActions,
    modifier: Modifier = Modifier,
    large: Boolean = false,
) {
    if (state.locked) {
        LockedPanel(state, actions, modifier)
        return
    }
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Speed(state.alerts.speedKmh, fontSize = if (large) 96.sp else 48.sp)
            Spacer(Modifier.weight(1f))
            // Alerts always run during guidance, so the switch only matters otherwise.
            if (!state.guiding) {
                Text(stringResource(R.string.speed_camera_alerts), style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = state.alertsEnabled,
                    onCheckedChange = actions.onAlertsChange,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        val guidance = state.guidance
        // While navigating, ending the trip comes first so it stays on screen in landscape.
        if (state.navigatorReady && guidance is GuidanceState.Guiding) GuidingControls(guidance, actions.onStop)
        NextCamera(state.alerts)

        if (locationDenied) {
            Text(
                text = stringResource(R.string.location_permission_needed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = actions.onOpenAppSettings) { Text(stringResource(R.string.open_settings)) }
        }

        when {
            !state.navigatorReady -> NavigationSetupCard(state.navigator, actions.onEnableNavigation)
            guidance is GuidanceState.Guiding -> Unit
            else -> DestinationControls(state, query, actions)
        }

        if (!state.guiding) {
            // Only offered when not navigating; the view model also refuses while moving.
            OutlinedButton(onClick = actions.onAddPointHere, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.add_point_here))
            }
            HorizontalDivider()
            Text(
                text = stringResource(R.string.data_attribution),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row {
                TextButton(onClick = actions.onOpenSettings) { Text(stringResource(R.string.settings_title)) }
                TextButton(onClick = actions.onOpenLegal) { Text(stringResource(R.string.legal_title)) }
            }
        }
    }
}

/**
 * What remains while the auto-lock is on: large speed, the next alerts, ending navigation, and
 * a press-and-hold area for a passenger to unlock.
 */
@Composable
private fun LockedPanel(state: MapUiState, actions: PanelActions, modifier: Modifier) {
    // Compact enough for a landscape phone: both buttons share one row under the alerts.
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Speed(state.alerts.speedKmh, fontSize = 72.sp)
        NextCamera(state.alerts)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.guidance is GuidanceState.Guiding) {
                Button(
                    onClick = actions.onStop,
                    colors = endTripColors(),
                    modifier = Modifier
                        .weight(1f)
                        .height(72.dp),
                ) {
                    Text(stringResource(R.string.navigation_stop), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .weight(1f)
                    .height(72.dp)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown()
                            val released = withTimeoutOrNull(UNLOCK_HOLD_MS) { waitForUpOrCancellation() }
                            if (released == null) actions.onUnlock()
                        }
                    },
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(8.dp)) {
                    Text(
                        stringResource(R.string.locked_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** Destination search and route options. Never shown during guidance (no typing while moving). */
@Composable
private fun DestinationControls(state: MapUiState, query: String, actions: PanelActions) {
    val search = state.search
    val keyboard = LocalSoftwareKeyboardController.current
    var simulate by rememberSaveable { mutableStateOf(false) }

    OutlinedTextField(
        value = query,
        onValueChange = actions.onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text(stringResource(R.string.search_hint)) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                TextButton(onClick = actions.onClearSearch) { Text(stringResource(R.string.action_clear)) }
            }
        },
    )
    if (search.failed) {
        Text(stringResource(R.string.search_failed), color = MaterialTheme.colorScheme.error)
    }
    search.suggestions.forEach { suggestion ->
        ListItem(
            headlineContent = { Text(suggestion.name) },
            supportingContent = { Text(suggestion.address, maxLines = 1) },
            trailingContent = { suggestion.distanceMeters?.let { Text(formatDistance(it)) } },
            modifier = Modifier.clickable {
                keyboard?.hide()
                actions.onSelect(suggestion)
            },
        )
    }

    search.selected?.let { destination ->
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(destination.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(destination.address, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        TravelMode.entries.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = state.travelMode == mode,
                onClick = { actions.onTravelMode(mode) },
                shape = SegmentedButtonDefaults.itemShape(index, TravelMode.entries.size),
            ) {
                Text(stringResource(mode.labelRes()))
            }
        }
    }
    val routeNotice = when (state.travelMode) {
        TravelMode.SCOOTER -> R.string.scooter_route_notice
        TravelMode.HEAVY_MOTORCYCLE -> R.string.heavy_motorcycle_route_notice
        TravelMode.CAR -> null
    }
    routeNotice?.let {
        Text(
            text = stringResource(it),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    when (val guidance = state.guidance) {
        is GuidanceState.Routing -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.padding(end = 12.dp))
            Text(stringResource(R.string.navigation_routing))
        }
        is GuidanceState.Failed -> Text(
            text = stringResource(guidance.reason.messageRes()),
            color = MaterialTheme.colorScheme.error,
        )
        is GuidanceState.Arrived -> Text(stringResource(R.string.navigation_arrived, guidance.destination.name))
        else -> Unit
    }

    if (BuildConfig.DEBUG) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = simulate, onCheckedChange = { simulate = it })
            Text(stringResource(R.string.simulate_route))
        }
    }

    Button(
        onClick = { actions.onStart(simulate) },
        enabled = search.selected != null && state.guidance !is GuidanceState.Routing,
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
    ) {
        Text(stringResource(R.string.navigation_start), fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun GuidingControls(guidance: GuidanceState.Guiding, onStop: () -> Unit) {
    Text(
        text = stringResource(R.string.navigating_to, guidance.destination.name),
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Start,
    )
    if (guidance.simulated) {
        Text(stringResource(R.string.simulate_route), style = MaterialTheme.typography.bodySmall)
    }
    Button(
        onClick = onStop,
        colors = endTripColors(),
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp),
    ) {
        Text(stringResource(R.string.navigation_stop), fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}

private fun RouteFailure.messageRes() = when (this) {
    RouteFailure.NO_ROUTE -> R.string.route_failed_no_route
    RouteFailure.NETWORK -> R.string.route_failed_network
    RouteFailure.LOCATION_UNKNOWN -> R.string.route_failed_location
    RouteFailure.QUOTA -> R.string.route_failed_quota
    RouteFailure.NOT_READY, RouteFailure.OTHER -> R.string.route_failed_other
}

private fun formatDistance(meters: Int): String =
    if (meters < 1000) "$meters m" else String.format(Locale.ROOT, "%.1f km", meters / 1000.0)

@Composable
private fun FloatingCharacter(model: CharacterModel, speech: Speech?, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        speech?.let {
            // Over the empty right half of the trip summary, clear of the vehicle in the map's center.
            SpeechBubble(model.name, it.text, Modifier.widthIn(max = BUBBLE_MAX_WIDTH).padding(bottom = 8.dp))
        }
        key(model.id) {
            CharacterView(model.bytes, model.spec, speech, Modifier.size(CHARACTER_WIDTH, CHARACTER_HEIGHT))
        }
    }
}

@Composable
private fun SpeechBubble(name: String, text: String, modifier: Modifier = Modifier) {
    Card(modifier) {
        Column(Modifier.padding(12.dp)) {
            Text(name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(text, style = MaterialTheme.typography.bodyLarge, maxLines = 3)
        }
    }
}

/** Black on light, white on dark: strong but inside the black, white and blue palette. */
@Composable
private fun endTripColors() = ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.colorScheme.onSurface,
    contentColor = MaterialTheme.colorScheme.surface,
)

private val PANEL_WIDTH = 290.dp
private val BUBBLE_MAX_WIDTH = 170.dp

/** Height of the Navigation SDK's trip summary (time, distance, arrival) along the map's bottom. */
private val TRIP_SUMMARY_HEIGHT = 96.dp
private val CHARACTER_WIDTH = 240.dp

/** Below the turn card even with lane guidance (about 155 dp of a 385 dp landscape map). */
private val CHARACTER_HEIGHT = 210.dp
private const val UNLOCK_HOLD_MS = 2_000L
private const val PORTRAIT_PANEL_FRACTION = 0.55f
