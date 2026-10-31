package cc.nekocat.waylamaps.alerts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraRoadFilterTest {
    @Test
    fun scootersSkipElevatedRoadsAndFreeways() {
        assertFalse(CameraRoadFilter.isRelevant("市民大道高架京站百貨前", scooter = true, currentRoad = null))
        assertFalse(CameraRoadFilter.isRelevant("國道1號北向21.5公里", scooter = true, currentRoad = null))
        assertTrue(CameraRoadFilter.isRelevant("市民大道三段與建國南路口", scooter = true, currentRoad = null))
    }

    @Test
    fun scootersKeepExpresswayIntersectionsOnly() {
        assertFalse(CameraRoadFilter.isRelevant("台64線快速公路20.24公里（往東）", scooter = true, currentRoad = null))
        assertFalse(CameraRoadFilter.isRelevant("信義快速道路(北往南文山隧道出口處)", scooter = true, currentRoad = null))
        assertTrue(CameraRoadFilter.isRelevant("水源快速道路同安街口", scooter = true, currentRoad = null))
    }

    @Test
    fun carsKeepEverythingWhenTheRoadIsUnknown() {
        assertTrue(CameraRoadFilter.isRelevant("市民大道高架京站百貨前", scooter = false, currentRoad = null))
        assertTrue(CameraRoadFilter.isRelevant("國道1號北向21.5公里", scooter = false, currentRoad = null))
    }

    @Test
    fun carsMatchTheElevationOfTheCurrentRoad() {
        val elevated = "市民大道高架京站百貨前"
        val surface = "市民大道一段"

        assertFalse(CameraRoadFilter.isRelevant(elevated, scooter = false, currentRoad = "市民大道一段"))
        assertTrue(CameraRoadFilter.isRelevant(surface, scooter = false, currentRoad = "市民大道一段"))
        assertTrue(CameraRoadFilter.isRelevant(elevated, scooter = false, currentRoad = "市民高架道路"))
        assertFalse(CameraRoadFilter.isRelevant(surface, scooter = false, currentRoad = "市民高架道路"))
    }
}
