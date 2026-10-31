package cc.nekocat.waylamaps.navigation

import com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver

sealed interface ManeuverSpeech {
    /**
     * A recorded maneuver fragment. [atManeuver] is false for destinations: arrival has its own
     * prompt, so only the advance notice is spoken.
     */
    data class Fragment(val id: String, val atManeuver: Boolean = true) : ManeuverSpeech

    /** Not worth a prompt (departing, the road changing name). */
    data object Silent : ManeuverSpeech

    /** No fragment fits; speak the SDK's instruction text with TTS so the turn is not silent. */
    data object Unmapped : ManeuverSpeech
}

/** Maps Navigation SDK maneuvers to phrase table fragments (`maneuver_*` in phrases.json). */
object ManeuverMapper {
    private const val MAX_ROUNDABOUT_EXIT = 5

    fun speechFor(maneuver: Int, roundaboutExit: Int? = null): ManeuverSpeech = when (maneuver) {
        Maneuver.DEPART, Maneuver.NAME_CHANGE -> ManeuverSpeech.Silent

        Maneuver.TURN_LEFT, Maneuver.TURN_SHARP_LEFT -> fragment("maneuver_turn_left")
        Maneuver.TURN_RIGHT, Maneuver.TURN_SHARP_RIGHT -> fragment("maneuver_turn_right")
        Maneuver.TURN_SLIGHT_LEFT, Maneuver.TURN_KEEP_LEFT, Maneuver.FORK_LEFT, Maneuver.MERGE_LEFT ->
            fragment("maneuver_keep_left")
        Maneuver.TURN_SLIGHT_RIGHT, Maneuver.TURN_KEEP_RIGHT, Maneuver.FORK_RIGHT, Maneuver.MERGE_RIGHT ->
            fragment("maneuver_keep_right")
        Maneuver.TURN_U_TURN_CLOCKWISE, Maneuver.TURN_U_TURN_COUNTERCLOCKWISE -> fragment("maneuver_u_turn")
        Maneuver.STRAIGHT -> fragment("maneuver_straight")
        Maneuver.MERGE_UNSPECIFIED -> fragment("maneuver_merge")

        Maneuver.ON_RAMP_LEFT, Maneuver.ON_RAMP_KEEP_LEFT, Maneuver.ON_RAMP_SLIGHT_LEFT, Maneuver.ON_RAMP_SHARP_LEFT ->
            fragment("maneuver_on_ramp_left")
        Maneuver.ON_RAMP_RIGHT, Maneuver.ON_RAMP_KEEP_RIGHT, Maneuver.ON_RAMP_SLIGHT_RIGHT, Maneuver.ON_RAMP_SHARP_RIGHT ->
            fragment("maneuver_on_ramp_right")
        Maneuver.ON_RAMP_UNSPECIFIED, Maneuver.ON_RAMP_U_TURN_CLOCKWISE, Maneuver.ON_RAMP_U_TURN_COUNTERCLOCKWISE ->
            fragment("maneuver_on_ramp")

        Maneuver.OFF_RAMP_LEFT, Maneuver.OFF_RAMP_KEEP_LEFT, Maneuver.OFF_RAMP_SLIGHT_LEFT, Maneuver.OFF_RAMP_SHARP_LEFT ->
            fragment("maneuver_off_ramp_left")
        Maneuver.OFF_RAMP_RIGHT, Maneuver.OFF_RAMP_KEEP_RIGHT, Maneuver.OFF_RAMP_SLIGHT_RIGHT, Maneuver.OFF_RAMP_SHARP_RIGHT ->
            fragment("maneuver_off_ramp_right")
        Maneuver.OFF_RAMP_UNSPECIFIED, Maneuver.OFF_RAMP_U_TURN_CLOCKWISE, Maneuver.OFF_RAMP_U_TURN_COUNTERCLOCKWISE ->
            fragment("maneuver_off_ramp")

        in Maneuver.ROUNDABOUT_CLOCKWISE..Maneuver.ROUNDABOUT_U_TURN_COUNTERCLOCKWISE ->
            if (roundaboutExit != null && roundaboutExit in 1..MAX_ROUNDABOUT_EXIT) {
                fragment("maneuver_roundabout_exit_$roundaboutExit")
            } else {
                fragment("maneuver_roundabout")
            }
        Maneuver.ROUNDABOUT_EXIT_CLOCKWISE, Maneuver.ROUNDABOUT_EXIT_COUNTERCLOCKWISE -> fragment("maneuver_roundabout_leave")

        Maneuver.DESTINATION -> ManeuverSpeech.Fragment("maneuver_destination", atManeuver = false)
        Maneuver.DESTINATION_LEFT -> ManeuverSpeech.Fragment("maneuver_destination_left", atManeuver = false)
        Maneuver.DESTINATION_RIGHT -> ManeuverSpeech.Fragment("maneuver_destination_right", atManeuver = false)

        // UNKNOWN, ferries and anything added to the SDK later.
        else -> ManeuverSpeech.Unmapped
    }

    private fun fragment(id: String) = ManeuverSpeech.Fragment(id)
}
