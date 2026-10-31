package cc.nekocat.waylamaps.alerts

enum class EnforcementKind {
    /** Red lights, failing to yield, illegal turns, intersection speeding and the like. */
    INTERSECTION,

    /** Only illegal parking: irrelevant to someone driving through. */
    PARKING,

    /** Only vehicle-type restrictions (禁行車種). */
    VEHICLE_BAN,
}

/** A traffic enforcement (科技執法) point from a county police dataset. */
data class EnforcementPoint(
    val id: Long,
    val lat: Double,
    val lng: Double,
    val location: String,
    /** 取締項目 as published, e.g. "闖紅燈、不停讓行人". */
    val items: String,
    /** Enforced travel direction in degrees (0 = north); null means both ways or unknown. */
    val bearingDeg: Int?,
    val limitKmh: Int?,
    val kind: EnforcementKind,
)
