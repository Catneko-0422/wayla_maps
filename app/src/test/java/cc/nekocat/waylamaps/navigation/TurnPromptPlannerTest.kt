package cc.nekocat.waylamaps.navigation

import com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TurnPromptPlannerTest {
    private val planner = TurnPromptPlanner(listOf(50, 100, 150, 200, 250, 300, 500, 1000))

    private fun update(
        distance: Int,
        step: Int = 3,
        maneuver: Int = Maneuver.TURN_LEFT,
        road: String? = "忠孝東路",
        state: TurnNavState = TurnNavState.ENROUTE,
        text: String? = "左轉進入忠孝東路",
    ) = TurnUpdate(state, step, maneuver, roundaboutExit = null, distanceToStepM = distance, roadName = road, instructionText = text)

    private fun kmh(value: Double) = value / 3.6

    @Test
    fun previewDistanceDependsOnSpeed() {
        assertNull(planner.next(update(1_100), kmh(90.0)))
        assertEquals(TurnPrompt.Ahead("maneuver_turn_left", 1000), planner.next(update(985), kmh(90.0)))

        planner.reset()
        assertNull(planner.next(update(600), kmh(60.0)))
        assertEquals(TurnPrompt.Ahead("maneuver_turn_left", 500), planner.next(update(490), kmh(60.0)))

        planner.reset()
        assertNull(planner.next(update(300), kmh(30.0)))
        assertEquals(TurnPrompt.Ahead("maneuver_turn_left", 200), planner.next(update(195), kmh(30.0)))
    }

    @Test
    fun eachPromptPlaysOncePerStep() {
        assertEquals(TurnPrompt.Ahead("maneuver_turn_left", 200), planner.next(update(200), kmh(30.0)))
        assertNull(planner.next(update(150), kmh(30.0)))
        assertEquals(TurnPrompt.Now("maneuver_turn_left"), planner.next(update(45), kmh(30.0)))
        assertNull(planner.next(update(20), kmh(30.0)))
    }

    @Test
    fun finalPromptLeadsBySpeed() {
        // 6 s at 72 km/h is 120 m.
        planner.next(update(500), kmh(72.0))
        assertNull(planner.next(update(130), kmh(72.0)))
        assertEquals(TurnPrompt.Now("maneuver_turn_left"), planner.next(update(118), kmh(72.0)))
    }

    @Test
    fun shortStepGetsOnlyTheFinalPrompt() {
        // Within the 30 m final distance plus the 50 m gap, so the advance notice is skipped.
        assertNull(planner.next(update(70), kmh(20.0)))
        assertEquals(TurnPrompt.Now("maneuver_turn_left"), planner.next(update(30), kmh(20.0)))
    }

    @Test
    fun stepFirstSeenInsideFinalDistanceSpeaksOnlyTheManeuver() {
        assertEquals(TurnPrompt.Now("maneuver_turn_right"), planner.next(update(25, maneuver = Maneuver.TURN_RIGHT), kmh(20.0)))
    }

    @Test
    fun consecutiveTurnsAreAnnouncedInOrder() {
        planner.next(update(200, step = 3), kmh(30.0))
        assertEquals(TurnPrompt.Now("maneuver_turn_left"), planner.next(update(40, step = 3), kmh(30.0)))

        // The next step is only 150 m on: announced right after the turn.
        val next = update(150, step = 4, maneuver = Maneuver.TURN_RIGHT, road = "復興南路")
        assertEquals(TurnPrompt.Ahead("maneuver_turn_right", 150), planner.next(next, kmh(30.0)))
    }

    @Test
    fun routeUpdateWithTheSameNextStepDoesNotRepeat() {
        planner.next(update(200), kmh(30.0))
        assertNull(planner.next(update(190), kmh(30.0)))
    }

    @Test
    fun rerouteAnnouncesTheNewStep() {
        planner.next(update(200), kmh(30.0))
        assertNull(planner.next(update(180, state = TurnNavState.REROUTING), kmh(30.0)))

        val rerouted = update(190, step = 1, maneuver = Maneuver.TURN_U_TURN_CLOCKWISE, road = "忠孝東路")
        assertEquals(TurnPrompt.Ahead("maneuver_u_turn", 200), planner.next(rerouted, kmh(30.0)))
    }

    @Test
    fun departureIsSilent() {
        assertNull(planner.next(update(150, maneuver = Maneuver.DEPART), kmh(30.0)))
        assertNull(planner.next(update(20, maneuver = Maneuver.DEPART), kmh(30.0)))
    }

    @Test
    fun destinationIsAnnouncedAheadButNotAtArrival() {
        val destination = update(190, maneuver = Maneuver.DESTINATION_RIGHT)
        assertEquals(TurnPrompt.Ahead("maneuver_destination_right", 200), planner.next(destination, kmh(30.0)))
        assertNull(planner.next(destination.copy(distanceToStepM = 20), kmh(30.0)))
    }

    @Test
    fun unmappedManeuverSpeaksInstructionAtTheTurn() {
        val ferry = update(190, maneuver = Maneuver.FERRY_BOAT, text = "搭乘渡輪")
        assertNull(planner.next(ferry, kmh(30.0)))
        assertEquals(TurnPrompt.Instruction("搭乘渡輪"), planner.next(ferry.copy(distanceToStepM = 25), kmh(30.0)))
    }

    @Test
    fun unknownSpeedUsesWalkingPaceThresholds() {
        assertEquals(TurnPrompt.Ahead("maneuver_turn_left", 200), planner.next(update(200), speedMps = null))
        assertEquals(TurnPrompt.Now("maneuver_turn_left"), planner.next(update(30), speedMps = null))
    }

    @Test
    fun stoppedFeedIsIgnored() {
        assertNull(planner.next(update(20, state = TurnNavState.STOPPED), kmh(30.0)))
    }
}
