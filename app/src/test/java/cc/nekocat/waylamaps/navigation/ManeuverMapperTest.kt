package cc.nekocat.waylamaps.navigation

import cc.nekocat.waylamaps.voice.PhraseCatalog
import com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ManeuverMapperTest {
    private fun fragmentOf(maneuver: Int, exit: Int? = null) =
        (ManeuverMapper.speechFor(maneuver, exit) as ManeuverSpeech.Fragment).id

    @Test
    fun mapsTurns() {
        assertEquals("maneuver_turn_left", fragmentOf(Maneuver.TURN_LEFT))
        assertEquals("maneuver_turn_left", fragmentOf(Maneuver.TURN_SHARP_LEFT))
        assertEquals("maneuver_turn_right", fragmentOf(Maneuver.TURN_RIGHT))
        assertEquals("maneuver_keep_left", fragmentOf(Maneuver.TURN_SLIGHT_LEFT))
        assertEquals("maneuver_keep_right", fragmentOf(Maneuver.FORK_RIGHT))
        assertEquals("maneuver_u_turn", fragmentOf(Maneuver.TURN_U_TURN_COUNTERCLOCKWISE))
        assertEquals("maneuver_straight", fragmentOf(Maneuver.STRAIGHT))
    }

    @Test
    fun mapsRampsBySide() {
        assertEquals("maneuver_on_ramp_right", fragmentOf(Maneuver.ON_RAMP_SLIGHT_RIGHT))
        assertEquals("maneuver_on_ramp", fragmentOf(Maneuver.ON_RAMP_UNSPECIFIED))
        assertEquals("maneuver_off_ramp_left", fragmentOf(Maneuver.OFF_RAMP_KEEP_LEFT))
        assertEquals("maneuver_off_ramp", fragmentOf(Maneuver.OFF_RAMP_U_TURN_CLOCKWISE))
    }

    @Test
    fun mapsRoundaboutExitNumbers() {
        assertEquals("maneuver_roundabout_exit_2", fragmentOf(Maneuver.ROUNDABOUT_RIGHT_COUNTERCLOCKWISE, exit = 2))
        assertEquals("maneuver_roundabout", fragmentOf(Maneuver.ROUNDABOUT_LEFT_CLOCKWISE, exit = null))
        assertEquals("maneuver_roundabout", fragmentOf(Maneuver.ROUNDABOUT_STRAIGHT_CLOCKWISE, exit = 7))
        assertEquals("maneuver_roundabout_leave", fragmentOf(Maneuver.ROUNDABOUT_EXIT_CLOCKWISE))
    }

    @Test
    fun destinationsAreOnlyAnnouncedAhead() {
        val speech = ManeuverMapper.speechFor(Maneuver.DESTINATION_LEFT) as ManeuverSpeech.Fragment

        assertEquals("maneuver_destination_left", speech.id)
        assertEquals(false, speech.atManeuver)
    }

    @Test
    fun departureAndNameChangesAreSilent() {
        assertEquals(ManeuverSpeech.Silent, ManeuverMapper.speechFor(Maneuver.DEPART))
        assertEquals(ManeuverSpeech.Silent, ManeuverMapper.speechFor(Maneuver.NAME_CHANGE))
    }

    @Test
    fun unknownManeuversFallBackToInstructionText() {
        assertEquals(ManeuverSpeech.Unmapped, ManeuverMapper.speechFor(Maneuver.UNKNOWN))
        assertEquals(ManeuverSpeech.Unmapped, ManeuverMapper.speechFor(Maneuver.FERRY_BOAT))
        assertEquals(ManeuverSpeech.Unmapped, ManeuverMapper.speechFor(999))
    }

    /** Every fragment the mapper can return is a value of the phrase table's maneuver slot. */
    @Test
    fun everyFragmentIsInThePhraseTable() {
        val catalog = PhraseCatalog.parse(File("src/main/assets/${PhraseCatalog.ASSET_PATH}").readText())
        val slot = catalog.slots.getValue("maneuver")
        for (maneuver in Maneuver.UNKNOWN..Maneuver.NAME_CHANGE) {
            for (exit in listOf(null, 1, 2, 3, 4, 5)) {
                val speech = ManeuverMapper.speechFor(maneuver, exit)
                if (speech is ManeuverSpeech.Fragment) assertTrue("${speech.id} not in slot", speech.id in slot)
            }
        }
    }
}
