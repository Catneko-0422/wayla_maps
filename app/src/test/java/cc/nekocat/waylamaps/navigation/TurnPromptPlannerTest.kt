package cc.nekocat.waylamaps.navigation

import com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TurnPromptPlannerTest {
    private val planner = TurnPromptPlanner(listOf(50, 100, 150, 200, 250, 300, 500, 1000), kmSlots = listOf(2, 3, 5, 10))

    private fun update(
        distance: Int,
        step: Int = 3,
        maneuver: Int = Maneuver.TURN_LEFT,
        road: String? = "忠孝東路",
        state: TurnNavState = TurnNavState.ENROUTE,
        text: String? = "左轉進入忠孝東路",
        next: Int? = null,
        gap: Int? = null,
    ) = TurnUpdate(
        state, step, maneuver, roundaboutExit = null, distanceToStepM = distance, roadName = road, instructionText = text,
        nextManeuver = next, nextStepGapM = gap,
    )

    private fun kmh(value: Double) = value / 3.6

    @Test
    fun townSpeedGetsTheHundredMeterNoticeAndTheTurn() {
        assertNull(planner.next(update(300), kmh(30.0)))
        assertNull(planner.next(update(120), kmh(30.0)))
        assertEquals(TurnPrompt.Ahead("maneuver_turn_left", 100), planner.next(update(98), kmh(30.0)))
        assertNull(planner.next(update(60), kmh(30.0)))
        // 2.5 s at 30 km/h is 21 m, so the 25 m minimum applies.
        assertEquals(TurnPrompt.Now("maneuver_turn_left"), planner.next(update(24), kmh(30.0)))
        assertNull(planner.next(update(10), kmh(30.0)))
    }

    @Test
    fun fastRoadsAlsoGetAFarNotice() {
        assertNull(planner.next(update(1_100), kmh(90.0)))
        assertEquals(TurnPrompt.Ahead("maneuver_turn_left", 1000), planner.next(update(985), kmh(90.0)))
        assertNull(planner.next(update(400), kmh(90.0)))
        // 7 s at 90 km/h is 175 m, farther than 100 m.
        assertEquals(TurnPrompt.Ahead("maneuver_turn_left", 150), planner.next(update(170), kmh(90.0)))
        assertEquals(TurnPrompt.Now("maneuver_turn_left"), planner.next(update(60), kmh(90.0)))

        planner.reset()
        assertNull(planner.next(update(600), kmh(60.0)))
        assertEquals(TurnPrompt.Ahead("maneuver_turn_left", 500), planner.next(update(490), kmh(60.0)))
    }

    @Test
    fun farNoticeIsSkippedWhenTheMainOneIsClose() {
        // 260 m at 60 km/h: the 117 m main notice is less than 150 m further on.
        assertNull(planner.next(update(260), kmh(60.0)))
        assertEquals(TurnPrompt.Ahead("maneuver_turn_left", 100), planner.next(update(110), kmh(60.0)))
    }

    @Test
    fun shortStepGetsOnlyTheTurn() {
        // Inside the main notice distance but too close to the turn for it.
        assertNull(planner.next(update(45), kmh(20.0)))
        assertEquals(TurnPrompt.Now("maneuver_turn_left"), planner.next(update(25), kmh(20.0)))
    }

    @Test
    fun stepFirstSeenAtTheTurnSpeaksOnlyTheManeuver() {
        assertEquals(TurnPrompt.Now("maneuver_turn_right"), planner.next(update(20, maneuver = Maneuver.TURN_RIGHT), kmh(20.0)))
    }

    @Test
    fun consecutiveTurnsAreAnnouncedInOrder() {
        planner.next(update(100, step = 3), kmh(30.0))
        assertEquals(TurnPrompt.Now("maneuver_turn_left"), planner.next(update(20, step = 3), kmh(30.0)))

        // The next step is only 80 m on: its notice comes right after the turn.
        val next = update(80, step = 4, maneuver = Maneuver.TURN_RIGHT, road = "復興南路")
        assertEquals(TurnPrompt.Ahead("maneuver_turn_right", 100), planner.next(next, kmh(30.0)))
    }

    @Test
    fun routeUpdateWithTheSameNextStepDoesNotRepeat() {
        planner.next(update(100), kmh(30.0))
        assertNull(planner.next(update(90), kmh(30.0)))
    }

    @Test
    fun rerouteAnnouncesTheNewStep() {
        planner.next(update(100), kmh(30.0))
        assertNull(planner.next(update(95, state = TurnNavState.REROUTING), kmh(30.0)))

        val rerouted = update(90, step = 1, maneuver = Maneuver.TURN_U_TURN_CLOCKWISE, road = "忠孝東路")
        assertEquals(TurnPrompt.Ahead("maneuver_u_turn", 100), planner.next(rerouted, kmh(30.0)))
    }

    @Test
    fun departureIsSilent() {
        assertNull(planner.next(update(90, maneuver = Maneuver.DEPART), kmh(30.0)))
        assertNull(planner.next(update(20, maneuver = Maneuver.DEPART), kmh(30.0)))
    }

    @Test
    fun destinationIsAnnouncedAheadButNotAtArrival() {
        val destination = update(95, maneuver = Maneuver.DESTINATION_RIGHT)
        assertEquals(TurnPrompt.Ahead("maneuver_destination_right", 100), planner.next(destination, kmh(30.0)))
        assertNull(planner.next(destination.copy(distanceToStepM = 20), kmh(30.0)))
    }

    @Test
    fun unmappedManeuverSpeaksInstructionAtTheTurn() {
        val ferry = update(95, maneuver = Maneuver.FERRY_BOAT, text = "搭乘渡輪")
        assertNull(planner.next(ferry, kmh(30.0)))
        assertEquals(TurnPrompt.Instruction("搭乘渡輪"), planner.next(ferry.copy(distanceToStepM = 20), kmh(30.0)))
    }

    @Test
    fun unknownSpeedUsesTheMinimumDistances() {
        assertEquals(TurnPrompt.Ahead("maneuver_turn_left", 100), planner.next(update(100), speedMps = null))
        assertEquals(TurnPrompt.Now("maneuver_turn_left"), planner.next(update(25), speedMps = null))
    }

    @Test
    fun stoppedFeedIsIgnored() {
        assertNull(planner.next(update(20, state = TurnNavState.STOPPED), kmh(30.0)))
    }

    @Test
    fun closeTurnsAreChainedAndTheSecondNoticeIsSkipped() {
        val first = update(100, next = Maneuver.TURN_RIGHT, gap = 80)
        assertEquals(TurnPrompt.Ahead("maneuver_turn_left", 100, then = "maneuver_turn_right"), planner.next(first, kmh(30.0)))
        assertEquals(TurnPrompt.Now("maneuver_turn_left", then = "maneuver_turn_right"), planner.next(first.copy(distanceToStepM = 20), kmh(30.0)))

        // The right turn was already said: only its turn prompt remains.
        val second = update(75, step = 4, maneuver = Maneuver.TURN_RIGHT, road = "復興南路")
        assertNull(planner.next(second, kmh(30.0)))
        assertEquals(TurnPrompt.Now("maneuver_turn_right"), planner.next(second.copy(distanceToStepM = 20), kmh(30.0)))
    }

    @Test
    fun distantNextTurnIsNotChained() {
        assertEquals(TurnPrompt.Ahead("maneuver_turn_left", 100), planner.next(update(100, next = Maneuver.TURN_RIGHT, gap = 600), kmh(30.0)))
    }

    @Test
    fun longStretchGetsContinueFirst() {
        assertEquals(TurnPrompt.Continue(3), planner.next(update(3_200), kmh(60.0)))
        assertNull(planner.next(update(3_100), kmh(60.0)))
        assertEquals(TurnPrompt.Ahead("maneuver_turn_left", 500), planner.next(update(495), kmh(60.0)))
    }

    @Test
    fun shortStretchHasNoContinue() {
        assertNull(planner.next(update(1_500), kmh(30.0)))
        assertEquals(TurnPrompt.Ahead("maneuver_turn_left", 100), planner.next(update(100), kmh(30.0)))
    }
}
