package cc.nekocat.waylamaps.car

import androidx.car.app.navigation.model.Maneuver as CarManeuver
import com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver as SdkManeuver

/** An Android Auto maneuver type, with the roundabout exit number when the type needs one. */
data class CarManeuverType(val type: Int, val roundaboutExit: Int? = null)

/** Converts Navigation SDK turn-by-turn maneuvers to Android Auto maneuver types. */
object CarManeuvers {
    private val SIMPLE = mapOf(
        SdkManeuver.DEPART to CarManeuver.TYPE_DEPART,
        SdkManeuver.NAME_CHANGE to CarManeuver.TYPE_NAME_CHANGE,
        SdkManeuver.STRAIGHT to CarManeuver.TYPE_STRAIGHT,
        SdkManeuver.DESTINATION to CarManeuver.TYPE_DESTINATION,
        SdkManeuver.DESTINATION_LEFT to CarManeuver.TYPE_DESTINATION_LEFT,
        SdkManeuver.DESTINATION_RIGHT to CarManeuver.TYPE_DESTINATION_RIGHT,
        SdkManeuver.TURN_LEFT to CarManeuver.TYPE_TURN_NORMAL_LEFT,
        SdkManeuver.TURN_RIGHT to CarManeuver.TYPE_TURN_NORMAL_RIGHT,
        SdkManeuver.TURN_KEEP_LEFT to CarManeuver.TYPE_KEEP_LEFT,
        SdkManeuver.TURN_KEEP_RIGHT to CarManeuver.TYPE_KEEP_RIGHT,
        SdkManeuver.TURN_SLIGHT_LEFT to CarManeuver.TYPE_TURN_SLIGHT_LEFT,
        SdkManeuver.TURN_SLIGHT_RIGHT to CarManeuver.TYPE_TURN_SLIGHT_RIGHT,
        SdkManeuver.TURN_SHARP_LEFT to CarManeuver.TYPE_TURN_SHARP_LEFT,
        SdkManeuver.TURN_SHARP_RIGHT to CarManeuver.TYPE_TURN_SHARP_RIGHT,
        SdkManeuver.TURN_U_TURN_CLOCKWISE to CarManeuver.TYPE_U_TURN_RIGHT,
        SdkManeuver.TURN_U_TURN_COUNTERCLOCKWISE to CarManeuver.TYPE_U_TURN_LEFT,
        SdkManeuver.MERGE_UNSPECIFIED to CarManeuver.TYPE_MERGE_SIDE_UNSPECIFIED,
        SdkManeuver.MERGE_LEFT to CarManeuver.TYPE_MERGE_LEFT,
        SdkManeuver.MERGE_RIGHT to CarManeuver.TYPE_MERGE_RIGHT,
        SdkManeuver.FORK_LEFT to CarManeuver.TYPE_FORK_LEFT,
        SdkManeuver.FORK_RIGHT to CarManeuver.TYPE_FORK_RIGHT,
        SdkManeuver.ON_RAMP_UNSPECIFIED to CarManeuver.TYPE_ON_RAMP_NORMAL_RIGHT,
        SdkManeuver.ON_RAMP_LEFT to CarManeuver.TYPE_ON_RAMP_NORMAL_LEFT,
        SdkManeuver.ON_RAMP_RIGHT to CarManeuver.TYPE_ON_RAMP_NORMAL_RIGHT,
        SdkManeuver.ON_RAMP_KEEP_LEFT to CarManeuver.TYPE_ON_RAMP_SLIGHT_LEFT,
        SdkManeuver.ON_RAMP_KEEP_RIGHT to CarManeuver.TYPE_ON_RAMP_SLIGHT_RIGHT,
        SdkManeuver.ON_RAMP_SLIGHT_LEFT to CarManeuver.TYPE_ON_RAMP_SLIGHT_LEFT,
        SdkManeuver.ON_RAMP_SLIGHT_RIGHT to CarManeuver.TYPE_ON_RAMP_SLIGHT_RIGHT,
        SdkManeuver.ON_RAMP_SHARP_LEFT to CarManeuver.TYPE_ON_RAMP_SHARP_LEFT,
        SdkManeuver.ON_RAMP_SHARP_RIGHT to CarManeuver.TYPE_ON_RAMP_SHARP_RIGHT,
        SdkManeuver.ON_RAMP_U_TURN_CLOCKWISE to CarManeuver.TYPE_ON_RAMP_U_TURN_RIGHT,
        SdkManeuver.ON_RAMP_U_TURN_COUNTERCLOCKWISE to CarManeuver.TYPE_ON_RAMP_U_TURN_LEFT,
        SdkManeuver.OFF_RAMP_UNSPECIFIED to CarManeuver.TYPE_OFF_RAMP_NORMAL_RIGHT,
        SdkManeuver.OFF_RAMP_LEFT to CarManeuver.TYPE_OFF_RAMP_NORMAL_LEFT,
        SdkManeuver.OFF_RAMP_RIGHT to CarManeuver.TYPE_OFF_RAMP_NORMAL_RIGHT,
        SdkManeuver.OFF_RAMP_KEEP_LEFT to CarManeuver.TYPE_OFF_RAMP_SLIGHT_LEFT,
        SdkManeuver.OFF_RAMP_KEEP_RIGHT to CarManeuver.TYPE_OFF_RAMP_SLIGHT_RIGHT,
        SdkManeuver.OFF_RAMP_SLIGHT_LEFT to CarManeuver.TYPE_OFF_RAMP_SLIGHT_LEFT,
        SdkManeuver.OFF_RAMP_SLIGHT_RIGHT to CarManeuver.TYPE_OFF_RAMP_SLIGHT_RIGHT,
        // Android Auto has no sharp or U-turn exits; the normal exit on that side is closest.
        SdkManeuver.OFF_RAMP_SHARP_LEFT to CarManeuver.TYPE_OFF_RAMP_NORMAL_LEFT,
        SdkManeuver.OFF_RAMP_SHARP_RIGHT to CarManeuver.TYPE_OFF_RAMP_NORMAL_RIGHT,
        SdkManeuver.OFF_RAMP_U_TURN_CLOCKWISE to CarManeuver.TYPE_OFF_RAMP_NORMAL_RIGHT,
        SdkManeuver.OFF_RAMP_U_TURN_COUNTERCLOCKWISE to CarManeuver.TYPE_OFF_RAMP_NORMAL_LEFT,
        SdkManeuver.ROUNDABOUT_EXIT_CLOCKWISE to CarManeuver.TYPE_ROUNDABOUT_EXIT_CW,
        SdkManeuver.ROUNDABOUT_EXIT_COUNTERCLOCKWISE to CarManeuver.TYPE_ROUNDABOUT_EXIT_CCW,
        SdkManeuver.FERRY_BOAT to CarManeuver.TYPE_FERRY_BOAT,
        SdkManeuver.FERRY_TRAIN to CarManeuver.TYPE_FERRY_TRAIN,
    )

    private val CLOCKWISE_ROUNDABOUTS = setOf(
        SdkManeuver.ROUNDABOUT_CLOCKWISE, SdkManeuver.ROUNDABOUT_STRAIGHT_CLOCKWISE,
        SdkManeuver.ROUNDABOUT_LEFT_CLOCKWISE, SdkManeuver.ROUNDABOUT_RIGHT_CLOCKWISE,
        SdkManeuver.ROUNDABOUT_SLIGHT_LEFT_CLOCKWISE, SdkManeuver.ROUNDABOUT_SLIGHT_RIGHT_CLOCKWISE,
        SdkManeuver.ROUNDABOUT_SHARP_LEFT_CLOCKWISE, SdkManeuver.ROUNDABOUT_SHARP_RIGHT_CLOCKWISE,
        SdkManeuver.ROUNDABOUT_U_TURN_CLOCKWISE,
    )
    private val COUNTERCLOCKWISE_ROUNDABOUTS = setOf(
        SdkManeuver.ROUNDABOUT_COUNTERCLOCKWISE, SdkManeuver.ROUNDABOUT_STRAIGHT_COUNTERCLOCKWISE,
        SdkManeuver.ROUNDABOUT_LEFT_COUNTERCLOCKWISE, SdkManeuver.ROUNDABOUT_RIGHT_COUNTERCLOCKWISE,
        SdkManeuver.ROUNDABOUT_SLIGHT_LEFT_COUNTERCLOCKWISE, SdkManeuver.ROUNDABOUT_SLIGHT_RIGHT_COUNTERCLOCKWISE,
        SdkManeuver.ROUNDABOUT_SHARP_LEFT_COUNTERCLOCKWISE, SdkManeuver.ROUNDABOUT_SHARP_RIGHT_COUNTERCLOCKWISE,
        SdkManeuver.ROUNDABOUT_U_TURN_COUNTERCLOCKWISE,
    )

    fun convert(maneuver: Int, roundaboutExit: Int?): CarManeuverType {
        SIMPLE[maneuver]?.let { return CarManeuverType(it) }
        val exit = roundaboutExit?.takeIf { it >= 1 }
        return when (maneuver) {
            in CLOCKWISE_ROUNDABOUTS -> if (exit != null) {
                CarManeuverType(CarManeuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW, exit)
            } else {
                CarManeuverType(CarManeuver.TYPE_ROUNDABOUT_ENTER_CW)
            }
            in COUNTERCLOCKWISE_ROUNDABOUTS -> if (exit != null) {
                CarManeuverType(CarManeuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW, exit)
            } else {
                CarManeuverType(CarManeuver.TYPE_ROUNDABOUT_ENTER_CCW)
            }
            else -> CarManeuverType(CarManeuver.TYPE_UNKNOWN)
        }
    }
}
