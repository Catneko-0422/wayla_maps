package cc.nekocat.waylamaps.alerts

data class SpeedCamera(
    val id: Long,
    val lat: Double,
    val lng: Double,
    /** Enforced travel direction in degrees (0 = north); null means both ways or unknown. */
    val bearingDeg: Int?,
    val limitKmh: Int?,
)

/** One position update, stripped of Android types. */
data class Fix(
    val lat: Double,
    val lng: Double,
    /** GPS course over ground in degrees, if the receiver reported one. */
    val bearingDeg: Double?,
    val speedMps: Double?,
)

data class CameraAhead(val camera: SpeedCamera, val distanceM: Double)
