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
 * Each step gets up to three prompts, like other navigation apps:
 * - a far notice on fast roads only (1 km at 80 km/h and above, 500 m at 50 km/h and above), so
 *   there is time to change lanes; in town it would only be noise;
 * - the main notice at 100 m, or [nearLeadTimeS] seconds ahead when that is farther: "in 100
 *   meters, turn right";
 * - the maneuver alone just before the turn, about [finalLeadTimeS] seconds ahead (at least
 *   [minFinalDistanceM]).
 * Distances are spoken as the nearest recorded distance. A notice is skipped when the next one
 * is about to follow anyway, so close turns do not pile up prompts. A step is identified by
 * number, maneuver and road, so a traffic-driven route update that keeps the same next step does
 * not repeat it.
 *
 * Not thread-safe: call from a single coroutine.
 */
class TurnPromptPlanner(
    /** Distances with recorded `ahead_<n>m` fragments. */
    private val distanceSlots: List<Int>,
    private val nearDistanceM: Double = 100.0,
    private val nearLeadTimeS: Double = 7.0,
    private val minFinalDistanceM: Double = 25.0,
    private val finalLeadTimeS: Double = 2.5,
    /** A notice must come at least this far before the next one, or it is left out. */
    private val minNoticeGapM: Double = 30.0,
    /** The far notice also needs this much room before the main one. */
    private val minFarGapM: Double = 150.0,
) {
    private data class StepKey(val stepNumber: Int, val maneuver: Int, val roadName: String?)

    private enum class Stage { FAR, NEAR, NOW }

    private var step: StepKey? = null
    private val done = mutableSetOf<Stage>()

    init {
        require(distanceSlots.isNotEmpty()) { "No distance slots" }
    }

    fun reset() {
        step = null
        done.clear()
    }

    fun next(update: TurnUpdate, speedMps: Double?): TurnPrompt? {
        if (update.state != TurnNavState.ENROUTE) return null
        val number = update.stepNumber ?: return null
        val distance = update.distanceToStepM?.toDouble() ?: return null
        val speech = ManeuverMapper.speechFor(update.maneuver, update.roundaboutExit)
        if (speech == ManeuverSpeech.Silent) return null

        val key = StepKey(number, update.maneuver, update.roadName)
        if (key != step) {
            step = key
            done.clear()
        }
        val speed = speedMps ?: 0.0
        val finalDistance = max(minFinalDistanceM, speed * finalLeadTimeS)
        val nearDistance = max(nearDistanceM, speed * nearLeadTimeS)

        if (distance <= finalDistance) {
            if (Stage.NOW in done) return null
            done += Stage.entries
            return when (speech) {
                is ManeuverSpeech.Fragment -> if (speech.atManeuver) TurnPrompt.Now(speech.id) else null
                ManeuverSpeech.Unmapped -> update.instructionText?.takeIf { it.isNotBlank() }?.let(TurnPrompt::Instruction)
                ManeuverSpeech.Silent -> null
            }
        }
        // Unmapped maneuvers get their instruction text once, at the turn, rather than twice.
        val fragment = (speech as? ManeuverSpeech.Fragment)?.id ?: return null

        if (distance <= nearDistance) {
            if (Stage.NEAR in done) return null
            done += Stage.FAR
            done += Stage.NEAR
            if (distance < finalDistance + minNoticeGapM) return null
            return TurnPrompt.Ahead(fragment, nearestSlot(distance))
        }

        val farDistance = farDistanceM(speed) ?: return null
        if (Stage.FAR in done || distance > farDistance) return null
        done += Stage.FAR
        if (distance < nearDistance + minFarGapM) return null
        return TurnPrompt.Ahead(fragment, nearestSlot(distance))
    }

    private fun farDistanceM(speedMps: Double): Double? {
        val kmh = speedMps * 3.6
        return when {
            kmh >= 80 -> 1_000.0
            kmh >= 50 -> 500.0
            else -> null
        }
    }

    /** Nearest rather than rounded down: the notice fires just inside the threshold, e.g. at 990 m. */
    private fun nearestSlot(distanceM: Double): Int = distanceSlots.minBy { abs(it - distanceM) }
}
