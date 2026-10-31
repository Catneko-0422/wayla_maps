package cc.nekocat.waylamaps.alerts

import kotlin.math.abs

/**
 * Average speed zones (區間測速) in the speed camera data. Each zone is one row with one
 * coordinate (its start camera); the extent is only in the address text, as two mileages
 * ("台9線129k+404m至136k+883m", "台26線尖山段8.5-13.7K").
 */
object AverageSpeedZones {
    private const val ZONE = "區間"
    private const val NUMBER = "(\\d+(?:\\.\\d+)?)"
    private const val UNIT = "(?:[kK]|公里)?"
    private const val METERS = "(?:\\s*\\+\\s*(\\d+)\\s*[mM])?"
    private val RANGE = Regex("$NUMBER\\s*$UNIT$METERS\\s*(?:\\([^)]*\\))?\\s*(?:至|-|~|～)\\s*$NUMBER\\s*$UNIT$METERS")

    /** Several ranges or directions in one row; the length would be a guess. */
    private val SEVERAL = Regex("及|；|;|/")
    private val HAS_UNIT = Regex("[kK]|公里")
    private val PLAUSIBLE_M = 300.0..30_000.0

    fun isZone(camera: SpeedCamera): Boolean = ZONE in camera.address || ZONE in camera.direction

    /** Zone length in meters from the mileage text, or null when it cannot be read reliably. */
    fun lengthMeters(address: String): Double? {
        if (SEVERAL.containsMatchIn(address) || !HAS_UNIT.containsMatchIn(address)) return null
        val m = RANGE.find(address) ?: return null
        val (a, aMeters, b, bMeters) = m.destructured
        val from = a.toDouble() * 1000 + (aMeters.toDoubleOrNull() ?: 0.0)
        val to = b.toDouble() * 1000 + (bMeters.toDoubleOrNull() ?: 0.0)
        return abs(to - from).takeIf { it in PLAUSIBLE_M }
    }
}

/**
 * Follows a ride through an average speed zone after its start camera is passed: distance
 * covered and average speed, for display, and when the zone is surely over.
 *
 * The end is announced only after the zone length plus [endMarginM], so the rider is never told
 * the zone is over before it is. Zones of unknown length are shown until [unknownLengthLimitM]
 * and then dropped without an announcement.
 */
class AverageSpeedTracker(
    private val endMarginM: Double = 300.0,
    private val unknownLengthLimitM: Double = 20_000.0,
) {
    data class Zone(val limitKmh: Int?, val lengthM: Double?, val traveledM: Double, val averageKmh: Int?)

    var zone: Zone? = null
        private set

    private var startedAt = 0L

    fun enter(now: Long, limitKmh: Int?, lengthM: Double?) {
        startedAt = now
        zone = Zone(limitKmh, lengthM, traveledM = 0.0, averageKmh = null)
    }

    /** @return true once, when a zone of known length has surely ended. */
    fun onMove(now: Long, deltaM: Double): Boolean {
        val current = zone ?: return false
        val traveled = current.traveledM + deltaM
        val hours = (now - startedAt) / 3_600_000.0
        val average = if (hours > 0 && traveled > 0) (traveled / 1000 / hours).toInt() else null
        val length = current.lengthM
        if (length != null && traveled >= length + endMarginM) {
            zone = null
            return true
        }
        if (length == null && traveled >= unknownLengthLimitM) {
            zone = null
            return false
        }
        zone = current.copy(traveledM = traveled, averageKmh = average)
        return false
    }

    fun clear() {
        zone = null
    }
}
