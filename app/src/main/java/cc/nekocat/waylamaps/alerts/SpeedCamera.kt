package cc.nekocat.waylamaps.alerts

/** Something on the road to warn about: a speed camera or an enforcement point. */
interface AlertPoint {
    val id: Long
    val lat: Double
    val lng: Double

    /** Enforced travel direction in degrees (0 = north); null means both ways or unknown. */
    val bearingDeg: Int?
}

data class SpeedCamera(
    override val id: Long,
    override val lat: Double,
    override val lng: Double,
    override val bearingDeg: Int?,
    val limitKmh: Int?,
    /** Location text from the dataset, e.g. "市民大道高架京站百貨前". */
    val address: String = "",
    /** 拍攝方向 as published; may carry notes such as "兼闖紅燈". */
    val direction: String = "",
) : AlertPoint

/** One position update, stripped of Android types. */
data class Fix(
    val lat: Double,
    val lng: Double,
    /** GPS course over ground in degrees, if the receiver reported one. */
    val bearingDeg: Double?,
    val speedMps: Double?,
)

/** [point] lies [distanceM] ahead (along the route while navigating). */
data class Ahead<T : AlertPoint>(val point: T, val distanceM: Double)

typealias CameraAhead = Ahead<SpeedCamera>

val CameraAhead.camera: SpeedCamera get() = point
