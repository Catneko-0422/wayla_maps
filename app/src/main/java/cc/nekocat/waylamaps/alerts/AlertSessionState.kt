package cc.nekocat.waylamaps.alerts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

data class AlertSession(
    val running: Boolean = false,
    val speedKmh: Int? = null,
    val next: CameraAhead? = null,
)

/** Shares the speed camera service's state with the UI. */
@Singleton
class AlertSessionState @Inject constructor() {
    private val _session = MutableStateFlow(AlertSession())
    val session: StateFlow<AlertSession> = _session.asStateFlow()

    fun update(transform: (AlertSession) -> AlertSession) = _session.update(transform)
}
