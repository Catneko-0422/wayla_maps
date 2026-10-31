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
            turnByTurn.readNavInfoFromBundle(message.data)?.let { feed.publish(it.toTurnUpdate()) }
            true
        },
    )

    override fun onBind(intent: Intent?): IBinder = messenger.binder
}

private fun NavInfo.toTurnUpdate(): TurnUpdate {
    val step = currentStep
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
    )
}
