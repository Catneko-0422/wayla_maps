package cc.nekocat.waylamaps.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TripTest {
    private fun place(id: String) = PlaceSuggestion(id, id, "", distanceMeters = null)

    private val wuling = place("wuling")
    private val fuel = place("fuel")
    private val cafe = place("cafe")

    @Test
    fun stopsComeBeforeTheDestination() {
        val trip = Trip(wuling).withStop(fuel).withStop(cafe)

        assertEquals(listOf(fuel, cafe, wuling), trip.waypoints)
    }

    @Test
    fun ignoresAPlaceAlreadyOnTheTrip() {
        val trip = Trip(wuling).withStop(fuel)

        assertEquals(trip, trip.withStop(fuel))
        assertEquals(trip, trip.withStop(wuling))
    }

    @Test
    fun capsTheNumberOfStops() {
        var trip = Trip(wuling)
        repeat(Trip.MAX_STOPS + 2) { trip = trip.withStop(place("stop$it")) }

        assertEquals(Trip.MAX_STOPS, trip.stops.size)
        assertFalse(trip.canAddStop)
    }

    @Test
    fun removesAndReordersStops() {
        val trip = Trip(wuling).withStop(fuel).withStop(cafe)

        assertEquals(listOf(cafe, fuel), trip.movingStop(1, -1).stops)
        assertEquals(trip, trip.movingStop(0, -1))
        assertEquals(listOf(cafe), trip.withoutStop(0).stops)
        assertEquals(trip, trip.withoutStop(5))
    }

    @Test
    fun remainingDropsReachedStops() {
        val trip = Trip(wuling).withStop(fuel).withStop(cafe)

        assertEquals(listOf(cafe, wuling), trip.remaining(1).waypoints)
    }
}
