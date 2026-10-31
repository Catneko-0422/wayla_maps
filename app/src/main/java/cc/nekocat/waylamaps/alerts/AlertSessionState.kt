package cc.nekocat.waylamaps.alerts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

data class AlertSession(
    val running: Boolean = false,
    /** False until the first GPS fix arrives after starting. */
    val hasFix: Boolean = false,
    val speedKmh: Int? = null,
    val next: CameraAhead? = null,
    /** Nearest enforcement intersection ahead; shown but only announced while navigating. */
    val nextEnforcement: Ahead<EnforcementPoint>? = null,
    /** Set while riding through an average speed zone. */
    val averageZone: AverageSpeedTracker.Zone? = null,
)

/** Shares the speed camera service's state with the UI. */
@Singleton
class AlertSessionState @Inject constructor() {
    private val _session = MutableStateFlow(AlertSession())
    val session: StateFlow<AlertSession> = _session.asStateFlow()

    fun update(transform: (AlertSession) -> AlertSession) = _session.update(transform)
}
