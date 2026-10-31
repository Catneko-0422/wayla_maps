package cc.nekocat.waylamaps.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import kotlin.math.cos

class RouteCameraMatcherTest {
    private val lat0 = 25.0
    private val lng0 = 121.5
    private val metersPerDegLng = 111_195.0 * cos(Math.toRadians(lat0))

    /** A point [east] and [north] meters from the origin. */
    private fun at(east: Double, north: Double) = GeoPoint(lat0 + north / 111_195.0, lng0 + east / metersPerDegLng)
    private fun fix(east: Double, north: Double) = at(east, north).let { Fix(it.lat, it.lng, bearingDeg = null, speedMps = 12.0) }
    private fun camera(id: Long, east: Double, north: Double, bearing: Int? = null) =
        at(east, north).let { SpeedCamera(id, it.lat, it.lng, bearingDeg = bearing, limitKmh = 50) }

    // North for 500 m, then east for 500 m.
    private val matcher = RouteCameraMatcher()

    @Before
    fun setUp() {
        matcher.setRoute(listOf(at(0.0, 0.0), at(0.0, 500.0), at(500.0, 500.0)))
    }

    @Test
    fun announcesCameraOnRouteWithAlongRouteDistance() {
        // 200 m north then 90 m east: 290 m along the route from (0, 300), though only ~219 m away.
        val result = matcher.update(fix(0.0, 300.0), listOf(camera(1, 90.0, 500.0)))

        assertEquals(1L, result.announce?.camera?.id)
        assertEquals(290.0, result.announce!!.distanceM, 2.0)
    }

    @Test
    fun ignoresCameraOnParallelRoad() {
        // 60 m to the side of the first leg, like an elevated or service road next to it.
        val result = matcher.update(fix(0.0, 100.0), listOf(camera(1, 60.0, 250.0)))

        assertNull(result.next)
    }

    @Test
    fun ignoresCameraOnCrossStreet() {
        // On a street crossing the route at (0, 300), 80 m away from the junction.
        val result = matcher.update(fix(0.0, 100.0), listOf(camera(1, -80.0, 300.0)))

        assertNull(result.next)
    }

    @Test
    fun ignoresCameraEnforcingTheOtherDirection() {
        val result = matcher.update(fix(0.0, 100.0), listOf(camera(1, 0.0, 300.0, bearing = 180)))

        assertNull(result.next)
    }

    @Test
    fun usesTheRouteDirectionAtTheCamera() {
        // The second leg runs east, so an eastbound camera there counts.
        val result = matcher.update(fix(0.0, 400.0), listOf(camera(1, 100.0, 500.0, bearing = 90)))

        assertEquals(1L, result.announce?.camera?.id)
    }

    @Test
    fun ignoresCameraAlreadyPassed() {
        val result = matcher.update(fix(0.0, 300.0), listOf(camera(1, 0.0, 200.0)))

        assertNull(result.next)
    }

    @Test
    fun showsCameraFurtherAwayWithoutAnnouncing() {
        val result = matcher.update(fix(0.0, 0.0), listOf(camera(1, 0.0, 450.0)))

        assertEquals(1L, result.next?.camera?.id)
        assertNull(result.announce)
    }

    @Test
    fun announcesEachCameraOnce() {
        val cameras = listOf(camera(1, 0.0, 400.0))
        matcher.update(fix(0.0, 200.0), cameras)

        assertNull(matcher.update(fix(0.0, 250.0), cameras).announce)
        assertEquals(1L, matcher.update(fix(0.0, 250.0), cameras).next?.camera?.id)
    }

    @Test
    fun matchesEnforcementPointsTheSameWay() {
        val onRoute = at(0.0, 300.0).let {
            EnforcementPoint(7, it.lat, it.lng, "路口", "闖紅燈", bearingDeg = 0, limitKmh = null, kind = EnforcementKind.INTERSECTION)
        }
        val crossStreet = at(-80.0, 300.0).let { onRoute.copy(id = 8, lat = it.lat, lng = it.lng) }

        val result = matcher.update(fix(0.0, 100.0), listOf(onRoute, crossStreet))

        assertEquals(7L, result.announce?.point?.id)
        assertEquals(200.0, result.announce!!.distanceM, 2.0)
    }

    @Test
    fun reportsNothingWhenOffTheRoute() {
        val result = matcher.update(fix(300.0, 100.0), listOf(camera(1, 0.0, 300.0)))

        assertNull(result.next)
    }

    @Test
    fun keepsProgressOnRouteThatPassesTheSamePlaceTwice() {
        // Out north 300 m and back to the start: the camera at (0, 100) is enforced southbound.
        matcher.setRoute(listOf(at(0.0, 0.0), at(0.0, 300.0), at(5.0, 300.0), at(5.0, 0.0)))
        val southbound = listOf(camera(1, 4.0, 100.0, bearing = 180))

        // Heading north at 50 m: the camera is ahead only on the way back, 250 + 5 + 200 m on.
        val outbound = matcher.update(fix(0.0, 50.0), southbound)
        assertEquals(455.0, outbound.next!!.distanceM, 3.0)

        matcher.update(fix(0.0, 250.0), southbound)
        matcher.update(fix(5.0, 290.0), southbound)
        val inbound = matcher.update(fix(5.0, 200.0), southbound)
        assertEquals(100.0, inbound.next!!.distanceM, 3.0)
    }
}
