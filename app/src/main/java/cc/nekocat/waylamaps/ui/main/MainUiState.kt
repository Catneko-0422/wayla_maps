package cc.nekocat.waylamaps.ui.main

import cc.nekocat.waylamaps.alerts.AlertSession
import kotlin.math.roundToInt

/** What the speed and speed camera widgets show. */
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
