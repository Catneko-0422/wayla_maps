package cc.nekocat.waylamaps.navigation

import com.google.android.libraries.mapsplatform.turnbyturn.model.Maneuver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TurnByTurnFeedTest {
    private fun step(number: Int, road: String) =
        TurnUpdate(TurnNavState.ENROUTE, number, Maneuver.TURN_LEFT, null, distanceToStepM = 100, roadName = road, instructionText = null)

    @Test
    fun currentRoadIsTheRoadThePreviousStepTurnedOnto() {
        val feed = TurnByTurnFeed()
        feed.publish(step(1, "市民大道"))
        assertNull(feed.currentRoad.value)

        feed.publish(step(2, "建國南路"))
        assertEquals("市民大道", feed.currentRoad.value)

        feed.publish(step(2, "建國南路"))
        assertEquals("市民大道", feed.currentRoad.value)
    }

    @Test
    fun clearForgetsTheRoad() {
        val feed = TurnByTurnFeed()
        feed.publish(step(1, "市民大道"))
        feed.publish(step(2, "建國南路"))
        feed.clear()

        assertNull(feed.updates.value)
        assertNull(feed.currentRoad.value)
    }
}
