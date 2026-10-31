package cc.nekocat.waylamaps.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.nekocat.waylamaps.alerts.AlertSession
import cc.nekocat.waylamaps.alerts.AlertSessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import kotlin.math.roundToInt

data class MainUiState(
    val running: Boolean = false,
    val waitingForGps: Boolean = false,
    val speedKmh: Int? = null,
    /** Distance to the next camera ahead, rounded to 10 m. */
    val nextCameraMeters: Int? = null,
    val nextCameraLimitKmh: Int? = null,
)

fun AlertSession.toUiState() = MainUiState(
    running = running,
    waitingForGps = running && !hasFix,
    speedKmh = speedKmh,
    nextCameraMeters = next?.let { (it.distanceM / 10).roundToInt() * 10 },
    nextCameraLimitKmh = next?.camera?.limitKmh,
)

@HiltViewModel
class MainViewModel @Inject constructor(sessionState: AlertSessionState) : ViewModel() {
    val uiState: StateFlow<MainUiState> = sessionState.session
        .map { it.toUiState() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), sessionState.session.value.toUiState())
}
