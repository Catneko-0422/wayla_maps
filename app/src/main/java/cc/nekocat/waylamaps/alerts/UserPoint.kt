package cc.nekocat.waylamaps.alerts

/** A reminder point the rider added; warned about in both directions. */
data class UserPoint(
    override val id: Long,
    override val lat: Double,
    override val lng: Double,
    val createdAt: Long,
) : AlertPoint {
    override val bearingDeg: Int? get() = null
}
