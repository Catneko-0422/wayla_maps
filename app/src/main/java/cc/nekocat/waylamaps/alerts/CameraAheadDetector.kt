package cc.nekocat.waylamaps.alerts

import kotlin.math.roundToInt

/**
 * Finds the speed camera (or other [AlertPoint]) ahead in the direction of travel and decides
 * when to announce it. Use one instance per kind of point.
 *
 * Not thread-safe: feed it fixes from a single coroutine.
 */
class CameraAheadDetector(
    /** Distances to announce a point at; see [AlertCountdown]. */
    countdownM: List<Int> = AlertCountdown.DEFAULT_DISTANCES_M,
    private val lookAheadM: Double = 1000.0,
    /** Below this speed the GPS course is noise, so the last good heading is kept. */
    private val minHeadingSpeedMps: Double = 10.0 / 3.6,
    /** How far off the heading a camera may be and still count as ahead. */
    private val aheadToleranceDeg: Double = 30.0,
    /** How far the camera's enforced direction may differ from the heading. */
    private val directionToleranceDeg: Double = 45.0,
    /** A passed camera must be this far behind before it can be announced again. */
    private val passedDistanceM: Double = 100.0,
    /** An announced camera this far away can be announced again. */
    private val resetDistanceM: Double = 500.0,
    /** How far behind an announced point must be to count as passed. */
    private val passMarginM: Double = 30.0,
) {
    data class Result<T : AlertPoint>(
        /** Nearest point ahead within [lookAheadM], for display. */
        val next: Ahead<T>?,
        /** Set when [next] has just reached a step of its countdown (see [AlertCountdown]). */
        val announce: Ahead<T>?,
        /** An announced point that is now [passMarginM] behind, reported once. */
        val passed: T? = null,
        /** The countdown step [announce] reached. */
        val step: AlertCountdown.Step? = null,
    )

    private var heading: Double? = null
    private val countdown = AlertCountdown(countdownM)
    private val announced get() = countdown.ids
    private val awaitingPass = mutableSetOf<Long>()

    fun <T : AlertPoint> update(fix: Fix, candidates: List<T>): Result<T> {
        if (fix.bearingDeg != null && (fix.speedMps ?: 0.0) >= minHeadingSpeedMps) {
            heading = fix.bearingDeg
        }

        val measured = candidates.map { camera ->
            Measured(
                point = camera,
                distanceM = Geo.distanceMeters(fix.lat, fix.lng, camera.lat, camera.lng),
                bearingToCamera = Geo.bearingDegrees(fix.lat, fix.lng, camera.lat, camera.lng),
            )
        }
        forgetPassedPoints(measured)

        val currentHeading = heading ?: return Result<T>(next = null, announce = null)
        val passed = measured.firstOrNull { m ->
            m.point.id in awaitingPass && m.distanceM >= passMarginM &&
                Geo.angleBetween(m.bearingToCamera, currentHeading) > 90.0
        }?.point
        passed?.let { awaitingPass.remove(it.id) }
        val next = measured
            .filter { it.distanceM <= lookAheadM && it.isAhead(currentHeading) }
            .minByOrNull { it.distanceM }
            ?.let { Ahead(it.point, it.distanceM) }

        val step = next?.let { countdown.check(it.point.id, it.distanceM) }
        val announce = next?.takeIf { step != null }
        announce?.let { awaitingPass += it.point.id }
        return Result(next, announce, passed, step)
    }

    private fun forgetPassedPoints(measured: List<Measured<*>>) {
        val byId = measured.associateBy { it.point.id }
        awaitingPass.retainAll(byId.keys)
        val currentHeading = heading
        announced.removeAll { id ->
            val m = byId[id] ?: return@removeAll true
            val behind = currentHeading != null &&
                Geo.angleBetween(m.bearingToCamera, currentHeading) > 90.0 &&
                m.distanceM > passedDistanceM
            behind || m.distanceM > resetDistanceM
        }
    }

    private fun Measured<*>.isAhead(heading: Double): Boolean {
        if (Geo.angleBetween(bearingToCamera, heading) > aheadToleranceDeg) return false
        val enforced = point.bearingDeg ?: return true
        return Geo.angleBetween(enforced.toDouble(), heading) <= directionToleranceDeg
    }

    private data class Measured<T : AlertPoint>(val point: T, val distanceM: Double, val bearingToCamera: Double)

    companion object {
        /** Distance to speak, rounded to the nearest 50 m and never below 50 m. */
        fun spokenDistanceMeters(distanceM: Double): Int =
            ((distanceM / 50.0).roundToInt() * 50).coerceAtLeast(50)
    }
}
