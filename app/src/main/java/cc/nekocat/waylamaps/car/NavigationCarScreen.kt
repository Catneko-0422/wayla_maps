package cc.nekocat.waylamaps.car

import android.graphics.Bitmap
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Alert
import androidx.car.app.model.CarIcon
import androidx.car.app.model.CarText
import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.model.Distance
import androidx.car.app.model.Header
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.versioning.CarAppApiLevels
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.lifecycleScope
import cc.nekocat.waylamaps.R
import cc.nekocat.waylamaps.alerts.AlertSession
import cc.nekocat.waylamaps.navigation.GuidanceState
import cc.nekocat.waylamaps.navigation.NavigatorState
import cc.nekocat.waylamaps.navigation.TurnUpdate
import cc.nekocat.waylamaps.voice.Priority
import cc.nekocat.waylamaps.voice.VoiceMode
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.time.ZonedDateTime
import kotlin.math.roundToInt

/**
 * The Android Auto main screen: the map, the current turn while navigating, the next speed
 * camera or enforcement point otherwise, and actions to search, toggle alerts or end the trip.
 * Spoken camera and traffic prompts also pop up as car alerts.
 */
class NavigationCarScreen(carContext: CarContext, private val deps: CarDependencies) : Screen(carContext) {
    private var navigator: NavigatorState = deps.controller.state.value
    private var guidance: GuidanceState = deps.navigation.state.value
    private var turn: TurnUpdate? = null
    private var icon: Bitmap? = null
    private var alerts: AlertSession = deps.alerts.session.value
    private var alertsEnabled = deps.rideController.alertsEnabled.value
    private var voiceMode = deps.ridePreferences.voiceMode.value
    private var alertId = 0

    private val navigationManager = carContext.getCarService(NavigationManager::class.java)

    init {
        navigationManager.setNavigationManagerCallback(object : NavigationManagerCallback {
            override fun onStopNavigation() = deps.navigation.stop()
        })
        lifecycleScope.launch {
            combine(
                combine(deps.controller.state, deps.navigation.state, ::Pair),
                combine(deps.turnByTurn.updates, deps.turnByTurn.icon, ::Pair),
                combine(deps.alerts.session, deps.rideController.alertsEnabled, ::Pair),
            ) { a, b, c -> Triple(a, b, c) }.collect { (nav, tbt, alert) ->
                val wasGuiding = guidance is GuidanceState.Guiding
                navigator = nav.first
                guidance = nav.second
                turn = tbt.first
                icon = tbt.second
                alerts = alert.first
                alertsEnabled = alert.second
                val guiding = guidance is GuidanceState.Guiding
                if (guiding && !wasGuiding) navigationManager.navigationStarted()
                if (!guiding && wasGuiding) navigationManager.navigationEnded()
                invalidate()
            }
        }
        lifecycleScope.launch {
            deps.ridePreferences.voiceMode.collect {
                voiceMode = it
                invalidate()
            }
        }
        lifecycleScope.launch {
            deps.speech.current.collect { speech ->
                if (speech == null || speech.priority == Priority.TURN || speech.priority == Priority.CHATTER) return@collect
                if (carContext.carAppApiLevel < CarAppApiLevels.LEVEL_5) return@collect
                carContext.getCarService(AppManager::class.java)
                    .showAlert(Alert.Builder(alertId++, CarText.create(speech.text), ALERT_MS).build())
            }
        }
        // Returning drivers have accepted the terms on the phone; start the navigator for the car.
        if (navigator is NavigatorState.NotInitialized && deps.controller.areTermsAccepted()) deps.controller.initialize()
    }

    override fun onGetTemplate(): Template {
        if (navigator !is NavigatorState.Ready) {
            val header = Header.Builder()
                .setTitle(carContext.getString(R.string.app_name))
                .setStartHeaderAction(Action.APP_ICON)
                .build()
            return MessageTemplate.Builder(carContext.getString(R.string.car_needs_phone)).setHeader(header).build()
        }
        val builder = NavigationTemplate.Builder()
            .setActionStrip(actions())
            .setMapActionStrip(ActionStrip.Builder().addAction(Action.PAN).build())
        val current = turn
        val currentIcon = icon
        when {
            guidance is GuidanceState.Guiding && current != null && currentIcon != null -> {
                builder.setNavigationInfo(routingInfo(current, currentIcon))
                travelEstimate(current)?.let(builder::setDestinationTravelEstimate)
            }
            guidance is GuidanceState.Guiding || guidance is GuidanceState.Routing ->
                builder.setNavigationInfo(RoutingInfo.Builder().setLoading(true).build())
            else -> builder.setNavigationInfo(MessageInfo.Builder(idleMessage()).build())
        }
        return builder.build()
    }

    private fun actions(): ActionStrip {
        val strip = ActionStrip.Builder()
        if (guidance is GuidanceState.Guiding) {
            // Same setting as the phone's voice button: everything, turns and cameras, or muted.
            strip.addAction(
                Action.Builder()
                    .setIcon(icon(voiceMode.iconRes()))
                    .setOnClickListener { deps.ridePreferences.setVoiceMode(voiceMode.next()) }
                    .build(),
            )
            strip.addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.navigation_stop))
                    .setOnClickListener { deps.navigation.stop() }
                    .build(),
            )
        } else {
            strip.addAction(
                Action.Builder()
                    .setTitle(carContext.getString(R.string.car_places))
                    .setOnClickListener { screenManager.push(PlacesCarScreen(carContext, deps)) }
                    .build(),
            )
            strip.addAction(
                Action.Builder()
                    .setIcon(icon(R.drawable.ic_search))
                    .setOnClickListener { screenManager.push(SearchCarScreen(carContext, deps)) }
                    .build(),
            )
            strip.addAction(
                Action.Builder()
                    .setTitle(carContext.getString(if (alertsEnabled) R.string.car_alerts_on else R.string.car_alerts_off))
                    .setOnClickListener { deps.rideController.setAlertsEnabled(!alertsEnabled) }
                    .build(),
            )
        }
        return strip.build()
    }

    private fun icon(res: Int) = CarIcon.Builder(IconCompat.createWithResource(carContext, res)).build()

    private fun VoiceMode.iconRes() = when (this) {
        VoiceMode.ALL -> R.drawable.ic_volume_up
        VoiceMode.ESSENTIAL -> R.drawable.ic_volume_down
        VoiceMode.MUTED -> R.drawable.ic_volume_off
    }

    private fun routingInfo(update: TurnUpdate, bitmap: Bitmap): RoutingInfo {
        val type = CarManeuvers.convert(update.maneuver, update.roundaboutExit)
        val maneuver = Maneuver.Builder(type.type)
            .setIcon(CarIcon.Builder(IconCompat.createWithBitmap(bitmap)).build())
            .apply { type.roundaboutExit?.let(::setRoundaboutExitNumber) }
            .build()
        val step = Step.Builder(update.instructionText ?: update.roadName.orEmpty())
            .setManeuver(maneuver)
            .apply { update.roadName?.let(::setRoad) }
            .build()
        return RoutingInfo.Builder().setCurrentStep(step, distance(update.distanceToStepM ?: 0)).build()
    }

    private fun travelEstimate(update: TurnUpdate): TravelEstimate? {
        val meters = update.remainingMeters ?: return null
        val seconds = update.remainingSeconds ?: return null
        val arrival = DateTimeWithZone.create(ZonedDateTime.now().plusSeconds(seconds.toLong()))
        return TravelEstimate.Builder(distance(meters), arrival).setRemainingTimeSeconds(seconds.toLong()).build()
    }

    private fun idleMessage(): String {
        alerts.next?.let { return carContext.getString(R.string.next_camera_distance, (it.distanceM / 10).roundToInt() * 10) }
        alerts.nextEnforcement?.let {
            return carContext.getString(R.string.next_enforcement_distance, (it.distanceM / 10).roundToInt() * 10)
        }
        return carContext.getString(if (alertsEnabled) R.string.no_camera_nearby else R.string.car_idle)
    }

    private fun distance(meters: Int): Distance =
        if (meters < 1000) {
            Distance.create(meters.toDouble(), Distance.UNIT_METERS)
        } else {
            Distance.create(meters / 1000.0, Distance.UNIT_KILOMETERS_P1)
        }

    private companion object {
        const val ALERT_MS = 5_000L
    }
}
