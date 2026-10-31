package cc.nekocat.waylamaps.service

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Ride settings from the settings screen. The travel mode lives in TravelModePreference. */
@Singleton
class RidePreferences @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences("ride", Context.MODE_PRIVATE)

    private val _matchElevation = MutableStateFlow(prefs.getBoolean(KEY_MATCH_ELEVATION, true))

    /** Skip cameras on elevated roads above (or surface roads below) the rider. */
    val matchElevation: StateFlow<Boolean> = _matchElevation.asStateFlow()

    fun setMatchElevation(enabled: Boolean) {
        _matchElevation.value = enabled
        prefs.edit { putBoolean(KEY_MATCH_ELEVATION, enabled) }
    }

    private companion object {
        const val KEY_MATCH_ELEVATION = "match_elevation"
    }
}
