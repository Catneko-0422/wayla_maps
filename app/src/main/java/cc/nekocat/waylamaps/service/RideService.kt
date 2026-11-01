package cc.nekocat.waylamaps.service

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import cc.nekocat.waylamaps.alerts.Ahead
import cc.nekocat.waylamaps.alerts.AlertSession
import cc.nekocat.waylamaps.alerts.AverageSpeedTracker
import cc.nekocat.waylamaps.alerts.AverageSpeedZones
import cc.nekocat.waylamaps.alerts.AlertSessionState
import cc.nekocat.waylamaps.alerts.CameraAhead
import cc.nekocat.waylamaps.alerts.CameraAheadDetector
import cc.nekocat.waylamaps.alerts.CameraDescriber
import cc.nekocat.waylamaps.alerts.CameraRoadFilter
import cc.nekocat.waylamaps.alerts.EnforcementKind
import cc.nekocat.waylamaps.alerts.EnforcementPoint
import cc.nekocat.waylamaps.alerts.Fix
import cc.nekocat.waylamaps.alerts.Geo
import cc.nekocat.waylamaps.alerts.GeoPoint
import cc.nekocat.waylamaps.alerts.NearbyOnceNotifier
import cc.nekocat.waylamaps.alerts.OverLimitWarner
import cc.nekocat.waylamaps.alerts.AlertCountdown
import cc.nekocat.waylamaps.alerts.AlertDescription
import cc.nekocat.waylamaps.alerts.RouteCameraMatcher
import cc.nekocat.waylamaps.alerts.speedBetween
import cc.nekocat.waylamaps.alerts.camera
import cc.nekocat.waylamaps.data.SpeedCameraRepository
import cc.nekocat.waylamaps.data.UserPointRepository
import cc.nekocat.waylamaps.data.enforcement.EnforcementRepository
import cc.nekocat.waylamaps.navigation.GuidanceState
import cc.nekocat.waylamaps.navigation.NavigationSession
import cc.nekocat.waylamaps.navigation.RouteFailure
import cc.nekocat.waylamaps.navigation.TravelModePreference
import cc.nekocat.waylamaps.navigation.TurnByTurnFeed
import cc.nekocat.waylamaps.navigation.TurnPrompt
import cc.nekocat.waylamaps.navigation.TurnPromptPlanner
import cc.nekocat.waylamaps.voice.AlertPhrases
import cc.nekocat.waylamaps.voice.PhraseCatalog
import cc.nekocat.waylamaps.voice.Priority
import cc.nekocat.waylamaps.voice.VoicePlayer
import com.google.android.libraries.navigation.NavigationApi
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Foreground service for a ride: follows the position, calls out turns and speed cameras ahead,
 * and keeps the process alive (screen off included) while navigating. Started and stopped by
 * [RideController].
 *
 * Uses raw GPS when not navigating and the Navigation SDK's road-snapped positions while
 * navigating. While navigating, only cameras on the route count. Its notification is shared with
 * the SDK's own foreground service.
 */
@AndroidEntryPoint
class RideService : LifecycleService() {
    @Inject lateinit var repository: SpeedCameraRepository
    @Inject lateinit var enforcementRepository: EnforcementRepository
    @Inject lateinit var userPoints: UserPointRepository
    @Inject lateinit var sessionState: AlertSessionState
    @Inject lateinit var voicePlayer: VoicePlayer
    @Inject lateinit var phraseCatalog: PhraseCatalog
    @Inject lateinit var navigation: NavigationSession
    @Inject lateinit var rideController: RideController
    @Inject lateinit var notificationProvider: RideNotificationProvider
    @Inject lateinit var turnByTurn: TurnByTurnFeed
    @Inject lateinit var travelMode: TravelModePreference
    @Inject lateinit var ridePreferences: RidePreferences

    private val detector = CameraAheadDetector()
    private val routeMatcher = RouteCameraMatcher()
    // Speed cameras count down 400 to 50 m. Enforcement intersections are many in cities, and
    // the rider's own points are reminders, so they are said less often.
    private val enforcementDetector = CameraAheadDetector(countdownM = SHORT_COUNTDOWN_M)
    private val enforcementRouteMatcher = RouteCameraMatcher(countdownM = SHORT_COUNTDOWN_M)
    private val userPointDetector = CameraAheadDetector(countdownM = SHORT_COUNTDOWN_M)
    private val userPointRouteMatcher = RouteCameraMatcher(countdownM = SHORT_COUNTDOWN_M)
    private val overLimit = OverLimitWarner()
    private val averageSpeed = AverageSpeedTracker()
    private var lastFix: Fix? = null
    private var lastPosition: Pair<GeoPoint, Long>? = null
    private val parkingZones = NearbyOnceNotifier()
    private val announcedCameraIds = mutableSetOf<Long>()

    /** Where speed cameras were announced this ride, so an enforcement point there is not repeated. */
    private val announcedCameraSpots = ArrayDeque<GeoPoint>()
    private val turnPlanner by lazy {
        TurnPromptPlanner(
            phraseCatalog.slots.getValue("distance").map { it.toInt() },
            kmSlots = phraseCatalog.slots["km"].orEmpty().map { it.toInt() },
        )
    }
    private var speedMps: Double? = null
    private val locations = Channel<Location>(Channel.CONFLATED)
    private var locationSource: LocationSource? = null
    private var started = false
    private val statusMonitor = RideStatusMonitor()
    private var gpsEnabled = true
    private var systemReceiver: BroadcastReceiver? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Every start, even a stop request, must go foreground: it may come from startForegroundService.
        start()
        if (intent?.action == ACTION_STOP) rideController.stopAll()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        systemReceiver?.let { unregisterReceiver(it) }
        locationSource?.stop()
        if (started) runCatching { NavigationApi.getForegroundServiceManager(application).stopForeground(this) }
        voicePlayer.release()
        sessionState.update { AlertSession() }
        super.onDestroy()
    }

    private fun start() {
        if (started) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Location permission missing, not starting")
            rideController.stopAll()
            stopSelf()
            return
        }
        started = true
        goForeground()
        sessionState.update { it.copy(running = true) }

        lifecycleScope.launch {
            navigation.state.map { it is GuidanceState.Guiding }.distinctUntilChanged().collect { guiding ->
                locationSource?.stop()
                locationSource = (if (guiding) RoadSnappedLocationSource(application) else GpsLocationSource(application))
                    .also { it.start { location -> locations.trySend(location) } }
            }
        }
        lifecycleScope.launch {
            locations.consumeAsFlow().collect { onLocation(it) }
        }
        watchStatus()
        lifecycleScope.launch {
            navigation.route.collect {
                routeMatcher.setRoute(it)
                enforcementRouteMatcher.setRoute(it)
                userPointRouteMatcher.setRoute(it)
            }
        }
        lifecycleScope.launch {
            turnByTurn.updates.filterNotNull().collect { update ->
                Log.d(TAG, "Turn feed $update")
                turnPlanner.next(update, speedMps)?.let { speakTurn(it) }
            }
        }
        lifecycleScope.launch {
            // Off the route: said as the new route is worked out, so a missed turn is not met with silence.
            navigation.reroutes.collect { voicePlayer.speak("rerouting") }
        }
        lifecycleScope.launch {
            var previous = navigation.state.value
            navigation.state.collect { state ->
                if (state !is GuidanceState.Guiding) turnPlanner.reset()
                if (state is GuidanceState.Arrived && previous is GuidanceState.Guiding) voicePlayer.speak("arrived")
                if (state is GuidanceState.Guiding && previous !is GuidanceState.Guiding) voicePlayer.speak("guidance_start")
                val before = previous
                if (state is GuidanceState.Guiding && before is GuidanceState.Guiding && state.reached > before.reached) {
                    voicePlayer.speak("stop_reached")
                }
                previous = state
            }
        }
    }

    /** Goes foreground with the notification shared with the Navigation SDK. */
    private fun goForeground() {
        try {
            NavigationApi.getForegroundServiceManager(application).startForeground(this)
        } catch (e: Exception) {
            Log.w(TAG, "Shared foreground notification unavailable, using our own", e)
            ServiceCompat.startForeground(
                this,
                RideNotificationProvider.NOTIFICATION_ID,
                notificationProvider.notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
            )
        }
    }

    private suspend fun onLocation(location: Location) {
        val here = GeoPoint(location.latitude, location.longitude)
        val previous = lastPosition
        lastPosition = here to location.elapsedRealtimeNanos
        val speed = if (location.hasSpeed()) {
            location.speed.toDouble()
        } else {
            previous?.let { (point, nanos) -> speedBetween(point, nanos, here, location.elapsedRealtimeNanos) }
        }
        val fix = Fix(
            lat = location.latitude,
            lng = location.longitude,
            bearingDeg = if (location.hasBearing()) location.bearing.toDouble() else null,
            speedMps = speed,
        )
        speedMps = fix.speedMps
        statusMonitor.onFix(SystemClock.elapsedRealtime(), fix)?.let { speakStatus(it) }
        val guiding = navigation.state.value as? GuidanceState.Guiding
        val access = (guiding?.travelMode ?: travelMode.mode.value).roadAccess
        val matchElevation = ridePreferences.matchElevation.value
        val currentRoad = guiding?.let { turnByTurn.currentRoad.value }
        val onRoute = guiding != null && routeMatcher.hasRoute
        val cameras = repository.camerasNear(fix.lat, fix.lng, SEARCH_RADIUS_M)
            .filter { CameraRoadFilter.isRelevant(it.address, access, currentRoad, matchElevation) }
        val result = if (onRoute) routeMatcher.update(fix, cameras) else detector.update(fix, cameras)
        val points = enforcementRepository.pointsNear(fix.lat, fix.lng, SEARCH_RADIUS_M)
            .filter { CameraRoadFilter.isRelevant(it.location, access, currentRoad, matchElevation) }
        val enforcement = if (onRoute) {
            enforcementRouteMatcher.update(fix, points)
        } else {
            enforcementDetector.update(fix, points)
        }

        // The rider's own points: always spoken, whatever the vehicle or road level.
        val own = userPoints.pointsNear(fix.lat, fix.lng, SEARCH_RADIUS_M)
        val ownResult = if (onRoute) userPointRouteMatcher.update(fix, own) else userPointDetector.update(fix, own)

        val now = SystemClock.elapsedRealtime()
        result.passed?.takeIf { AverageSpeedZones.isZone(it) }?.let { start ->
            averageSpeed.enter(now, start.limitKmh, AverageSpeedZones.lengthMeters(start.address))
        }
        val moved = lastFix?.let { Geo.distanceMeters(it.lat, it.lng, fix.lat, fix.lng) } ?: 0.0
        lastFix = fix
        // A jump this large is a GPS glitch or a gap, not distance ridden.
        if (moved < MAX_STEP_M && averageSpeed.onMove(now, moved)) voicePlayer.speak("average_speed_exit")

        val speedKmh = fix.speedMps?.let { it * 3.6 }
        sessionState.update {
            it.copy(
                hasFix = true,
                speedKmh = speedKmh?.roundToInt(),
                next = result.next,
                nextEnforcement = enforcement.next,
                averageZone = averageSpeed.zone,
            )
        }
        val roadKnown = currentRoad != null
        result.announce?.let { announce(it, result.step, roadKnown) }
        // Enforcement points are spoken only while navigating; otherwise they are just shown.
        if (guiding != null) enforcement.announce?.let { announceEnforcement(it, enforcement.step, roadKnown) }
        ownResult.announce?.let {
            val description = AlertDescription("type_user_point")
            voicePlayer.speak(AlertPhrases.ahead(description, ownResult.step, it.distanceM, null, limitSlots))
        }
        if (result.passed != null || ownResult.passed != null || (guiding != null && enforcement.passed != null)) {
            voicePlayer.speak("alert_passed")
        }
        result.next?.let { next ->
            if (next.camera.id in announcedCameraIds &&
                overLimit.shouldWarn(next.camera.id, next.distanceM, next.camera.limitKmh, speedKmh)
            ) {
                voicePlayer.speak("over_limit")
            }
        }
        val parking = enforcementRepository.pointsNear(fix.lat, fix.lng, PARKING_SEARCH_M, setOf(EnforcementKind.PARKING))
        parkingZones.update(fix, parking)?.let { voicePlayer.speak("parking_zone") }
    }

    /** Says out loud what the rider would otherwise not notice: GPS trouble, sleep, standing still. */
    private fun watchStatus() {
        statusMonitor.start(SystemClock.elapsedRealtime())
        gpsEnabled = getSystemService(LocationManager::class.java).isProviderEnabled(LocationManager.GPS_PROVIDER)
        if (!gpsEnabled) voicePlayer.speak("gps_disabled")

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    LocationManager.PROVIDERS_CHANGED_ACTION -> {
                        val enabled = getSystemService(LocationManager::class.java)
                            .isProviderEnabled(LocationManager.GPS_PROVIDER)
                        if (enabled != gpsEnabled) {
                            gpsEnabled = enabled
                            // Time without fixes while GPS was off is not a lost signal.
                            statusMonitor.start(SystemClock.elapsedRealtime())
                            voicePlayer.speak(if (enabled) "gps_enabled" else "gps_disabled")
                        }
                    }
                    PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                        val idle = getSystemService(PowerManager::class.java).isDeviceIdleMode
                        voicePlayer.speak(if (idle) "doze_begin" else "doze_end")
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        }
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        systemReceiver = receiver

        lifecycleScope.launch {
            while (true) {
                delay(STATUS_TICK_MS)
                val alertsOnly = navigation.state.value !is GuidanceState.Guiding
                // With GPS switched off, "disabled" has been said; "no fix" or "lost" would only repeat it.
                if (!gpsEnabled) continue
                statusMonitor.onTick(SystemClock.elapsedRealtime(), alertsOnly)?.let { speakStatus(it) }
            }
        }
        lifecycleScope.launch {
            // The current value is how the service was started: say so only for the alerts.
            if (rideController.alertsEnabled.value) voicePlayer.speak("detect_start")
            rideController.alertsEnabled.drop(1).collect { enabled ->
                voicePlayer.speak(if (enabled) "detect_start" else "detect_stop")
            }
        }
        lifecycleScope.launch {
            navigation.state.collect { state ->
                if (state is GuidanceState.Failed && state.reason == RouteFailure.NETWORK) voicePlayer.speak("no_internet")
            }
        }
    }

    private fun speakStatus(event: RideStatusEvent) {
        Log.i(TAG, "Ride status: $event")
        voicePlayer.speak(
            when (event) {
                RideStatusEvent.NO_FIX_YET -> "no_fix"
                RideStatusEvent.SIGNAL_LOST -> "gps_lost"
                RideStatusEvent.SIGNAL_RECOVERED -> "gps_recovered"
                RideStatusEvent.STATIONARY_LONG -> "stationary"
            },
        )
    }

    private fun announce(ahead: CameraAhead, step: AlertCountdown.Step?, roadKnown: Boolean) {
        val first = step?.first ?: true
        if (first) {
            announcedCameraIds += ahead.camera.id
            announcedCameraSpots.addLast(GeoPoint(ahead.camera.lat, ahead.camera.lng))
            if (announcedCameraSpots.size > MAX_REMEMBERED_SPOTS) announcedCameraSpots.removeFirst()
        }
        val description = CameraDescriber.describe(ahead.camera, roadKnown)
        voicePlayer.speak(AlertPhrases.ahead(description, step, ahead.distanceM, ahead.camera.limitKmh, limitSlots))
        if (first && AverageSpeedZones.isZone(ahead.camera) && "隧道" in ahead.camera.address) {
            voicePlayer.speak("average_speed_tunnel")
        }
    }

    private fun announceEnforcement(ahead: Ahead<EnforcementPoint>, step: AlertCountdown.Step?, roadKnown: Boolean) {
        val point = ahead.point
        if (announcedCameraSpots.any { Geo.distanceMeters(it.lat, it.lng, point.lat, point.lng) <= SAME_SPOT_M }) return
        val description = CameraDescriber.describe(point, roadKnown)
        voicePlayer.speak(AlertPhrases.ahead(description, step, ahead.distanceM, point.limitKmh, limitSlots))
    }

    private val limitSlots by lazy { phraseCatalog.slots["limit"].orEmpty() }

    private fun speakTurn(prompt: TurnPrompt) {
        Log.i(TAG, "Turn prompt $prompt at ${speedMps?.let { (it * 3.6).roundToInt() }} km/h")
        when (prompt) {
            is TurnPrompt.Ahead -> {
                val args = mapOf("distance" to prompt.distanceM, "maneuver" to prompt.maneuverFragment)
                if (prompt.then == null) {
                    voicePlayer.speak("turn_ahead", args)
                } else {
                    voicePlayer.speak("turn_ahead_then", args + ("next" to prompt.then))
                }
            }
            is TurnPrompt.Now -> if (prompt.then == null) {
                voicePlayer.speak("turn_now", mapOf("maneuver" to prompt.maneuverFragment))
            } else {
                voicePlayer.speak("turn_now_then", mapOf("maneuver" to prompt.maneuverFragment, "next" to prompt.then))
            }
            is TurnPrompt.Continue -> voicePlayer.speak("follow_road", mapOf("km" to prompt.km))
            is TurnPrompt.Instruction -> voicePlayer.speakText("turn_text", prompt.text, Priority.TURN)
        }
    }

    companion object {
        private const val TAG = "RideService"
        private const val ACTION_STOP = "cc.nekocat.waylamaps.action.STOP_RIDE"
        private const val SEARCH_RADIUS_M = 1_000.0
        private val SHORT_COUNTDOWN_M = listOf(300, 100)

        /** An enforcement point this close to an announced speed camera is the same spot. */
        private const val SAME_SPOT_M = 50.0
        private const val MAX_REMEMBERED_SPOTS = 20
        private const val PARKING_SEARCH_M = 200.0
        private const val STATUS_TICK_MS = 1_000L
        private const val MAX_STEP_M = 200.0

        /** Only from the foreground (a user action), as Android requires for foreground services. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, RideService::class.java))
        }

        /** Safe from anywhere, unlike startService(), which background apps may not call. */
        fun stop(context: Context) {
            context.stopService(Intent(context, RideService::class.java))
        }

        /** For the notification's stop action. */
        fun stopIntent(context: Context): Intent = Intent(context, RideService::class.java).setAction(ACTION_STOP)
    }
}
