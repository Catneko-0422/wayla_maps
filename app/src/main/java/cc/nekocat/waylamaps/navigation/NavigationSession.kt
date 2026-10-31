package cc.nekocat.waylamaps.navigation

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.RoutingOptions
import com.google.android.libraries.navigation.SimulationOptions
import com.google.android.libraries.navigation.Waypoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed interface GuidanceState {
    data object Idle : GuidanceState
    data class Routing(val destination: PlaceSuggestion) : GuidanceState
    data class Guiding(val destination: PlaceSuggestion, val travelMode: TravelMode, val simulated: Boolean) : GuidanceState
    data class Failed(val destination: PlaceSuggestion, val reason: RouteFailure) : GuidanceState
}

enum class RouteFailure { NO_ROUTE, NETWORK, LOCATION_UNKNOWN, QUOTA, NOT_READY, OTHER }

/** Starts and stops turn-by-turn guidance on the shared [Navigator]. */
@Singleton
class NavigationSession @Inject constructor(
    private val controller: NavigationController,
    private val travelMode: TravelModePreference,
) {
    private val _state = MutableStateFlow<GuidanceState>(GuidanceState.Idle)
    val state: StateFlow<GuidanceState> = _state.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val arrivalListener = Navigator.ArrivalListener { event ->
        // Stage 3 announces arrival before this; for now guidance simply ends.
        if (event.isFinalDestination) stop()
    }

    /** Routes to [destination] with the current travel mode and starts guidance. */
    fun start(destination: PlaceSuggestion, simulate: Boolean = false) {
        val navigator = controller.navigator
        if (navigator == null) {
            _state.value = GuidanceState.Failed(destination, RouteFailure.NOT_READY)
            return
        }
        val waypoint = try {
            Waypoint.builder().setPlaceIdString(destination.placeId).setTitle(destination.name).build()
        } catch (e: Waypoint.UnsupportedPlaceIdException) {
            Log.w(TAG, "Place ID not usable for navigation: ${destination.placeId}", e)
            _state.value = GuidanceState.Failed(destination, RouteFailure.OTHER)
            return
        }
        val mode = travelMode.mode.value
        _state.value = GuidanceState.Routing(destination)
        navigator.setDestination(waypoint, RoutingOptions().travelMode(mode.sdkTravelMode)) { description ->
            mainHandler.post { onRouteResult(navigator, destination, mode, simulate, description.routeStatus) }
        }
    }

    fun stop() {
        controller.navigator?.let { navigator ->
            navigator.removeArrivalListener(arrivalListener)
            navigator.stopGuidance()
            navigator.clearDestinations()
            navigator.simulator.unsetUserLocation()
        }
        _state.value = GuidanceState.Idle
    }

    private fun onRouteResult(
        navigator: Navigator,
        destination: PlaceSuggestion,
        mode: TravelMode,
        simulate: Boolean,
        status: Navigator.RouteStatus,
    ) {
        // A newer request or a stop() superseded this one.
        if (_state.value != GuidanceState.Routing(destination)) return
        Log.i(TAG, "Route to ${destination.name} ($mode): $status")
        if (status != Navigator.RouteStatus.OK) {
            _state.value = GuidanceState.Failed(destination, status.toFailure())
            return
        }
        navigator.addArrivalListener(arrivalListener)
        navigator.startGuidance()
        if (simulate) {
            navigator.simulator.simulateLocationsAlongExistingRoute(SimulationOptions().speedMultiplier(SIMULATION_SPEED))
        }
        _state.value = GuidanceState.Guiding(destination, mode, simulate)
    }

    private fun Navigator.RouteStatus.toFailure() = when (this) {
        Navigator.RouteStatus.NO_ROUTE_FOUND -> RouteFailure.NO_ROUTE
        Navigator.RouteStatus.NETWORK_ERROR, Navigator.RouteStatus.ROUTING_TIMEOUT -> RouteFailure.NETWORK
        Navigator.RouteStatus.LOCATION_DISABLED, Navigator.RouteStatus.LOCATION_UNKNOWN -> RouteFailure.LOCATION_UNKNOWN
        Navigator.RouteStatus.QUOTA_CHECK_FAILED -> RouteFailure.QUOTA
        else -> RouteFailure.OTHER
    }

    private companion object {
        const val TAG = "NavigationSession"
        const val SIMULATION_SPEED = 1.5f
    }
}
