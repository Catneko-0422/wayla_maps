package cc.nekocat.waylamaps.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoadNameTest {
    @Test
    fun keepsPlainRoadNames() {
        listOf("中山路", "承德路一段", "龍潭北路40巷", "市民大道高架道路", "中正橋", "內山公路").forEach {
            assertEquals(it, RoadNames.spoken(it))
        }
    }

    @Test
    fun prefersTheNamedRoadOverRouteNumbers() {
        assertEquals("永興路", RoadNames.spoken("雲193鄉道 永興路"))
        assertEquals("三樂路", RoadNames.spoken("雲82鄉道 三樂路"))
        assertEquals("仁義路", RoadNames.spoken("台3線 仁義路 / 內山公路"))
    }

    @Test
    fun fallsBackToTheFreeway() {
        assertEquals("國道1號", RoadNames.spoken("台78線  / 國道1號 虎尾"))
        assertEquals("國道1號", RoadNames.spoken("30 號出口 國道1號  / 大林"))
        assertEquals("台1線", RoadNames.spoken("台1線"))
    }

    @Test
    fun dropsInstructionsAndPlaces() {
        listOf("靠左", "迴轉", "目的地", "", "虎尾").forEach { assertNull(it, RoadNames.spoken(it)) }
    }
}
