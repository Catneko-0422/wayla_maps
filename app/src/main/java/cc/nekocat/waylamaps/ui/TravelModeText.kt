package cc.nekocat.waylamaps.ui

import androidx.annotation.StringRes
import cc.nekocat.waylamaps.R
import cc.nekocat.waylamaps.navigation.TravelMode

@StringRes
fun TravelMode.labelRes(): Int = when (this) {
    TravelMode.CAR -> R.string.travel_mode_car
    TravelMode.SCOOTER -> R.string.travel_mode_scooter
    TravelMode.HEAVY_MOTORCYCLE -> R.string.travel_mode_heavy_motorcycle
}

/** The phrase that confirms switching to this mode. */
fun TravelMode.confirmationPhrase(): String = when (this) {
    TravelMode.CAR -> "mode_car"
    TravelMode.SCOOTER -> "mode_scooter"
    TravelMode.HEAVY_MOTORCYCLE -> "mode_heavy_motorcycle"
}
