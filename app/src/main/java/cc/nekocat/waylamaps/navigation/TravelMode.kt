package cc.nekocat.waylamaps.navigation

import android.content.Context
import androidx.core.content.edit
import cc.nekocat.waylamaps.alerts.RoadAccess
import com.google.android.libraries.navigation.RoutingOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class TravelMode(val sdkTravelMode: Int, val avoidHighways: Boolean, val roadAccess: RoadAccess) {
    CAR(RoutingOptions.TravelMode.DRIVING, avoidHighways = false, RoadAccess.ALL),

    /** Google's two-wheeler routing: motorcycles and scooters, available in Taiwan. */
    SCOOTER(RoutingOptions.TravelMode.TWO_WHEELER, avoidHighways = false, RoadAccess.SURFACE_ONLY),

    /**
     * Heavy motorcycles (大型重機, 550 cc and up): car routes without freeways, which they may
     * not use. `avoidHighways` also keeps them off expressways, erring on the safe side.
     */
    HEAVY_MOTORCYCLE(RoutingOptions.TravelMode.DRIVING, avoidHighways = true, RoadAccess.NO_FREEWAYS),
}

/** The last chosen travel mode, shared by the phone and Android Auto. */
@Singleton
class TravelModePreference @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("navigation", Context.MODE_PRIVATE)
    private val _mode = MutableStateFlow(
        prefs.getString(KEY, null)?.let { runCatching { TravelMode.valueOf(it) }.getOrNull() } ?: TravelMode.SCOOTER,
    )
    val mode: StateFlow<TravelMode> = _mode.asStateFlow()

    fun set(mode: TravelMode) {
        _mode.value = mode
        prefs.edit { putString(KEY, mode.name) }
    }

    private companion object {
        const val KEY = "travel_mode"
    }
}
