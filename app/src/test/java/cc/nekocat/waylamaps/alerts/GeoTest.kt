package cc.nekocat.waylamaps.alerts

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoTest {
    @Test
    fun oneThousandthDegreeOfLatitudeIsAbout111Meters() {
        assertEquals(111.2, Geo.distanceMeters(25.0, 121.5, 25.001, 121.5), 0.5)
    }

    @Test
    fun bearingsPointToCompassDirections() {
        assertEquals(0.0, Geo.bearingDegrees(25.0, 121.5, 25.01, 121.5), 0.1)
        assertEquals(90.0, Geo.bearingDegrees(25.0, 121.5, 25.0, 121.51), 0.1)
        assertEquals(180.0, Geo.bearingDegrees(25.0, 121.5, 24.99, 121.5), 0.1)
        assertEquals(270.0, Geo.bearingDegrees(25.0, 121.5, 25.0, 121.49), 0.1)
    }

    @Test
    fun angleBetweenWrapsAroundNorth() {
        assertEquals(20.0, Geo.angleBetween(350.0, 10.0), 1e-9)
        assertEquals(180.0, Geo.angleBetween(0.0, 180.0), 1e-9)
        assertEquals(90.0, Geo.angleBetween(45.0, 315.0), 1e-9)
    }
}
