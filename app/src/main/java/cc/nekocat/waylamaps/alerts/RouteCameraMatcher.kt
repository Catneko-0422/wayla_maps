package cc.nekocat.waylamaps.alerts

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Finds speed cameras (or other [AlertPoint]s) on the route ahead while navigating, measuring
 * distance along the route. Use one instance per kind of point.
 *
 * A camera counts when it lies within [maxOffsetM] of the route polyline, ahead of the current
 * position along the route, and (if its direction is known) enforces the direction the route
 * travels there. This rules out cameras on parallel roads and cross streets, which
 * [CameraAheadDetector] cannot tell apart without a route.
 *
 * Not thread-safe: feed it from a single coroutine.
 */
class RouteCameraMatcher(
    /** Distances to announce a point at; see [AlertCountdown]. */
    countdownM: List<Int> = AlertCountdown.DEFAULT_DISTANCES_M,
    private val lookAheadM: Double = 1000.0,
    private val maxOffsetM: Double = 25.0,
    private val directionToleranceDeg: Double = 45.0,
    /** How far off the route the position may be and still be placed on it. */
    private val maxPositionOffsetM: Double = 60.0,
    /** Progress is searched from this far behind the last known progress, for GPS jitter. */
    private val backtrackM: Double = 100.0,
    /** How far past an announced point counts as passed. */
    private val passMarginM: Double = 30.0,
) {
    private var points: List<GeoPoint> = emptyList()

    /** Distance along the route at each point. */
    private var cumulative: DoubleArray = DoubleArray(0)
    private var progressM: Double? = null
    private val countdown = AlertCountdown(countdownM)
    private val announced get() = countdown.ids

    /** Announced points not passed yet, by their distance along the route. */
    private val awaitingPass = mutableMapOf<Long, Double>()

    val hasRoute: Boolean get() = points.size >= 2

    /** Replaces the route, e.g. after a reroute. Cameras already announced stay announced. */
    fun setRoute(route: List<GeoPoint>) {
        points = route
        cumulative = DoubleArray(route.size)
        for (i in 1 until route.size) {
            cumulative[i] = cumulative[i - 1] + Geo.distanceMeters(route[i - 1].lat, route[i - 1].lng, route[i].lat, route[i].lng)
        }
        progressM = null
        // Positions along the old route mean nothing on the new one.
        awaitingPass.clear()
    }

    fun <T : AlertPoint> update(fix: Fix, candidates: List<T>): CameraAheadDetector.Result<T> {
        // Forget cameras that dropped out of the search area, so a later pass can announce them again.
        val candidateIds = candidates.mapTo(HashSet()) { it.id }
        announced.retainAll(candidateIds)
        awaitingPass.keys.retainAll(candidateIds)

        val position = locate(fix) ?: return CameraAheadDetector.Result<T>(next = null, announce = null)
        progressM = position.alongM

        val passedId = awaitingPass.entries.firstOrNull { (_, alongM) -> position.alongM >= alongM + passMarginM }?.key
        passedId?.let { awaitingPass.remove(it) }
        val passed = passedId?.let { id -> candidates.firstOrNull { it.id == id } }

        val next = candidates
            .mapNotNull { camera -> measure(camera, position.alongM) }
            .minByOrNull { it.distanceM }
        val step = next?.let { countdown.check(it.point.id, it.distanceM) }
        val announce = next?.takeIf { step != null }
        announce?.let { awaitingPass[it.point.id] = position.alongM + it.distanceM }
        return CameraAheadDetector.Result(next, announce, passed, step)
    }

    /** Places the fix on the route, preferring the stretch just ahead of the last progress. */
    private fun locate(fix: Fix): Projection? {
        if (!hasRoute) return null
        val last = progressM
        if (last != null) {
            nearest(fix.lat, fix.lng, fromM = last - backtrackM, toM = last + lookAheadM)
                ?.takeIf { it.offsetM <= maxPositionOffsetM }
                ?.let { return it }
        }
        return nearest(fix.lat, fix.lng, fromM = Double.NEGATIVE_INFINITY, toM = Double.POSITIVE_INFINITY)
            ?.takeIf { it.offsetM <= maxPositionOffsetM }
    }

    private fun <T : AlertPoint> measure(camera: T, positionAlongM: Double): Ahead<T>? {
        val onRoute = nearest(camera.lat, camera.lng, fromM = positionAlongM, toM = positionAlongM + lookAheadM)
            ?.takeIf { it.offsetM <= maxOffsetM && it.alongM > positionAlongM }
            ?: return null
        val enforced = camera.bearingDeg
        if (enforced != null && Geo.angleBetween(enforced.toDouble(), onRoute.segmentBearingDeg) > directionToleranceDeg) {
            return null
        }
        return Ahead(camera, onRoute.alongM - positionAlongM)
    }

    private data class Projection(val alongM: Double, val offsetM: Double, val segmentBearingDeg: Double)

    /** Closest point on the segments overlapping [fromM, toM] of the route. */
    private fun nearest(lat: Double, lng: Double, fromM: Double, toM: Double): Projection? {
        var best: Projection? = null
        for (i in 0 until points.size - 1) {
            if (cumulative[i + 1] < fromM) continue
            if (cumulative[i] > toM) break
            val projection = project(lat, lng, i)
            if (best == null || projection.offsetM < best.offsetM) best = projection
        }
        return best
    }

    /** Projects onto segment [i] in a local flat approximation, fine at segment scale. */
    private fun project(lat: Double, lng: Double, i: Int): Projection {
        val a = points[i]
        val b = points[i + 1]
        val metersPerDegLng = METERS_PER_DEGREE_LAT * cos(Math.toRadians(a.lat))
        val bx = (b.lng - a.lng) * metersPerDegLng
        val by = (b.lat - a.lat) * METERS_PER_DEGREE_LAT
        val px = (lng - a.lng) * metersPerDegLng
        val py = (lat - a.lat) * METERS_PER_DEGREE_LAT
        val lengthSq = bx * bx + by * by
        val t = if (lengthSq == 0.0) 0.0 else ((px * bx + py * by) / lengthSq).coerceIn(0.0, 1.0)
        val dx = px - t * bx
        val dy = py - t * by
        return Projection(
            alongM = cumulative[i] + t * (cumulative[i + 1] - cumulative[i]),
            offsetM = sqrt(dx * dx + dy * dy),
            segmentBearingDeg = Geo.bearingDegrees(a.lat, a.lng, b.lat, b.lng),
        )
    }

    private companion object {
        const val METERS_PER_DEGREE_LAT = 111_195.0
    }
}
