package cc.nekocat.waylamaps.ui.main

import cc.nekocat.waylamaps.alerts.AlertSession
import cc.nekocat.waylamaps.alerts.camera
import kotlin.math.roundToInt

/** What the speed and speed camera widgets show. */
data class MainUiState(
    val running: Boolean = false,
    val waitingForGps: Boolean = false,
    val speedKmh: Int? = null,
    /** Distance to the next camera ahead, rounded to 10 m. */
    val nextCameraMeters: Int? = null,
    val nextCameraLimitKmh: Int? = null,
    /** Distance to the next enforcement intersection ahead, rounded to 10 m. */
    val nextEnforcementMeters: Int? = null,
    /** Average speed so far in a zone, km/h; null outside zones or before it is known. */
    val zoneAverageKmh: Int? = null,
    val inAverageZone: Boolean = false,
    val zoneTraveledKm: Double? = null,
    val zoneLengthKm: Double? = null,
)

fun AlertSession.toUiState() = MainUiState(
    running = running,
    waitingForGps = running && !hasFix,
    speedKmh = speedKmh,
    nextCameraMeters = next?.let { roundTo10(it.distanceM) },
    nextCameraLimitKmh = next?.camera?.limitKmh,
    nextEnforcementMeters = nextEnforcement?.let { roundTo10(it.distanceM) },
    zoneAverageKmh = averageZone?.averageKmh,
    inAverageZone = averageZone != null,
    zoneTraveledKm = averageZone?.traveledM?.div(1000),
    zoneLengthKm = averageZone?.lengthM?.div(1000),
)

private fun roundTo10(meters: Double) = (meters / 10).roundToInt() * 10
