package cc.nekocat.waylamaps.navigation

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import com.google.android.libraries.mapsplatform.turnbyturn.TurnByTurnManager
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavInfo
import com.google.android.libraries.mapsplatform.turnbyturn.model.NavState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Bound by the Navigation SDK (see [NavigationSession]) to deliver turn-by-turn data, about once
 * a second, which it forwards to [TurnByTurnFeed].
 */
@AndroidEntryPoint
class NavInfoReceiverService : Service() {
    @Inject lateinit var feed: TurnByTurnFeed

    private val turnByTurn = TurnByTurnManager.createInstance()

    private val messenger = Messenger(
        Handler(Looper.getMainLooper()) { message: Message ->
            if (message.what != TurnByTurnManager.MSG_NAV_INFO) return@Handler false
            turnByTurn.readNavInfoFromBundle(message.data)?.let { info ->
                feed.publishIcon(info.currentStep?.maneuverBitmap)
                feed.publish(info.toTurnUpdate())
            }
            true
        },
    )

    override fun onBind(intent: Intent?): IBinder = messenger.binder
}

private fun NavInfo.toTurnUpdate(): TurnUpdate {
    val step = currentStep
    val next = remainingSteps?.firstOrNull()
    return TurnUpdate(
        state = when (navState) {
            NavState.ENROUTE -> TurnNavState.ENROUTE
            NavState.REROUTING -> TurnNavState.REROUTING
            NavState.STOPPED -> TurnNavState.STOPPED
            else -> TurnNavState.UNKNOWN
        },
        stepNumber = step?.stepNumber,
        maneuver = step?.maneuver ?: 0,
        roundaboutExit = step?.roundaboutTurnNumber,
        distanceToStepM = distanceToCurrentStepMeters,
        roadName = step?.fullRoadName,
        instructionText = step?.fullInstructionText,
        remainingMeters = distanceToFinalDestinationMeters,
        remainingSeconds = timeToFinalDestinationSeconds,
        nextManeuver = next?.maneuver,
        nextRoundaboutExit = next?.roundaboutTurnNumber,
        nextStepGapM = next?.distanceFromPrevStepMeters,
        roads = (listOfNotNull(step) + remainingSteps.orEmpty()).mapNotNull { it.fullRoadName?.takeIf(::isRoadName) },
    )
}

/**
 * Whether the SDK's road text is a Taiwanese road name (中山路, 台1線, 國道1號) rather than the
 * instruction it puts there for unnamed roads (靠左).
 */
internal fun isRoadName(text: String): Boolean = ROAD_NAME.containsMatchIn(text.trim())

private val ROAD_NAME = Regex("[路街道段巷弄線橋號]$|[路街道]")
