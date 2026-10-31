package cc.nekocat.waylamaps.service

import cc.nekocat.waylamaps.alerts.Fix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RideStatusMonitorTest {
    private val monitor = RideStatusMonitor().apply { start(0) }
    private fun fix(metersNorth: Double = 0.0) = Fix(25.0 + metersNorth / 111_195.0, 121.5, null, 0.0)

    @Test
    fun reportsMissingFixOnceAfterThirtySeconds() {
        assertNull(monitor.onTick(29_000, alertsOnly = true))
        assertEquals(RideStatusEvent.NO_FIX_YET, monitor.onTick(30_000, alertsOnly = true))
        assertNull(monitor.onTick(60_000, alertsOnly = true))
    }

    @Test
    fun reportsSignalLossAndRecovery() {
        monitor.onFix(1_000, fix())
        assertNull(monitor.onTick(10_000, alertsOnly = true))
        assertEquals(RideStatusEvent.SIGNAL_LOST, monitor.onTick(11_000, alertsOnly = true))
        assertNull(monitor.onTick(20_000, alertsOnly = true))
        assertEquals(RideStatusEvent.SIGNAL_RECOVERED, monitor.onFix(21_000, fix()))
        assertNull(monitor.onFix(22_000, fix()))
    }

    @Test
    fun restartingForgetsTheGapWithoutFixes() {
        monitor.onFix(1_000, fix())
        monitor.start(60_000)

        assertNull(monitor.onTick(61_000, alertsOnly = true))
        assertNull(monitor.onFix(62_000, fix()))
    }

    @Test
    fun reportsStandingStillOnlyWithAlertsAlone() {
        val minute = 60_000L
        for (t in 0..20) monitor.onFix(t * minute, fix(metersNorth = t.toDouble()))
        assertNull(monitor.onTick(20 * minute + 500, alertsOnly = false))
        assertEquals(RideStatusEvent.STATIONARY_LONG, monitor.onTick(20 * minute + 500, alertsOnly = true))
        assertNull(monitor.onTick(20 * minute + 1_000, alertsOnly = true))
    }

    @Test
    fun movingResetsTheStationaryTimer() {
        val minute = 60_000L
        monitor.onFix(0, fix())
        monitor.onFix(15 * minute, fix(metersNorth = 200.0))
        monitor.onFix(30 * minute - 1, fix(metersNorth = 200.0))
        assertNull(monitor.onTick(30 * minute - 1, alertsOnly = true))
        monitor.onFix(35 * minute, fix(metersNorth = 210.0))
        assertEquals(RideStatusEvent.STATIONARY_LONG, monitor.onTick(35 * minute, alertsOnly = true))
    }
}
