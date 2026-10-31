package cc.nekocat.waylamaps.car

import androidx.car.app.navigation.model.Maneuver as CarManeuver
import com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver as SdkManeuver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CarManeuversTest {
    @Test
    fun convertsTurnsAndRamps() {
        assertEquals(CarManeuverType(CarManeuver.TYPE_TURN_NORMAL_LEFT), CarManeuvers.convert(SdkManeuver.TURN_LEFT, null))
        assertEquals(CarManeuverType(CarManeuver.TYPE_U_TURN_LEFT), CarManeuvers.convert(SdkManeuver.TURN_U_TURN_COUNTERCLOCKWISE, null))
        assertEquals(CarManeuverType(CarManeuver.TYPE_OFF_RAMP_NORMAL_RIGHT), CarManeuvers.convert(SdkManeuver.OFF_RAMP_SHARP_RIGHT, null))
    }

    @Test
    fun roundaboutsCarryTheExitWhenKnown() {
        assertEquals(
            CarManeuverType(CarManeuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW, 2),
            CarManeuvers.convert(SdkManeuver.ROUNDABOUT_RIGHT_COUNTERCLOCKWISE, 2),
        )
        assertEquals(
            CarManeuverType(CarManeuver.TYPE_ROUNDABOUT_ENTER_CW),
            CarManeuvers.convert(SdkManeuver.ROUNDABOUT_LEFT_CLOCKWISE, null),
        )
    }

    /** Every SDK maneuver but UNKNOWN maps to something Android Auto can draw. */
    @Test
    fun coversEveryKnownManeuver() {
        for (maneuver in SdkManeuver.DEPART..SdkManeuver.NAME_CHANGE) {
            assertNotEquals("maneuver $maneuver", CarManeuver.TYPE_UNKNOWN, CarManeuvers.convert(maneuver, null).type)
        }
    }
}
