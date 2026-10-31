package cc.nekocat.waylamaps.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RideAlertRulesTest {
    @Test
    fun overLimitWarnsOncePerCameraWithinRange() {
        val warner = OverLimitWarner()

        assertFalse(warner.shouldWarn(1, distanceM = 400.0, limitKmh = 50, speedKmh = 70.0))
        assertFalse(warner.shouldWarn(1, distanceM = 250.0, limitKmh = 50, speedKmh = 50.0))
        assertTrue(warner.shouldWarn(1, distanceM = 200.0, limitKmh = 50, speedKmh = 51.0))
        assertFalse(warner.shouldWarn(1, distanceM = 100.0, limitKmh = 50, speedKmh = 60.0))
        assertFalse(warner.shouldWarn(2, distanceM = 100.0, limitKmh = null, speedKmh = 90.0))
    }

    @Test
    fun nearbyOnceReportsSlowApproachOnce() {
        val zone = SpeedCamera(9, 25.0, 121.5, bearingDeg = null, limitKmh = null)
        val notifier = NearbyOnceNotifier()
        fun fix(metersNorth: Double, kmh: Double) = Fix(25.0 + metersNorth / 111_195.0, 121.5, null, kmh / 3.6)

        assertNull(notifier.update(fix(30.0, 40.0), listOf(zone)))
        assertEquals(9L, notifier.update(fix(30.0, 5.0), listOf(zone))?.id)
        assertNull(notifier.update(fix(10.0, 0.0), listOf(zone)))
        // Leaving and coming back reports it again.
        assertNull(notifier.update(fix(300.0, 30.0), listOf(zone)))
        assertEquals(9L, notifier.update(fix(20.0, 3.0), listOf(zone))?.id)
    }
}
