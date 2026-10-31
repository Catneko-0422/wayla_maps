package cc.nekocat.waylamaps.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import cc.nekocat.waylamaps.alerts.AlertSession
import cc.nekocat.waylamaps.alerts.AlertSessionState
import cc.nekocat.waylamaps.alerts.CameraAhead
import cc.nekocat.waylamaps.alerts.CameraAheadDetector
import cc.nekocat.waylamaps.alerts.CameraRoadFilter
import cc.nekocat.waylamaps.alerts.Fix
import cc.nekocat.waylamaps.alerts.RouteCameraMatcher
import cc.nekocat.waylamaps.data.SpeedCameraRepository
import cc.nekocat.waylamaps.navigation.GuidanceState
import cc.nekocat.waylamaps.navigation.NavigationSession
import cc.nekocat.waylamaps.navigation.TravelMode
import cc.nekocat.waylamaps.navigation.TravelModePreference
import cc.nekocat.waylamaps.navigation.TurnByTurnFeed
import cc.nekocat.waylamaps.navigation.TurnPrompt
import cc.nekocat.waylamaps.navigation.TurnPromptPlanner
import cc.nekocat.waylamaps.voice.PhraseCatalog
import cc.nekocat.waylamaps.voice.Priority
import cc.nekocat.waylamaps.voice.VoicePlayer
import com.google.android.libraries.navigation.NavigationApi
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.channels.Channel
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
    @Inject lateinit var sessionState: AlertSessionState
    @Inject lateinit var voicePlayer: VoicePlayer
    @Inject lateinit var phraseCatalog: PhraseCatalog
    @Inject lateinit var navigation: NavigationSession
    @Inject lateinit var rideController: RideController
    @Inject lateinit var notificationProvider: RideNotificationProvider
    @Inject lateinit var turnByTurn: TurnByTurnFeed
    @Inject lateinit var travelMode: TravelModePreference

    private val detector = CameraAheadDetector()
    private val routeMatcher = RouteCameraMatcher()
    private val turnPlanner by lazy {
        TurnPromptPlanner(phraseCatalog.slots.getValue("distance").map { it.toInt() })
    }
    private var speedMps: Double? = null
    private val locations = Channel<Location>(Channel.CONFLATED)
    private var locationSource: LocationSource? = null
    private var started = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // Every start, even a stop request, must go foreground: it may come from startForegroundService.
        start()
        if (intent?.action == ACTION_STOP) rideController.stopAll()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
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
        lifecycleScope.launch {
            navigation.route.collect { routeMatcher.setRoute(it) }
        }
        lifecycleScope.launch {
            turnByTurn.updates.filterNotNull().collect { update ->
                Log.d(TAG, "Turn feed $update")
                turnPlanner.next(update, speedMps)?.let { speakTurn(it) }
            }
        }
        lifecycleScope.launch {
            var previous = navigation.state.value
            navigation.state.collect { state ->
                if (state !is GuidanceState.Guiding) turnPlanner.reset()
                if (state is GuidanceState.Arrived && previous is GuidanceState.Guiding) voicePlayer.speak("arrived")
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
        val fix = Fix(
            lat = location.latitude,
            lng = location.longitude,
            bearingDeg = if (location.hasBearing()) location.bearing.toDouble() else null,
            speedMps = if (location.hasSpeed()) location.speed.toDouble() else null,
        )
        speedMps = fix.speedMps
        val guiding = navigation.state.value as? GuidanceState.Guiding
        val scooter = (guiding?.travelMode ?: travelMode.mode.value) == TravelMode.SCOOTER
        val currentRoad = guiding?.let { turnByTurn.currentRoad.value }
        val candidates = repository.camerasNear(fix.lat, fix.lng, SEARCH_RADIUS_M)
            .filter { CameraRoadFilter.isRelevant(it, scooter, currentRoad) }
        val result = if (guiding != null && routeMatcher.hasRoute) {
            routeMatcher.update(fix, candidates)
        } else {
            detector.update(fix, candidates)
        }

        sessionState.update {
            it.copy(
                hasFix = true,
                speedKmh = fix.speedMps?.let { mps -> (mps * 3.6).roundToInt() },
                next = result.next,
            )
        }
        result.announce?.let { announce(it) }
    }

    private fun announce(ahead: CameraAhead) {
        val distance = CameraAheadDetector.spokenDistanceMeters(ahead.distanceM)
        val limit = ahead.camera.limitKmh
        // A limit without a recorded fragment (new data) still gets the distance announced.
        if (limit != null && limit.toString() in phraseCatalog.slots["limit"].orEmpty()) {
            voicePlayer.speak("speed_camera_ahead_limit", mapOf("distance" to distance, "limit" to limit))
        } else {
            voicePlayer.speak("speed_camera_ahead", mapOf("distance" to distance))
        }
    }

    private fun speakTurn(prompt: TurnPrompt) {
        Log.i(TAG, "Turn prompt $prompt at ${speedMps?.let { (it * 3.6).roundToInt() }} km/h")
        when (prompt) {
            is TurnPrompt.Ahead ->
                voicePlayer.speak("turn_ahead", mapOf("distance" to prompt.distanceM, "maneuver" to prompt.maneuverFragment))
            is TurnPrompt.Now -> voicePlayer.speak("turn_now", mapOf("maneuver" to prompt.maneuverFragment))
            is TurnPrompt.Instruction -> voicePlayer.speakText("turn_text", prompt.text, Priority.TURN)
        }
    }

    companion object {
        private const val TAG = "RideService"
        private const val ACTION_STOP = "cc.nekocat.waylamaps.action.STOP_RIDE"
        private const val SEARCH_RADIUS_M = 1_000.0

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
