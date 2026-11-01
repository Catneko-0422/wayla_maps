package cc.nekocat.waylamaps.navigation

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class TurnNavState { UNKNOWN, ENROUTE, REROUTING, STOPPED }

/** One turn-by-turn update from the Navigation SDK, stripped of SDK types. */
data class TurnUpdate(
    val state: TurnNavState,
    val stepNumber: Int?,
    /** A `turnbyturn.model.Maneuver` constant. */
    val maneuver: Int,
    val roundaboutExit: Int?,
    val distanceToStepM: Int?,
    /** The road the upcoming maneuver turns onto. */
    val roadName: String?,
    val instructionText: String?,
    /** To the final destination, for the trip estimate. */
    val remainingMeters: Int? = null,
    val remainingSeconds: Int? = null,
    /** The maneuver after this one, for "then turn left"; a `Maneuver` constant. */
    val nextManeuver: Int? = null,
    val nextRoundaboutExit: Int? = null,
    /** From this maneuver to the next one. */
    val nextStepGapM: Int? = null,
)

/** Latest turn-by-turn data, fed by [NavInfoReceiverService] and read by the ride service. */
@Singleton
class TurnByTurnFeed @Inject constructor() {
    private val _updates = MutableStateFlow<TurnUpdate?>(null)
    val updates: StateFlow<TurnUpdate?> = _updates.asStateFlow()

    private val _currentRoad = MutableStateFlow<String?>(null)

    /**
     * The road being travelled now: the road the previous step turned onto. Unknown (null) until
     * the first maneuver is passed.
     */
    val currentRoad: StateFlow<String?> = _currentRoad.asStateFlow()

    private val _icon = MutableStateFlow<Bitmap?>(null)

    /** The SDK's picture of the current maneuver, for the Android Auto turn card. */
    val icon: StateFlow<Bitmap?> = _icon.asStateFlow()

    fun publishIcon(bitmap: Bitmap?) {
        _icon.value = bitmap
    }

    fun publish(update: TurnUpdate) {
        val previous = _updates.value
        val previousStep = previous?.stepNumber
        val step = update.stepNumber
        if (previousStep != null && step != null && step > previousStep) _currentRoad.value = previous.roadName
        _updates.value = update
    }

    fun clear() {
        _updates.value = null
        _icon.value = null
        _currentRoad.value = null
    }
}
