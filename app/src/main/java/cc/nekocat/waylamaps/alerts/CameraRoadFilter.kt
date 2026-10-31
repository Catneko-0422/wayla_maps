package cc.nekocat.waylamaps.alerts

/**
 * Drops cameras on a different road level from the rider, using the dataset's address text.
 *
 * Elevated roads often run right above surface roads (市民大道 and its 高架), so position and
 * heading alone cannot tell them apart.
 */
object CameraRoadFilter {
    private const val ELEVATED = "高架"
    private val CLOSED_TO_SCOOTERS = listOf("國道", ELEVATED)
    private val EXPRESSWAYS = listOf("快速道路", "快速公路")

    /** An intersection ("同安街口"), not an exit or entrance ("隧道出口"). */
    private val INTERSECTION = Regex("(?<![出入])口")

    /**
     * @param scooter scooters never use freeways and elevated roads, and use expressways only
     *   at grade, where cameras sit at an intersection ("…口").
     * @param currentRoad the road being travelled, when navigation knows it: a camera counts only
     *   when it is elevated exactly when that road is.
     */
    fun isRelevant(camera: SpeedCamera, scooter: Boolean, currentRoad: String?): Boolean {
        val address = camera.address
        if (scooter && isClosedToScooters(address)) return false
        if (currentRoad != null && (ELEVATED in address) != (ELEVATED in currentRoad)) return false
        return true
    }

    private fun isClosedToScooters(address: String): Boolean =
        CLOSED_TO_SCOOTERS.any { it in address } || (EXPRESSWAYS.any { it in address } && !INTERSECTION.containsMatchIn(address))
}
