package cc.nekocat.waylamaps.navigation

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import cc.nekocat.waylamaps.alerts.GeoPoint
import com.google.android.libraries.navigation.NavigationUpdatesOptions
import com.google.android.libraries.navigation.Navigator
import com.google.android.libraries.navigation.RoutingOptions
import com.google.android.libraries.navigation.SimulationOptions
import com.google.android.libraries.navigation.TimeAndDistance
import com.google.android.libraries.navigation.Waypoint
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed interface GuidanceState {
    data object Idle : GuidanceState
    data class Routing(val trip: Trip) : GuidanceState {
        val destination: PlaceSuggestion get() = trip.destination
    }

    /** A route is ready and shown on the map; guidance starts once the rider confirms. */
    data class Previewing(
        val trip: Trip,
        val travelMode: TravelMode,
        val seconds: Int,
        val meters: Int,
        /** One of `TimeAndDistance.DELAY_*`: traffic on the route. */
        val delaySeverity: Int,
        /** Time to reach each waypoint from here, in the order of [Trip.waypoints]. */
        val waypointSeconds: List<Int> = emptyList(),
    ) : GuidanceState {
        val destination: PlaceSuggestion get() = trip.destination
    }

    data class Guiding(
        val trip: Trip,
        val travelMode: TravelMode,
        val simulated: Boolean,
        /** How many of the trip's waypoints have been reached; stops come first. */
        val reached: Int = 0,
    ) : GuidanceState {
        val destination: PlaceSuggestion get() = trip.destination

        /** The waypoint being ridden to now. */
        val next: PlaceSuggestion get() = trip.waypoints[reached.coerceAtMost(trip.stops.size)]

        val stopsLeft: Int get() = (trip.stops.size - reached).coerceAtLeast(0)
    }

    data class Failed(val trip: Trip, val reason: RouteFailure) : GuidanceState {
        val destination: PlaceSuggestion get() = trip.destination
    }

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

    private val _reroutes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Fires when the rider leaves the route and a new one is being worked out. */
    val reroutes: SharedFlow<Unit> = _reroutes.asSharedFlow()
    private val reroutingListener = Navigator.ReroutingListener { _reroutes.tryEmit(Unit) }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val arrivalListener = Navigator.ArrivalListener { event ->
        val guiding = _state.value as? GuidanceState.Guiding ?: return@ArrivalListener
        if (!event.isFinalDestination) {
            // A stop on the way: carry straight on to the next one, so the rider never has to
            // touch the phone to continue.
            val navigator = controller.navigator ?: return@ArrivalListener
            navigator.continueToNextDestination()
            navigator.silenceSdkVoice()
            navigator.startGuidance()
            if (guiding.simulated) {
                navigator.simulator.simulateLocationsAlongExistingRoute(SimulationOptions().speedMultiplier(SIMULATION_SPEED))
            }
            refreshRoute()
            _state.value = guiding.copy(reached = guiding.reached + 1)
            return@ArrivalListener
        }
        endGuidance()
        val arrived = GuidanceState.Arrived(guiding.destination)
        _state.value = arrived
        mainHandler.postDelayed({ if (_state.value == arrived) _state.value = GuidanceState.Idle }, ARRIVED_HOLD_MS)
    }
    private val routeChangedListener = Navigator.RouteChangedListener { refreshRoute() }

    /** Routes the [trip] with the current travel mode and starts guidance straight away. */
    fun start(trip: Trip, simulate: Boolean = false) = route(trip, startNow = true, simulate)

    fun start(destination: PlaceSuggestion, simulate: Boolean = false) = start(Trip(destination), simulate)

    /** Routes the [trip] and shows the route; [confirm] starts guidance. */
    fun preview(trip: Trip) = route(trip, startNow = false, simulate = false)

    /** Starts guidance on the previewed route. */
    fun confirm(simulate: Boolean = false) {
        val preview = _state.value as? GuidanceState.Previewing ?: return
        val navigator = controller.navigator ?: return
        beginGuidance(navigator, preview.trip, preview.travelMode, simulate)
    }

    private fun route(trip: Trip, startNow: Boolean, simulate: Boolean) {
        val navigator = controller.navigator
        if (navigator == null) {
            _state.value = GuidanceState.Failed(trip, RouteFailure.NOT_READY)
            return
        }
        val waypoints = try {
            trip.waypoints.map { Waypoint.builder().setPlaceIdString(it.placeId).setTitle(it.name).build() }
        } catch (e: Waypoint.UnsupportedPlaceIdException) {
            Log.w(TAG, "Place ID not usable for navigation", e)
            _state.value = GuidanceState.Failed(trip, RouteFailure.OTHER)
            return
        }
        val mode = travelMode.mode.value
        _state.value = GuidanceState.Routing(trip)
        // The vehicle's restrictions (no expressways on a scooter) apply to every leg.
        val options = RoutingOptions().travelMode(mode.sdkTravelMode).avoidHighways(mode.avoidHighways)
        navigator.setDestinations(waypoints, options) { description ->
            mainHandler.post { onRouteResult(navigator, trip, mode, startNow, simulate, description.routeStatus) }
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
            navigator.removeReroutingListener(reroutingListener)
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
        trip: Trip,
        mode: TravelMode,
        startNow: Boolean,
        simulate: Boolean,
        status: Navigator.RouteStatus,
    ) {
        // A newer request or a stop() superseded this one.
        if (_state.value != GuidanceState.Routing(trip)) return
        Log.i(TAG, "Route to ${trip.destination.name} via ${trip.stops.size} stops ($mode): $status")
        if (status != Navigator.RouteStatus.OK) {
            _state.value = GuidanceState.Failed(trip, status.toFailure())
            return
        }
        if (startNow) {
            beginGuidance(navigator, trip, mode, simulate)
        } else {
            // One entry per waypoint; the last is the whole trip.
            val estimates = navigator.timeAndDistanceList.orEmpty()
            val total = estimates.lastOrNull() ?: navigator.currentTimeAndDistance
            _state.value = GuidanceState.Previewing(
                trip,
                mode,
                seconds = total?.seconds ?: 0,
                meters = total?.meters ?: 0,
                delaySeverity = total?.delaySeverity ?: TimeAndDistance.DELAY_NODATA,
                waypointSeconds = estimates.map { it.seconds },
            )
            refreshRoute()
        }
    }

    private fun beginGuidance(navigator: Navigator, trip: Trip, mode: TravelMode, simulate: Boolean) {
        navigator.addArrivalListener(arrivalListener)
        navigator.addRouteChangedListener(routeChangedListener)
        navigator.addReroutingListener(reroutingListener)
        // Maneuver pictures are generated for the Android Auto turn card.
        val options = NavigationUpdatesOptions.Builder()
            .setNumNextStepsToPreview(NEXT_STEPS_TO_PREVIEW)
            .setGeneratedStepImagesType(NavigationUpdatesOptions.GeneratedStepImagesType.BITMAP)
            .setDisplayMetrics(application.resources.displayMetrics)
            .build()
        val registered = navigator.registerServiceForNavUpdates(
            application.packageName,
            NavInfoReceiverService::class.java.name,
            options,
        )
        if (!registered) Log.e(TAG, "Turn-by-turn feed unavailable; turns will not be announced")
        navigator.silenceSdkVoice()
        navigator.startGuidance()
        refreshRoute()
        if (simulate) {
            navigator.simulator.simulateLocationsAlongExistingRoute(SimulationOptions().speedMultiplier(SIMULATION_SPEED))
        }
        _state.value = GuidanceState.Guiding(trip, mode, simulate)
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
