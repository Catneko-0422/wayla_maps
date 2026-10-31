package cc.nekocat.waylamaps.navigation

/** Where a ride goes: stops on the way, in order, then the destination. */
data class Trip(
    val destination: PlaceSuggestion,
    val stops: List<PlaceSuggestion> = emptyList(),
) {
    /** Every place to reach, in order; the destination is last. */
    val waypoints: List<PlaceSuggestion> get() = stops + destination

    val canAddStop: Boolean get() = stops.size < MAX_STOPS

    /** Adds [place] as the last stop before the destination; the same place twice is ignored. */
    fun withStop(place: PlaceSuggestion): Trip {
        if (!canAddStop || waypoints.any { it.placeId == place.placeId }) return this
        return copy(stops = stops + place)
    }

    fun withoutStop(index: Int): Trip =
        if (index in stops.indices) copy(stops = stops.filterIndexed { i, _ -> i != index }) else this

    /** Swaps stop [index] with its neighbour [by] places away, e.g. -1 to ride to it earlier. */
    fun movingStop(index: Int, by: Int): Trip {
        val target = index + by
        if (index !in stops.indices || target !in stops.indices) return this
        val reordered = stops.toMutableList()
        reordered[index] = stops[target]
        reordered[target] = stops[index]
        return copy(stops = reordered)
    }

    /** The trip left after reaching the first [reached] waypoints. */
    fun remaining(reached: Int): Trip = copy(stops = stops.drop(reached))

    companion object {
        /** Enough for a fuel stop and a few sights; each stop is one more leg to plan. */
        const val MAX_STOPS = 4
    }
}
