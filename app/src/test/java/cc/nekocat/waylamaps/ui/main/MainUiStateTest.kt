package cc.nekocat.waylamaps.ui.main

import cc.nekocat.waylamaps.alerts.AlertSession
import cc.nekocat.waylamaps.alerts.Ahead
import cc.nekocat.waylamaps.alerts.CameraAhead
import cc.nekocat.waylamaps.alerts.EnforcementKind
import cc.nekocat.waylamaps.alerts.EnforcementPoint
import cc.nekocat.waylamaps.alerts.SpeedCamera
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainUiStateTest {
    @Test
    fun waitsForGpsUntilFirstFix() {
        assertTrue(AlertSession(running = true).toUiState().waitingForGps)
        assertFalse(AlertSession(running = true, hasFix = true).toUiState().waitingForGps)
        assertFalse(AlertSession(running = false).toUiState().waitingForGps)
    }

    @Test
    fun roundsDistanceToTenMeters() {
        val camera = SpeedCamera(1, 25.0, 121.5, bearingDeg = null, limitKmh = 60)
        val state = AlertSession(running = true, hasFix = true, next = CameraAhead(camera, 286.4)).toUiState()

        assertEquals(290, state.nextCameraMeters)
        assertEquals(60, state.nextCameraLimitKmh)
    }

    @Test
    fun showsEnforcementIntersectionDistance() {
        val point = EnforcementPoint(1, 25.0, 121.5, "市民大道與承德路口", "闖紅燈", null, null, EnforcementKind.INTERSECTION)
        val state = AlertSession(running = true, hasFix = true, nextEnforcement = Ahead(point, 154.0)).toUiState()

        assertEquals(150, state.nextEnforcementMeters)
        assertNull(state.nextCameraMeters)
    }

    @Test
    fun noCameraAhead() {
        assertNull(AlertSession(running = true, hasFix = true).toUiState().nextCameraMeters)
    }
}
