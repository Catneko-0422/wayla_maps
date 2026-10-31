package cc.nekocat.waylamaps.ui.map

import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cc.nekocat.waylamaps.BuildConfig
import cc.nekocat.waylamaps.R
import androidx.compose.foundation.layout.Box
import cc.nekocat.waylamaps.navigation.Trip
import cc.nekocat.waylamaps.navigation.GuidanceState
import cc.nekocat.waylamaps.navigation.PlaceSuggestion
import cc.nekocat.waylamaps.navigation.RouteFailure
import cc.nekocat.waylamaps.navigation.SavedPlace
import cc.nekocat.waylamaps.navigation.SavedPlacesData
import cc.nekocat.waylamaps.navigation.TravelMode
import cc.nekocat.waylamaps.ui.labelRes
import com.google.android.libraries.navigation.TimeAndDistance
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** What the browse panel (portrait sheet, landscape card) can do. */
class BrowseActions(
    val onQueryChange: (String) -> Unit,
    val onSelect: (PlaceSuggestion) -> Unit,
    val onClearSearch: () -> Unit,
    val onTravelMode: (TravelMode) -> Unit,
    val onStart: (simulate: Boolean) -> Unit,
    val onSetHome: (PlaceSuggestion?) -> Unit,
    val onSetWork: (PlaceSuggestion?) -> Unit,
    val onRemoveRecent: (SavedPlace) -> Unit,
    val onAlertsChange: (Boolean) -> Unit,
    val onAddPointHere: () -> Unit,
    val onOpenSettings: () -> Unit,
    val onAddStop: () -> Unit,
    val onRemoveStop: (index: Int) -> Unit,
    val onMoveStop: (index: Int, by: Int) -> Unit,
)

/** A rounded search field with a settings button, floating over the map. */
@Composable
fun SearchBar(query: String, actions: BrowseActions, modifier: Modifier = Modifier, addingStop: Boolean = false) {
    Surface(shape = RoundedCornerShape(28.dp), shadowElevation = 6.dp, modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextField(
                value = query,
                onValueChange = actions.onQueryChange,
                singleLine = true,
                placeholder = { Text(stringResource(if (addingStop) R.string.stop_search_hint else R.string.search_hint)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty() || addingStop) {
                        IconButton(onClick = actions.onClearSearch) {
                            Icon(painterResource(R.drawable.ic_close), stringResource(R.string.action_clear))
                        }
                    }
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = actions.onOpenSettings, modifier = Modifier.padding(end = 4.dp)) {
                Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings_title))
            }
        }
    }
}

/** Speed, the alerts switch and the next camera, always visible at the top of the panel. */
@Composable
fun RideStatusRow(state: MapUiState, onAlertsChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            state.alerts.speedKmh?.toString() ?: stringResource(R.string.speed_unknown),
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            stringResource(R.string.speed_unit),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(start = 4.dp),
        )
        Spacer(Modifier.weight(1f))
        Text(stringResource(R.string.speed_camera_alerts), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = state.alertsEnabled, onCheckedChange = onAlertsChange, modifier = Modifier.padding(start = 8.dp))
    }
    val alerts = state.alerts
    val line = when {
        !alerts.running -> null
        alerts.waitingForGps -> stringResource(R.string.waiting_for_gps)
        alerts.nextCameraMeters != null -> stringResource(R.string.next_camera_distance, alerts.nextCameraMeters) +
            (alerts.nextCameraLimitKmh?.let { "・" + stringResource(R.string.next_camera_limit, it) } ?: "")
        alerts.nextEnforcementMeters != null -> stringResource(R.string.next_enforcement_distance, alerts.nextEnforcementMeters)
        else -> stringResource(R.string.no_camera_nearby)
    }
    line?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
}

/**
 * Below the status row: saved places or search results while searching, the route preview once
 * a destination is chosen. No typing happens here during guidance (the panel is hidden then).
 */
@Composable
fun DestinationContent(state: MapUiState, saved: SavedPlacesData, query: String, actions: BrowseActions) {
    val selected = state.search.selected
    if (state.search.addingStop) AddingStopHeader(actions)
    when {
        selected != null -> RouteContent(state, selected, saved, actions)
        query.isBlank() -> SavedPlacesList(saved, actions)
        else -> SuggestionList(state, actions)
    }
}

/** While searching for a stop: what the search is for, and a way back to the trip. */
@Composable
private fun AddingStopHeader(actions: BrowseActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_add), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(
            stringResource(R.string.stop_adding),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .weight(1f)
                .padding(start = 8.dp),
        )
        TextButton(onClick = actions.onClearSearch) { Text(stringResource(R.string.action_cancel)) }
    }
}

@Composable
private fun SavedPlacesList(saved: SavedPlacesData, actions: BrowseActions) {
    PlaceRow(
        R.drawable.ic_home,
        stringResource(R.string.place_home),
        saved.home?.name ?: stringResource(R.string.place_not_set),
        enabled = saved.home != null,
    ) { saved.home?.let { actions.onSelect(it.toSuggestion()) } }
    PlaceRow(
        R.drawable.ic_work,
        stringResource(R.string.place_work),
        saved.work?.name ?: stringResource(R.string.place_not_set),
        enabled = saved.work != null,
    ) { saved.work?.let { actions.onSelect(it.toSuggestion()) } }
    if (saved.recent.isNotEmpty()) {
        Text(
            stringResource(R.string.place_recent),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        saved.recent.forEach { place ->
            ListItem(
                headlineContent = { Text(place.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(place.address, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingContent = { Icon(painterResource(R.drawable.ic_history), contentDescription = null) },
                trailingContent = {
                    IconButton(onClick = { actions.onRemoveRecent(place) }) {
                        Icon(painterResource(R.drawable.ic_close), stringResource(R.string.action_delete))
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { actions.onSelect(place.toSuggestion()) },
            )
        }
    }
}

@Composable
private fun PlaceRow(@DrawableRes icon: Int, title: String, subtitle: String, enabled: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, fontWeight = FontWeight.Bold) },
        supportingContent = { Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(
                    painterResource(icon),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(8.dp),
                )
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = if (enabled) Modifier.clickable(onClick = onClick) else Modifier,
    )
}

@Composable
private fun SuggestionList(state: MapUiState, actions: BrowseActions) {
    val keyboard = LocalSoftwareKeyboardController.current
    if (state.search.failed) {
        Text(stringResource(R.string.search_failed), color = MaterialTheme.colorScheme.error)
    }
    state.search.suggestions.forEach { suggestion ->
        ListItem(
            headlineContent = { Text(suggestion.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(suggestion.address, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingContent = { Icon(painterResource(R.drawable.ic_place), contentDescription = null) },
            trailingContent = { suggestion.distanceMeters?.let { Text(formatDistance(it)) } },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable {
                keyboard?.hide()
                actions.onSelect(suggestion)
            },
        )
    }
}

/** The chosen destination: route summary, vehicle, start button, and saving it as home or work. */
@Composable
private fun RouteContent(state: MapUiState, destination: PlaceSuggestion, saved: SavedPlacesData, actions: BrowseActions) {
    var simulate by rememberSaveable { mutableStateOf(false) }
    val guidance = state.guidance

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(destination.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2)
            Text(
                destination.address,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = actions.onClearSearch) {
            Icon(painterResource(R.drawable.ic_close), stringResource(R.string.action_clear))
        }
    }

    state.search.trip?.let { trip -> StopsList(trip, guidance as? GuidanceState.Previewing, actions) }

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
        Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    when (guidance) {
        is GuidanceState.Previewing -> RouteSummary(guidance)
        is GuidanceState.Routing -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(56.dp)) {
            CircularProgressIndicator(Modifier.size(28.dp))
            Text(stringResource(R.string.navigation_routing), modifier = Modifier.padding(start = 12.dp))
        }
        is GuidanceState.Failed -> Text(stringResource(guidance.reason.messageRes()), color = MaterialTheme.colorScheme.error)
        is GuidanceState.Arrived -> Text(stringResource(R.string.navigation_arrived, guidance.destination.name))
        else -> Unit
    }

    val failed = guidance is GuidanceState.Failed
    Button(
        onClick = { if (failed) actions.onSelect(destination) else actions.onStart(simulate) },
        enabled = guidance is GuidanceState.Previewing || failed,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp),
    ) {
        Icon(painterResource(R.drawable.ic_navigation), contentDescription = null, modifier = Modifier.padding(end = 8.dp))
        Text(
            stringResource(if (failed) R.string.route_retry else R.string.navigation_start),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
    }

    if (BuildConfig.DEBUG) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = simulate, onCheckedChange = { simulate = it })
            Text(stringResource(R.string.simulate_route))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val isHome = saved.home?.placeId == destination.placeId
        val isWork = saved.work?.placeId == destination.placeId
        OutlinedButton(onClick = { actions.onSetHome(if (isHome) null else destination) }) {
            Icon(painterResource(R.drawable.ic_home), contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                stringResource(if (isHome) R.string.place_unset_home else R.string.place_set_home),
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        OutlinedButton(onClick = { actions.onSetWork(if (isWork) null else destination) }) {
            Icon(painterResource(R.drawable.ic_work), contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                stringResource(if (isWork) R.string.place_unset_work else R.string.place_set_work),
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

/**
 * The trip in order (here, each stop, the destination) with the time to each stop, and buttons to
 * reorder or drop stops and to add one. Only shown before guidance, so there is no typing on the
 * move.
 */
@Composable
private fun StopsList(trip: Trip, preview: GuidanceState.Previewing?, actions: BrowseActions) {
    if (trip.stops.isNotEmpty()) {
        Column {
            StopLine(R.drawable.ic_navigation, stringResource(R.string.stop_from_here), null)
            trip.stops.forEachIndexed { index, stop ->
                val eta = preview?.waypointSeconds?.getOrNull(index)?.let { formatDuration(it) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        StopLine(R.drawable.ic_place, stringResource(R.string.stop_label, index + 1, stop.name), eta)
                    }
                    // Reordering needs two stops; with one, the name gets the room.
                    if (trip.stops.size > 1) {
                        IconButton(onClick = { actions.onMoveStop(index, -1) }, enabled = index > 0) {
                            Icon(painterResource(R.drawable.ic_arrow_upward), stringResource(R.string.stop_move_up))
                        }
                        IconButton(onClick = { actions.onMoveStop(index, 1) }, enabled = index < trip.stops.lastIndex) {
                            Icon(painterResource(R.drawable.ic_arrow_downward), stringResource(R.string.stop_move_down))
                        }
                    }
                    IconButton(onClick = { actions.onRemoveStop(index) }) {
                        Icon(painterResource(R.drawable.ic_close), stringResource(R.string.stop_remove))
                    }
                }
            }
            StopLine(
                R.drawable.ic_place,
                trip.destination.name,
                preview?.waypointSeconds?.lastOrNull()?.let { formatDuration(it) },
                destination = true,
            )
        }
    }
    if (trip.canAddStop) {
        TextButton(onClick = actions.onAddStop, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.stop_add), modifier = Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
private fun StopLine(@DrawableRes icon: Int, text: String, eta: String?, destination: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 40.dp)) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = if (destination) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (destination) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f, fill = false)
                .padding(start = 10.dp),
        )
        eta?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** "23 min・8.9 km", with the arrival time and the traffic level in words. */
@Composable
private fun RouteSummary(preview: GuidanceState.Previewing) = Column {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            formatDuration(preview.seconds),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = trafficColor(preview.delaySeverity),
        )
        Text(
            "（" + formatDistance(preview.meters) + "）",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 6.dp, bottom = 3.dp),
        )
    }
    val arrival = LocalTime.now().plusSeconds(preview.seconds.toLong()).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    val traffic = trafficLabel(preview.delaySeverity)
    Text(
        stringResource(R.string.route_arrival, arrival) + (traffic?.let { "・$it" } ?: ""),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun trafficColor(severity: Int): Color = when (severity) {
    TimeAndDistance.DELAY_HEAVY -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurface
}

@Composable
private fun trafficLabel(severity: Int): String? = when (severity) {
    TimeAndDistance.DELAY_LIGHT -> stringResource(R.string.traffic_light)
    TimeAndDistance.DELAY_MEDIUM -> stringResource(R.string.traffic_medium)
    TimeAndDistance.DELAY_HEAVY -> stringResource(R.string.traffic_heavy)
    else -> null
}

/** Rarely used actions at the bottom of the panel. */
@Composable
fun PanelFooter(actions: BrowseActions) {
    HorizontalDivider(Modifier.padding(top = 4.dp))
    TextButton(onClick = actions.onAddPointHere) {
        Icon(painterResource(R.drawable.ic_place), contentDescription = null, modifier = Modifier.size(18.dp))
        Text(stringResource(R.string.add_point_here), modifier = Modifier.padding(start = 6.dp))
    }
}

internal fun RouteFailure.messageRes() = when (this) {
    RouteFailure.NO_ROUTE -> R.string.route_failed_no_route
    RouteFailure.NETWORK -> R.string.route_failed_network
    RouteFailure.LOCATION_UNKNOWN -> R.string.route_failed_location
    RouteFailure.QUOTA -> R.string.route_failed_quota
    RouteFailure.NOT_READY, RouteFailure.OTHER -> R.string.route_failed_other
}
