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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps [RideService] running exactly while it is needed: when speed camera alerts are switched
 * on, or while navigating (alerts and turn prompts always run during guidance).
 */
@Singleton
class RideController @Inject constructor(
    private val application: Application,
    private val navigation: NavigationSession,
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
                .collect { needed -> if (needed) RideService.start(application) else RideService.stop(application) }
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
}
