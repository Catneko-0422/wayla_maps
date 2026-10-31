package cc.nekocat.waylamaps.navigation

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import cc.nekocat.waylamaps.alerts.GeoPoint
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

    /** Guidance ended at the destination; held briefly so the arrival prompt can play. */
    data class Arrived(val destination: PlaceSuggestion) : GuidanceState
}

enum class RouteFailure { NO_ROUTE, NETWORK, LOCATION_UNKNOWN, QUOTA, NOT_READY, OTHER }

/**
 * Starts and stops turn-by-turn guidance on the shared [Navigator], and publishes the route and
 * the turn-by-turn feed while guiding.
 */
@Singleton
class NavigationSession @Inject constructor(
    private val application: Application,
    private val controller: NavigationController,
    private val travelMode: TravelModePreference,
    private val turnByTurn: TurnByTurnFeed,
) {
    private val _state = MutableStateFlow<GuidanceState>(GuidanceState.Idle)
    val state: StateFlow<GuidanceState> = _state.asStateFlow()

    private val _route = MutableStateFlow<List<GeoPoint>>(emptyList())

    /** The current route's polyline, empty when not guiding. Replaced on every reroute. */
    val route: StateFlow<List<GeoPoint>> = _route.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val arrivalListener = Navigator.ArrivalListener { event ->
        if (!event.isFinalDestination) return@ArrivalListener
        val guiding = _state.value as? GuidanceState.Guiding ?: return@ArrivalListener
        endGuidance()
        val arrived = GuidanceState.Arrived(guiding.destination)
        _state.value = arrived
        mainHandler.postDelayed({ if (_state.value == arrived) _state.value = GuidanceState.Idle }, ARRIVED_HOLD_MS)
    }
    private val routeChangedListener = Navigator.RouteChangedListener { refreshRoute() }

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
        navigator.setDestination(waypoint, RoutingOptions().travelMode(mode.sdkTravelMode).avoidHighways(mode.avoidHighways)) { description ->
            mainHandler.post { onRouteResult(navigator, destination, mode, simulate, description.routeStatus) }
        }
    }

    fun stop() {
        endGuidance()
        _state.value = GuidanceState.Idle
    }

    private fun endGuidance() {
        controller.navigator?.let { navigator ->
            navigator.removeArrivalListener(arrivalListener)
            navigator.removeRouteChangedListener(routeChangedListener)
            navigator.unregisterServiceForNavUpdates()
            navigator.stopGuidance()
            navigator.clearDestinations()
            navigator.simulator.unsetUserLocation()
        }
        turnByTurn.clear()
        _route.value = emptyList()
    }

    private fun refreshRoute() {
        val navigator = controller.navigator ?: return
        _route.value = navigator.routeSegments.flatMap { it.latLngs }.map { GeoPoint(it.latitude, it.longitude) }
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
        navigator.addRouteChangedListener(routeChangedListener)
        val registered = navigator.registerServiceForNavUpdates(
            application.packageName,
            NavInfoReceiverService::class.java.name,
            NEXT_STEPS_TO_PREVIEW,
        )
        if (!registered) Log.e(TAG, "Turn-by-turn feed unavailable; turns will not be announced")
        navigator.startGuidance()
        refreshRoute()
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
        const val NEXT_STEPS_TO_PREVIEW = 1
        const val ARRIVED_HOLD_MS = 10_000L
    }
}
