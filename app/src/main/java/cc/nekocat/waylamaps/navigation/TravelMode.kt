package cc.nekocat.waylamaps.navigation

import android.content.Context
import androidx.core.content.edit
import com.google.android.libraries.navigation.RoutingOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class TravelMode(val sdkTravelMode: Int) {
    CAR(RoutingOptions.TravelMode.DRIVING),

    /** Google's two-wheeler routing: motorcycles and scooters, available in Taiwan. */
    SCOOTER(RoutingOptions.TravelMode.TWO_WHEELER),
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
