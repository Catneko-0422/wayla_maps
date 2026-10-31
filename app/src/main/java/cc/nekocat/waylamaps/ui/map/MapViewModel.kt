package cc.nekocat.waylamaps.ui.map

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.nekocat.waylamaps.alerts.AlertSessionState
import cc.nekocat.waylamaps.data.UserPointRepository
import cc.nekocat.waylamaps.navigation.GuidanceState
import cc.nekocat.waylamaps.navigation.NavigationController
import cc.nekocat.waylamaps.navigation.NavigationSession
import cc.nekocat.waylamaps.navigation.NavigatorState
import cc.nekocat.waylamaps.navigation.PlaceSuggestion
import cc.nekocat.waylamaps.navigation.PlacesSearch
import cc.nekocat.waylamaps.navigation.TravelMode
import cc.nekocat.waylamaps.navigation.TravelModePreference
import cc.nekocat.waylamaps.service.RideController
import cc.nekocat.waylamaps.ui.confirmationPhrase
import cc.nekocat.waylamaps.ui.main.MainUiState
import cc.nekocat.waylamaps.ui.main.toUiState
import cc.nekocat.waylamaps.ui.permissions.hasLocationPermission
import cc.nekocat.waylamaps.voice.VoiceFeedback
import com.google.android.gms.maps.model.LatLng
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Search results. The query text itself is [MapViewModel.query], kept outside any flow. */
data class SearchState(
    val suggestions: List<PlaceSuggestion> = emptyList(),
    val failed: Boolean = false,
    val selected: PlaceSuggestion? = null,
)

data class MapUiState(
    val navigator: NavigatorState = NavigatorState.NotInitialized,
    val guidance: GuidanceState = GuidanceState.Idle,
    val travelMode: TravelMode = TravelMode.SCOOTER,
    val alerts: MainUiState = MainUiState(),
    val alertsEnabled: Boolean = false,
    val search: SearchState = SearchState(),
) {
    val navigatorReady: Boolean get() = navigator is NavigatorState.Ready
    val guiding: Boolean get() = guidance is GuidanceState.Guiding
}

@OptIn(FlowPreview::class)
@HiltViewModel
class MapViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    alertState: AlertSessionState,
    private val navigation: NavigationController,
    private val session: NavigationSession,
    private val rideController: RideController,
    private val travelMode: TravelModePreference,
    private val places: PlacesSearch,
    private val voice: VoiceFeedback,
    private val userPoints: UserPointRepository,
) : ViewModel() {
    private val search = MutableStateFlow(SearchState())

    /**
     * Text field contents as Compose state, updated synchronously. Routing it through the
     * combined ui-state flow lags behind typing and drops characters.
     */
    var query by mutableStateOf("")
        private set

    val uiState: StateFlow<MapUiState> = combine(
        combine(navigation.state, session.state, travelMode.mode, ::Triple),
        combine(alertState.session, rideController.alertsEnabled, ::Pair),
        search,
    ) { (navigator, guidance, mode), (alerts, alertsEnabled), search ->
        MapUiState(navigator, guidance, mode, alerts.toUiState(), alertsEnabled, search)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MapUiState())

    init {
        // Returning users have already granted everything; start the navigator straight away.
        if (context.hasLocationPermission() && navigation.areTermsAccepted()) navigation.initialize()

        viewModelScope.launch {
            combine(snapshotFlow { query }, search.map { it.selected }, ::Pair)
                .distinctUntilChanged()
                .debounce(SEARCH_DEBOUNCE_MS)
                .collectLatest { (query, selected) ->
                    if (selected != null || query.trim().length < MIN_QUERY_LENGTH) {
                        search.update { it.copy(suggestions = emptyList(), failed = false) }
                        return@collectLatest
                    }
                    try {
                        val results = places.suggestions(query.trim(), lastKnownPosition())
                        search.update { it.copy(suggestions = results.take(MAX_SUGGESTIONS), failed = false) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Place search failed", e)
                        search.update { it.copy(suggestions = emptyList(), failed = true) }
                    }
                }
        }
    }

    /** Shows the Navigation SDK terms on [activity] first if they are not accepted yet. */
    fun initializeNavigation(activity: Activity?) = navigation.initialize(activity)

    fun onQueryChange(text: String) {
        query = text
        if (search.value.selected != null) search.update { it.copy(selected = null) }
    }

    fun select(suggestion: PlaceSuggestion) {
        places.endSession()
        query = suggestion.name
        search.value = SearchState(selected = suggestion)
    }

    fun clearSearch() {
        places.endSession()
        query = ""
        search.value = SearchState()
    }

    fun setTravelMode(mode: TravelMode) {
        if (mode == travelMode.mode.value) return
        travelMode.set(mode)
        voice.speak(mode.confirmationPhrase())
    }

    fun startNavigation(simulate: Boolean) {
        val destination = search.value.selected ?: return
        session.start(destination, simulate)
    }

    fun stopNavigation() {
        session.stop()
        clearSearch()
    }

    /** Callers make sure location permission is granted before enabling. */
    fun setAlertsEnabled(enabled: Boolean) = rideController.setAlertsEnabled(enabled)

    /**
     * Saves a reminder point at the current position. Only while standing still with a recent
     * fix, so a point is never added on the move or at a stale position.
     */
    fun addPointHere() {
        val location = lastKnownLocation()
        val ageMs = location?.let { (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000 }
        val moving = location != null && location.hasSpeed() && location.speed * 3.6 > MAX_ADD_POINT_SPEED_KMH
        if (location == null || ageMs == null || ageMs > MAX_FIX_AGE_MS || moving) {
            voice.speak("point_add_failed")
            return
        }
        viewModelScope.launch {
            userPoints.add(location.latitude, location.longitude, System.currentTimeMillis())
            voice.speak("point_added")
        }
    }

    private fun lastKnownPosition(): LatLng? = lastKnownLocation()?.let { LatLng(it.latitude, it.longitude) }

    /** The most recent last-known location from any provider. */
    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(): Location? {
        if (!context.hasLocationPermission()) return null
        val manager = context.getSystemService(LocationManager::class.java)
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }
        return providers
            .filter { manager.allProviders.contains(it) }
            .mapNotNull { manager.getLastKnownLocation(it) }
            .maxByOrNull { it.elapsedRealtimeNanos }
    }

    private companion object {
        const val TAG = "MapViewModel"
        const val SEARCH_DEBOUNCE_MS = 350L
        const val MIN_QUERY_LENGTH = 2
        const val MAX_SUGGESTIONS = 6
        const val MAX_FIX_AGE_MS = 30_000L
        const val MAX_ADD_POINT_SPEED_KMH = 5.0
    }
}
