package cc.nekocat.waylamaps.alerts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraRoadFilterTest {
    @Test
    fun scootersSkipElevatedRoadsAndFreeways() {
        assertFalse(CameraRoadFilter.isRelevant("市民大道高架京站百貨前", RoadAccess.SURFACE_ONLY, null))
        assertFalse(CameraRoadFilter.isRelevant("國道1號北向21.5公里", RoadAccess.SURFACE_ONLY, null))
        assertTrue(CameraRoadFilter.isRelevant("市民大道三段與建國南路口", RoadAccess.SURFACE_ONLY, null))
    }

    @Test
    fun scootersKeepExpresswayIntersectionsOnly() {
        assertFalse(CameraRoadFilter.isRelevant("台64線快速公路20.24公里（往東）", RoadAccess.SURFACE_ONLY, null))
        assertFalse(CameraRoadFilter.isRelevant("信義快速道路(北往南文山隧道出口處)", RoadAccess.SURFACE_ONLY, null))
        assertTrue(CameraRoadFilter.isRelevant("水源快速道路同安街口", RoadAccess.SURFACE_ONLY, null))
    }

    @Test
    fun carsKeepEverythingWhenTheRoadIsUnknown() {
        assertTrue(CameraRoadFilter.isRelevant("市民大道高架京站百貨前", RoadAccess.ALL, null))
        assertTrue(CameraRoadFilter.isRelevant("國道1號北向21.5公里", RoadAccess.ALL, null))
    }

    @Test
    fun carsMatchTheElevationOfTheCurrentRoad() {
        val elevated = "市民大道高架京站百貨前"
        val surface = "市民大道一段"

        assertFalse(CameraRoadFilter.isRelevant(elevated, RoadAccess.ALL, "市民大道一段"))
        assertTrue(CameraRoadFilter.isRelevant(surface, RoadAccess.ALL, "市民大道一段"))
        assertTrue(CameraRoadFilter.isRelevant(elevated, RoadAccess.ALL, "市民高架道路"))
        assertFalse(CameraRoadFilter.isRelevant(surface, RoadAccess.ALL, "市民高架道路"))
    }

    @Test
    fun heavyMotorcyclesSkipOnlyFreeways() {
        assertFalse(CameraRoadFilter.isRelevant("國道1號北向21.5公里", RoadAccess.NO_FREEWAYS, null))
        assertTrue(CameraRoadFilter.isRelevant("台64線快速公路20.24公里（往東）", RoadAccess.NO_FREEWAYS, null))
        assertTrue(CameraRoadFilter.isRelevant("市民大道高架京站百貨前", RoadAccess.NO_FREEWAYS, null))
    }

    @Test
    fun elevationSettingOffKeepsBothLevels() {
        assertTrue(CameraRoadFilter.isRelevant("市民大道高架京站百貨前", RoadAccess.SURFACE_ONLY, null, matchElevation = false))
        assertTrue(CameraRoadFilter.isRelevant("市民大道高架京站百貨前", RoadAccess.ALL, "市民大道一段", matchElevation = false))
        // Roads closed to the vehicle stay out regardless.
        assertFalse(CameraRoadFilter.isRelevant("國道1號北向21.5公里", RoadAccess.SURFACE_ONLY, null, matchElevation = false))
    }
}
