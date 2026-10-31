package cc.nekocat.waylamaps.service

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, LIGHT, DARK }

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

    private val _autoLock = MutableStateFlow(prefs.getBoolean(KEY_AUTO_LOCK, false))

    /** Lock the control panel while moving. */
    val autoLock: StateFlow<Boolean> = _autoLock.asStateFlow()

    fun setAutoLock(enabled: Boolean) {
        _autoLock.value = enabled
        prefs.edit { putBoolean(KEY_AUTO_LOCK, enabled) }
    }

    private val _theme = MutableStateFlow(
        prefs.getString(KEY_THEME, null)?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
    )

    /** Light or dark UI; SYSTEM follows the phone, and the map then switches by time of day. */
    val theme: StateFlow<ThemeMode> = _theme.asStateFlow()

    fun setTheme(mode: ThemeMode) {
        _theme.value = mode
        prefs.edit { putString(KEY_THEME, mode.name) }
    }

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_MATCH_ELEVATION = "match_elevation"
        const val KEY_AUTO_LOCK = "auto_lock"
    }
}
