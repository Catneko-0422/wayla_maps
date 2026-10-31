package cc.nekocat.waylamaps.service

import android.app.Application
import cc.nekocat.waylamaps.navigation.GuidanceState
import cc.nekocat.waylamaps.navigation.NavigationSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import cc.nekocat.waylamaps.voice.SpeechState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps [RideService] running while it is needed: when speed camera alerts are switched on, or
 * while navigating (alerts and turn prompts always run during guidance), and until it has
 * finished speaking.
 */
@Singleton
class RideController @Inject constructor(
    private val application: Application,
    private val navigation: NavigationSession,
    private val speech: SpeechState,
) {
    private val _alertsEnabled = MutableStateFlow(false)
    val alertsEnabled: StateFlow<Boolean> = _alertsEnabled.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        scope.launch {
            combine(_alertsEnabled, navigation.state) { alerts, guidance ->
                // Arrived keeps the service a little longer so the arrival prompt is heard.
                alerts || guidance is GuidanceState.Guiding || guidance is GuidanceState.Arrived
            }
                .distinctUntilChanged()
                .collectLatest { needed ->
                    if (needed) {
                        RideService.start(application)
                        return@collectLatest
                    }
                    // Let the service say its last sentence ("alerts off") before it goes. Switching
                    // back on meanwhile cancels this wait and the service simply keeps running.
                    delay(PROMPT_START_MS)
                    withTimeoutOrNull(MAX_FAREWELL_MS) { speech.current.first { it == null } }
                    RideService.stop(application)
                }
        }
    }

    /** Callers make sure location permission is granted before enabling. */
    fun setAlertsEnabled(enabled: Boolean) {
        _alertsEnabled.value = enabled
    }

    /** The notification's stop action: end navigation and alerts together. */
    fun stopAll() {
        _alertsEnabled.value = false
        if (navigation.state.value !is GuidanceState.Idle) navigation.stop()
    }

    private companion object {
        /** Time for the service to queue its prompt after the switch. */
        const val PROMPT_START_MS = 300L
        const val MAX_FAREWELL_MS = 6_000L
    }
}
