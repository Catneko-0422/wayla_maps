package cc.nekocat.waylamaps.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CameraAheadDetectorTest {
    // About 111 m per 0.001 degree of latitude.
    private val origin = Fix(lat = 25.0, lng = 121.5, bearingDeg = 0.0, speedMps = 15.0)
    private fun north(meters: Double, fix: Fix = origin) = fix.copy(lat = fix.lat + meters / 111_195.0)
    private fun camera(id: Long, metersNorth: Double, bearing: Int? = null, limit: Int? = 50) =
        SpeedCamera(id, lat = origin.lat + metersNorth / 111_195.0, lng = origin.lng, bearingDeg = bearing, limitKmh = limit)

    @Test
    fun announcesCameraStraightAheadWithinAlertDistance() {
        val result = CameraAheadDetector().update(origin, listOf(camera(1, 280.0)))

        assertEquals(1L, result.announce?.camera?.id)
        assertEquals(280.0, result.announce!!.distanceM, 1.0)
    }

    @Test
    fun showsButDoesNotAnnounceCameraFurtherAway() {
        val result = CameraAheadDetector().update(origin, listOf(camera(1, 600.0)))

        assertEquals(1L, result.next?.camera?.id)
        assertNull(result.announce)
    }

    @Test
    fun ignoresCameraBehind() {
        val result = CameraAheadDetector().update(origin, listOf(camera(1, -200.0)))

        assertNull(result.next)
    }

    @Test
    fun ignoresCameraForOppositeDirection() {
        val result = CameraAheadDetector().update(origin, listOf(camera(1, 200.0, bearing = 180)))

        assertNull(result.next)
    }

    @Test
    fun cameraWithUnknownDirectionCountsWhenAhead() {
        val result = CameraAheadDetector().update(origin, listOf(camera(1, 200.0, bearing = null)))

        assertNotNull(result.announce)
    }

    @Test
    fun ignoresCameraOffToTheSide() {
        val side = SpeedCamera(1, lat = origin.lat, lng = origin.lng + 0.002, bearingDeg = null, limitKmh = 50)

        assertNull(CameraAheadDetector().update(origin, listOf(side)).next)
    }

    @Test
    fun picksNearestCameraAhead() {
        val result = CameraAheadDetector().update(origin, listOf(camera(1, 250.0), camera(2, 150.0)))

        assertEquals(2L, result.next?.camera?.id)
    }

    @Test
    fun noHeadingYetMeansNoAlert() {
        val stopped = origin.copy(bearingDeg = null, speedMps = 0.0)

        assertNull(CameraAheadDetector().update(stopped, listOf(camera(1, 200.0))).next)
    }

    @Test
    fun keepsLastHeadingWhileStopped() {
        val detector = CameraAheadDetector()
        detector.update(origin, emptyList())
        // Stopped at a light: the receiver reports a random course at walking speed.
        val stopped = north(50.0).copy(bearingDeg = 200.0, speedMps = 0.3)

        assertNotNull(detector.update(stopped, listOf(camera(1, 250.0))).announce)
    }

    @Test
    fun announcesOnlyOnce() {
        val detector = CameraAheadDetector()
        val cameras = listOf(camera(1, 400.0))

        assertNotNull(detector.update(north(110.0), cameras).announce)
        assertNull(detector.update(north(150.0), cameras).announce)
        assertNull(detector.update(north(300.0), cameras).announce)
    }

    @Test
    fun announcesAgainAfterTurningBackPastCamera() {
        val detector = CameraAheadDetector()
        val cameras = listOf(camera(1, 300.0, bearing = null))

        assertNotNull(detector.update(north(50.0), cameras).announce)
        // Rode past it and 150 m on, then turned around.
        detector.update(north(450.0), cameras)
        val southbound = north(450.0).copy(bearingDeg = 180.0)

        assertNotNull(detector.update(southbound, cameras).announce)
    }

    @Test
    fun spokenDistanceRoundsToFiftyMeters() {
        assertEquals(300, CameraAheadDetector.spokenDistanceMeters(287.0))
        assertEquals(250, CameraAheadDetector.spokenDistanceMeters(260.0))
        assertEquals(50, CameraAheadDetector.spokenDistanceMeters(10.0))
    }
}
