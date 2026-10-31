package cc.nekocat.waylamaps.alerts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraRoadFilterTest {
    private fun camera(address: String) = SpeedCamera(1, 25.0, 121.5, bearingDeg = null, limitKmh = 50, address = address)

    @Test
    fun scootersSkipElevatedRoadsAndFreeways() {
        assertFalse(CameraRoadFilter.isRelevant(camera("市民大道高架京站百貨前"), scooter = true, currentRoad = null))
        assertFalse(CameraRoadFilter.isRelevant(camera("國道1號北向21.5公里"), scooter = true, currentRoad = null))
        assertTrue(CameraRoadFilter.isRelevant(camera("市民大道三段與建國南路口"), scooter = true, currentRoad = null))
    }

    @Test
    fun scootersKeepExpresswayIntersectionsOnly() {
        assertFalse(CameraRoadFilter.isRelevant(camera("台64線快速公路20.24公里（往東）"), scooter = true, currentRoad = null))
        assertFalse(CameraRoadFilter.isRelevant(camera("信義快速道路(北往南文山隧道出口處)"), scooter = true, currentRoad = null))
        assertTrue(CameraRoadFilter.isRelevant(camera("水源快速道路同安街口"), scooter = true, currentRoad = null))
    }

    @Test
    fun carsKeepEverythingWhenTheRoadIsUnknown() {
        assertTrue(CameraRoadFilter.isRelevant(camera("市民大道高架京站百貨前"), scooter = false, currentRoad = null))
        assertTrue(CameraRoadFilter.isRelevant(camera("國道1號北向21.5公里"), scooter = false, currentRoad = null))
    }

    @Test
    fun carsMatchTheElevationOfTheCurrentRoad() {
        val elevated = camera("市民大道高架京站百貨前")
        val surface = camera("市民大道一段")

        assertFalse(CameraRoadFilter.isRelevant(elevated, scooter = false, currentRoad = "市民大道一段"))
        assertTrue(CameraRoadFilter.isRelevant(surface, scooter = false, currentRoad = "市民大道一段"))
        assertTrue(CameraRoadFilter.isRelevant(elevated, scooter = false, currentRoad = "市民高架道路"))
        assertFalse(CameraRoadFilter.isRelevant(surface, scooter = false, currentRoad = "市民高架道路"))
    }
}
