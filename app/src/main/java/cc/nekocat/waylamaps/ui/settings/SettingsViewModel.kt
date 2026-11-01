package cc.nekocat.waylamaps.ui.settings

import android.app.Application
import android.net.Uri
import java.io.File
import cc.nekocat.waylamaps.voice.ondevice.OnDeviceSpeechSynthesizer
import android.os.SystemClock
import android.media.MediaPlayer
import android.media.AudioAttributes
import androidx.work.WorkInfo
import androidx.work.WorkManager
import cc.nekocat.waylamaps.data.OpenDataUpdateWorker
import cc.nekocat.waylamaps.data.SpeedCameraImporter
import cc.nekocat.waylamaps.data.enforcement.EnforcementImporter
import cc.nekocat.waylamaps.voice.VoiceBoost
import cc.nekocat.waylamaps.voice.VoiceMode
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
    private val application: Application,
    private val speedCameras: SpeedCameraImporter,
    private val enforcement: EnforcementImporter,
    private val onDeviceSpeech: OnDeviceSpeechSynthesizer,
) : ViewModel() {
    val characters = characterRepository.characters
    val selectedCharacter = characterRepository.selected

    private val _importMessage = MutableStateFlow<CharacterPackImporter.Result?>(null)

    /** Outcome of the last pack import, shown in a dialog until dismissed. */
    val importMessage: StateFlow<CharacterPackImporter.Result?> = _importMessage.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    val voiceMode = ridePreferences.voiceMode
    val voiceBoost = ridePreferences.voiceBoost

    /** What the installed open data covers, for the data section. */
    data class DataSummary(val cameras: Int, val cameraVersion: String?, val enforcementPoints: Int)

    enum class UpdateState { IDLE, RUNNING, DONE, FAILED }

    private val _data = MutableStateFlow<DataSummary?>(null)
    val data: StateFlow<DataSummary?> = _data.asStateFlow()

    private val _update = MutableStateFlow(UpdateState.IDLE)
    val update: StateFlow<UpdateState> = _update.asStateFlow()

    init {
        refreshData()
    }

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
        viewModelScope.launch {
            _importing.value = true
            try {
                _importMessage.value = characterRepository.import(uri)
            } finally {
                _importing.value = false
            }
        }
    }

    fun dismissImportMessage() {
        _importMessage.value = null
    }

    fun setVoiceMode(mode: VoiceMode) = ridePreferences.setVoiceMode(mode)

    private val _onDeviceTest = MutableStateFlow<String?>(null)

    /** Debug builds: the last on-device road name test, for checking the phone's own voice model. */
    val onDeviceTest: StateFlow<String?> = _onDeviceTest.asStateFlow()

    fun testOnDeviceRoadName() {
        val voice = selectedCharacter.value.manifest.roadNames ?: run {
            _onDeviceTest.value = "no road voice"
            return
        }
        _onDeviceTest.value = "…"
        viewModelScope.launch {
            val started = SystemClock.elapsedRealtime()
            val audio = onDeviceSpeech.synthesize(ON_DEVICE_TEST_ROAD, voice)
            val ms = SystemClock.elapsedRealtime() - started
            if (audio == null) {
                _onDeviceTest.value = "failed after $ms ms"
                return@launch
            }
            _onDeviceTest.value = "$ON_DEVICE_TEST_ROAD: $ms ms"
            val file = File(application.cacheDir, "ondevice-test.wav").apply { writeBytes(audio) }
            MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).build())
                setDataSource(file.path)
                setOnCompletionListener { it.release() }
                prepare()
                start()
            }
        }
    }

    /** Plays a sample at the new level, so the rider hears the difference. */
    fun setVoiceBoost(boost: VoiceBoost) {
        ridePreferences.setVoiceBoost(boost)
        voice.speak("voice_demo")
    }

    fun updateDataNow() {
        if (_update.value == UpdateState.RUNNING) return
        val id = OpenDataUpdateWorker.runNow(application)
        _update.value = UpdateState.RUNNING
        viewModelScope.launch {
            WorkManager.getInstance(application).getWorkInfoByIdFlow(id).collect { info ->
                when (info?.state) {
                    WorkInfo.State.SUCCEEDED -> {
                        _update.value = UpdateState.DONE
                        refreshData()
                    }
                    // A retry means the download failed; WorkManager tries again later.
                    WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> _update.value = UpdateState.FAILED
                    WorkInfo.State.ENQUEUED -> if (info.runAttemptCount > 0) _update.value = UpdateState.FAILED
                    else -> Unit
                }
            }
        }
    }

    private fun refreshData() {
        viewModelScope.launch {
            _data.value = DataSummary(speedCameras.count(), speedCameras.installedVersion(), enforcement.count())
        }
    }

    fun deletePoint(point: UserPoint) {
        viewModelScope.launch {
            userPointRepository.delete(point.id)
            voice.speak("point_deleted")
        }
    }

    private companion object {
        const val ON_DEVICE_TEST_ROAD = "重慶北路一段"
    }
}
