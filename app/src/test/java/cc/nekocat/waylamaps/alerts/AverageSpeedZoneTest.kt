package cc.nekocat.waylamaps.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AverageSpeedZoneTest {
    private fun length(text: String) = AverageSpeedZones.lengthMeters(text)

    @Test
    fun readsLengthsFromMileageText() {
        assertEquals(7_479.0, length("台9線129k+404m至136k+883m(觀音隧道)")!!, 0.5)
        assertEquals(2_100.0, length("台3線82.2公里至84.3公里")!!, 0.5)
        assertEquals(1_000.0, length("台62線快速道路1.7K至0.7K(瑪陵隧道東往西)區間測速")!!, 0.5)
        assertEquals(5_200.0, length("台26線尖山段8.5-13.7K")!!, 0.5)
        assertEquals(5_980.0, length("臺9戊線3.94K(森永所)至9.92K壽卡休息亭區間測速")!!, 0.5)
    }

    @Test
    fun refusesAmbiguousText() {
        assertNull(length("台3線103.2公里至108公里及台3線114.3公里至123.6公里"))
        assertNull(length("臺21線53.8K至51.1K(北向)/臺21線51.1K至53.9K(南向)"))
        assertNull(length("自強隧道區間測速"))
        assertNull(length("萬壽路一段(東萬壽路309巷-萬壽路一段1275巷)"))
    }

    @Test
    fun recognisesZones() {
        assertTrue(AverageSpeedZones.isZone(SpeedCamera(1, 25.0, 121.5, null, 50, "自強隧道區間測速", "南北雙向")))
        assertTrue(AverageSpeedZones.isZone(SpeedCamera(1, 25.0, 121.5, null, 50, "台3線", "雙向(區間測速)")))
        assertFalse(AverageSpeedZones.isZone(SpeedCamera(1, 25.0, 121.5, null, 50, "中山路", "南向北")))
    }

    @Test
    fun announcesTheEndOnlyAfterTheMargin() {
        val tracker = AverageSpeedTracker()
        tracker.enter(now = 0, limitKmh = 60, lengthM = 1_000.0)

        assertFalse(tracker.onMove(60_000, 1_000.0))
        assertEquals(60, tracker.zone?.averageKmh)
        assertFalse(tracker.onMove(70_000, 250.0))
        assertTrue(tracker.onMove(75_000, 100.0))
        assertNull(tracker.zone)
    }

    @Test
    fun dropsUnknownLengthZonesSilently() {
        val tracker = AverageSpeedTracker()
        tracker.enter(now = 0, limitKmh = 50, lengthM = null)

        assertFalse(tracker.onMove(600_000, 19_000.0))
        assertFalse(tracker.onMove(660_000, 1_500.0))
        assertNull(tracker.zone)
    }
}
