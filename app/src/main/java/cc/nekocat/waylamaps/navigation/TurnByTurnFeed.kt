package cc.nekocat.waylamaps.navigation

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

    fun publish(update: TurnUpdate) {
        val previous = _updates.value
        val previousStep = previous?.stepNumber
        val step = update.stepNumber
        if (previousStep != null && step != null && step > previousStep) _currentRoad.value = previous.roadName
        _updates.value = update
    }

    fun clear() {
        _updates.value = null
        _currentRoad.value = null
    }
}
