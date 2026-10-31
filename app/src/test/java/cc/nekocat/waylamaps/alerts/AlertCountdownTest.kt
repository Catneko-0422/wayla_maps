package cc.nekocat.waylamaps.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlertCountdownTest {
    @Test
    fun announcesEachStepOnceOnTheWayIn() {
        val countdown = AlertCountdown()
        val spoken = listOf(450.0, 390.0, 350.0, 290.0, 199.0, 150.0, 95.0, 48.0, 20.0)
            .mapNotNull { countdown.check(1, it) }

        assertEquals(listOf(400, 300, 200, 100, 50), spoken.map { it.distanceM })
        assertEquals(listOf(true, false, false, false, false), spoken.map { it.first })
    }

    @Test
    fun startsAtTheStepAPointIsFirstSeenIn() {
        val countdown = AlertCountdown()

        assertEquals(AlertCountdown.Step(200, first = true), countdown.check(1, 180.0))
        assertNull(countdown.check(1, 120.0))
        assertEquals(AlertCountdown.Step(100, first = false), countdown.check(1, 90.0))
    }

    @Test
    fun skipsStepsJumpedOver() {
        val countdown = AlertCountdown()
        countdown.check(1, 380.0)

        // A GPS gap from 380 m to 90 m says 100 m only, not 300 and 200 late.
        assertEquals(100, countdown.check(1, 90.0)?.distanceM)
    }

    @Test
    fun countsDownAgainOnceForgotten() {
        val countdown = AlertCountdown()
        countdown.check(1, 40.0)
        countdown.ids.remove(1)

        assertEquals(AlertCountdown.Step(400, first = true), countdown.check(1, 400.0))
    }

    @Test
    fun keepsPointsApart() {
        val countdown = AlertCountdown()
        countdown.check(1, 250.0)

        assertEquals(300, countdown.check(2, 250.0)?.distanceM)
    }
}
