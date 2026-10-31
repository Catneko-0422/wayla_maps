package cc.nekocat.waylamaps.alerts

/**
 * Speed from two consecutive positions, for fixes that carry none (road-snapped positions often
 * do not). Null when the gap is too short to be meaningful or too long to still be current.
 */
fun speedBetween(previous: GeoPoint, previousNanos: Long, current: GeoPoint, currentNanos: Long): Double? {
    val seconds = (currentNanos - previousNanos) / 1e9
    if (seconds !in 0.5..5.0) return null
    return Geo.distanceMeters(previous.lat, previous.lng, current.lat, current.lng) / seconds
}

/**
 * Says "over the limit" once per announced camera, while it is within [rangeM] ahead and the
 * speed is above its limit. States the fact only: no tolerance, no advice.
 */
class OverLimitWarner(private val rangeM: Double = 300.0) {
    private val warned = mutableSetOf<Long>()

    fun shouldWarn(pointId: Long, distanceM: Double, limitKmh: Int?, speedKmh: Double?): Boolean {
        if (limitKmh == null || speedKmh == null || distanceM > rangeM) return false
        if (speedKmh <= limitKmh) return false
        return warned.add(pointId)
    }
}

/**
 * Reports each point once when the rider is slow and close to it, e.g. a no-stopping enforcement
 * zone, which only matters when about to stop. A point can be reported again after leaving it.
 */
class NearbyOnceNotifier(
    private val radiusM: Double = 50.0,
    private val maxSpeedKmh: Double = 10.0,
    private val resetDistanceM: Double = 200.0,
) {
    private val reported = mutableSetOf<Long>()

    fun <T : AlertPoint> update(fix: Fix, points: List<T>): T? {
        val distances = points.associateWith { Geo.distanceMeters(fix.lat, fix.lng, it.lat, it.lng) }
        reported.retainAll(distances.filterValues { it <= resetDistanceM }.keys.map { it.id }.toSet())
        val speedKmh = (fix.speedMps ?: return null) * 3.6
        if (speedKmh > maxSpeedKmh) return null
        return distances.entries
            .filter { (point, distance) -> distance <= radiusM && point.id !in reported }
            .minByOrNull { it.value }
            ?.key
            ?.also { reported += it.id }
    }
}
