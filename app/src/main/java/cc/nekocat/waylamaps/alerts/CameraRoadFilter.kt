package cc.nekocat.waylamaps.alerts

/** Which roads a vehicle may use, as far as alerts are concerned. */
enum class RoadAccess {
    /** Cars: every road. */
    ALL,

    /** Heavy motorcycles (大型重機): everything except freeways (國道). */
    NO_FREEWAYS,

    /** Scooters: surface roads only; expressways only at grade. */
    SURFACE_ONLY,
}

/**
 * Drops cameras and enforcement points on a different road level from the rider, using the
 * dataset's address text.
 *
 * Elevated roads often run right above surface roads (市民大道 and its 高架), so position and
 * heading alone cannot tell them apart.
 */
object CameraRoadFilter {
    private const val ELEVATED = "高架"
    private const val FREEWAY = "國道"
    private val EXPRESSWAYS = listOf("快速道路", "快速公路")

    /** An intersection ("同安街口"), not an exit or entrance ("隧道出口"). */
    private val INTERSECTION = Regex("(?<![出入])口")

    /**
     * @param access roads closed to the vehicle never have relevant cameras. Scooters use
     *   expressways only at grade, where cameras sit at an intersection ("…口").
     * @param currentRoad the road being travelled, when navigation knows it.
     * @param matchElevation the "skip cameras under or over me" setting: a camera counts only
     *   when it is elevated exactly when the current road is (scooters are always at street level).
     */
    fun isRelevant(address: String, access: RoadAccess, currentRoad: String?, matchElevation: Boolean = true): Boolean {
        if (access != RoadAccess.ALL && FREEWAY in address) return false
        if (access == RoadAccess.SURFACE_ONLY && isAboveGradeExpressway(address)) return false
        if (!matchElevation) return true
        val riderElevated = when {
            access == RoadAccess.SURFACE_ONLY -> false
            currentRoad != null -> ELEVATED in currentRoad
            else -> return true
        }
        return (ELEVATED in address) == riderElevated
    }

    private fun isAboveGradeExpressway(address: String): Boolean =
        EXPRESSWAYS.any { it in address } && !INTERSECTION.containsMatchIn(address)
}
