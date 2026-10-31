package cc.nekocat.waylamaps.alerts

/**
 * Counts down to each point ahead: it is announced once at each of [distancesM] it comes
 * within. A point first seen inside a step starts there (first seen at 180 m: 200, 100, 50).
 */
class AlertCountdown(private val distancesM: List<Int> = DEFAULT_DISTANCES_M) {
    data class Step(
        /** The step's distance, which is what is spoken. */
        val distanceM: Int,
        /** The first announcement of this point, which says what it is in full. */
        val first: Boolean,
    )

    /** Index into [distancesM] of the last step announced, by point id. */
    private val reached = mutableMapOf<Long, Int>()

    /** Points announced at least once; remove ids to let a point count down again. */
    val ids: MutableSet<Long> get() = reached.keys

    /** The step to announce for point [id] at [distanceM] ahead, or null if there is none new. */
    fun check(id: Long, distanceM: Double): Step? {
        // The closest step the point is within, e.g. 300 at 280 m.
        val index = distancesM.indexOfLast { distanceM <= it }
        if (index < 0) return null
        val previous = reached[id]
        if (previous != null && index <= previous) return null
        reached[id] = index
        return Step(distancesM[index], first = previous == null)
    }

    companion object {
        val DEFAULT_DISTANCES_M = listOf(400, 300, 200, 100, 50)
    }
}
