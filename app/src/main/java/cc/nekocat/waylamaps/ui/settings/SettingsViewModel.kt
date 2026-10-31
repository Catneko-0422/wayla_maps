package cc.nekocat.waylamaps.ui.settings

import androidx.lifecycle.ViewModel
import cc.nekocat.waylamaps.navigation.TravelMode
import cc.nekocat.waylamaps.navigation.TravelModePreference
import cc.nekocat.waylamaps.service.RidePreferences
import cc.nekocat.waylamaps.ui.confirmationPhrase
import cc.nekocat.waylamaps.voice.VoiceFeedback
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val travelModePreference: TravelModePreference,
    private val ridePreferences: RidePreferences,
    private val voice: VoiceFeedback,
) : ViewModel() {
    val travelMode = travelModePreference.mode
    val matchElevation = ridePreferences.matchElevation

    fun setTravelMode(mode: TravelMode) {
        if (mode == travelMode.value) return
        travelModePreference.set(mode)
        voice.speak(mode.confirmationPhrase())
    }

    fun setMatchElevation(enabled: Boolean) {
        ridePreferences.setMatchElevation(enabled)
        voice.speak(if (enabled) "elevation_filter_on" else "elevation_filter_off")
    }

    fun playVoiceDemo() = voice.speak("voice_demo")
}
