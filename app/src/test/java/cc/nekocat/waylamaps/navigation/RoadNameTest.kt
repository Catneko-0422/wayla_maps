package cc.nekocat.waylamaps.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadNameTest {
    @Test
    fun recognisesTaiwaneseRoads() {
        listOf("中山路", "承德路一段", "龍潭北路40巷", "台1線", "國道1號", "市民大道高架道路", "中正橋").forEach {
            assertTrue(it, isRoadName(it))
        }
    }

    @Test
    fun rejectsInstructions() {
        listOf("靠左", "迴轉", "目的地", "").forEach { assertFalse(it, isRoadName(it)) }
    }
}
