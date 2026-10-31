package cc.nekocat.waylamaps.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import cc.nekocat.waylamaps.character.Character
import cc.nekocat.waylamaps.character.CharacterPackImporter
import cc.nekocat.waylamaps.character.CharacterRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import androidx.lifecycle.viewModelScope
import cc.nekocat.waylamaps.alerts.UserPoint
import cc.nekocat.waylamaps.data.UserPointRepository
import cc.nekocat.waylamaps.navigation.TravelMode
import cc.nekocat.waylamaps.navigation.TravelModePreference
import cc.nekocat.waylamaps.service.RidePreferences
import cc.nekocat.waylamaps.service.ThemeMode
import cc.nekocat.waylamaps.ui.confirmationPhrase
import cc.nekocat.waylamaps.voice.VoiceFeedback
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val travelModePreference: TravelModePreference,
    private val ridePreferences: RidePreferences,
    private val voice: VoiceFeedback,
    private val userPointRepository: UserPointRepository,
    private val characterRepository: CharacterRepository,
) : ViewModel() {
    val characters = characterRepository.characters
    val selectedCharacter = characterRepository.selected

    private val _importMessage = MutableStateFlow<CharacterPackImporter.Result?>(null)

    /** Outcome of the last pack import, shown under the import button. */
    val importMessage: StateFlow<CharacterPackImporter.Result?> = _importMessage.asStateFlow()

    val travelMode = travelModePreference.mode
    val matchElevation = ridePreferences.matchElevation
    val autoLock = ridePreferences.autoLock
    val theme = ridePreferences.theme
    val userPoints: StateFlow<List<UserPoint>> =
        userPointRepository.all.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setTravelMode(mode: TravelMode) {
        if (mode == travelMode.value) return
        travelModePreference.set(mode)
        voice.speak(mode.confirmationPhrase())
    }

    fun setMatchElevation(enabled: Boolean) {
        ridePreferences.setMatchElevation(enabled)
        voice.speak(if (enabled) "elevation_filter_on" else "elevation_filter_off")
    }

    fun setAutoLock(enabled: Boolean) {
        ridePreferences.setAutoLock(enabled)
        voice.speak(if (enabled) "lock_on" else "lock_off")
    }

    fun setTheme(mode: ThemeMode) = ridePreferences.setTheme(mode)

    fun playVoiceDemo() = voice.speak("voice_demo")

    fun selectCharacter(character: Character) {
        characterRepository.select(character.id)
        voice.speak("voice_demo")
    }

    fun deleteCharacter(character: Character) = characterRepository.delete(character)

    fun importCharacter(uri: Uri) {
        viewModelScope.launch { _importMessage.value = characterRepository.import(uri) }
    }

    fun deletePoint(point: UserPoint) {
        viewModelScope.launch {
            userPointRepository.delete(point.id)
            voice.speak("point_deleted")
        }
    }
}
