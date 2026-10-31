package cc.nekocat.waylamaps.navigation

import kotlin.math.abs
import kotlin.math.max

sealed interface TurnPrompt {
    /** "In [distanceM] meters, <maneuver>": `turn_ahead`. */
    data class Ahead(val maneuverFragment: String, val distanceM: Int) : TurnPrompt

    /** Just the maneuver, at the turn: `turn_now`. */
    data class Now(val maneuverFragment: String) : TurnPrompt

    /** The SDK's own instruction text, for maneuvers without fragments. */
    data class Instruction(val text: String) : TurnPrompt
}

/**
 * Decides when to call out turns, from the turn-by-turn feed and the current speed.
 *
 * Each step gets at most two prompts:
 * - an advance notice once the step is within a speed-dependent distance (1 km at 80 km/h and
 *   above, 500 m at 50 km/h and above, otherwise 200 m), rounded to the nearest recorded distance;
 * - the maneuver alone once it is about [finalLeadTimeS] seconds away (at least [minFinalDistanceM]).
 *
 * The advance notice is skipped when the step is already almost at the final prompt, so two
 * close turns do not pile up prompts. A step is identified by number, maneuver and road, so a
 * traffic-driven route update that keeps the same next step does not repeat it.
 *
 * Not thread-safe: call from a single coroutine.
 */
class TurnPromptPlanner(
    /** Distances with recorded `ahead_<n>m` fragments. */
    private val distanceSlots: List<Int>,
    private val minFinalDistanceM: Double = 30.0,
    private val finalLeadTimeS: Double = 6.0,
    /** An advance notice must come at least this much before the final prompt. */
    private val minPreviewGapM: Double = 50.0,
) {
    private data class StepKey(val stepNumber: Int, val maneuver: Int, val roadName: String?)

    private var previewed: StepKey? = null
    private var finalized: StepKey? = null

    init {
        require(distanceSlots.isNotEmpty()) { "No distance slots" }
    }

    fun reset() {
        previewed = null
        finalized = null
    }

    fun next(update: TurnUpdate, speedMps: Double?): TurnPrompt? {
        if (update.state != TurnNavState.ENROUTE) return null
        val step = update.stepNumber ?: return null
        val distance = update.distanceToStepM?.toDouble() ?: return null
        val speech = ManeuverMapper.speechFor(update.maneuver, update.roundaboutExit)
        if (speech == ManeuverSpeech.Silent) return null

        val key = StepKey(step, update.maneuver, update.roadName)
        val speed = speedMps ?: 0.0
        val finalDistance = max(minFinalDistanceM, speed * finalLeadTimeS)

        if (distance <= finalDistance) {
            if (finalized == key) return null
            finalized = key
            previewed = key
            return when (speech) {
                is ManeuverSpeech.Fragment -> if (speech.atManeuver) TurnPrompt.Now(speech.id) else null
                ManeuverSpeech.Unmapped -> update.instructionText?.takeIf { it.isNotBlank() }?.let(TurnPrompt::Instruction)
                ManeuverSpeech.Silent -> null
            }
        }

        if (previewed == key || distance > previewDistanceM(speed) || distance < finalDistance + minPreviewGapM) return null
        previewed = key
        // Unmapped maneuvers get their instruction text once, at the turn, rather than twice.
        return (speech as? ManeuverSpeech.Fragment)?.let { TurnPrompt.Ahead(it.id, nearestSlot(distance)) }
    }

    private fun previewDistanceM(speedMps: Double): Double {
        val kmh = speedMps * 3.6
        return when {
            kmh >= 80 -> 1_000.0
            kmh >= 50 -> 500.0
            else -> 200.0
        }
    }

    /** Nearest rather than rounded down: the notice fires just inside the threshold, e.g. at 990 m. */
    private fun nearestSlot(distanceM: Double): Int = distanceSlots.minBy { abs(it - distanceM) }
}
