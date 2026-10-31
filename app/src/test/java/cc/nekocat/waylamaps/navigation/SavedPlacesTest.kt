package cc.nekocat.waylamaps.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class SavedPlacesTest {
    private fun place(id: String) = SavedPlace(id, "Place $id", "Address $id")

    @Test
    fun newestRecentComesFirstWithoutDuplicates() {
        val data = SavedPlacesData()
            .withRecent(place("a"))
            .withRecent(place("b"))
            .withRecent(place("a"))

        assertEquals(listOf("a", "b"), data.recent.map { it.placeId })
    }

    @Test
    fun recentListIsCapped() {
        val data = (1..12).fold(SavedPlacesData()) { acc, i -> acc.withRecent(place("$i")) }

        assertEquals(SavedPlacesData.MAX_RECENT, data.recent.size)
        assertEquals("12", data.recent.first().placeId)
        assertEquals("5", data.recent.last().placeId)
    }
}
