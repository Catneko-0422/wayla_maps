package cc.nekocat.waylamaps.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraDescriberTest {
    private fun camera(address: String, direction: String = "南向北") =
        SpeedCamera(1, 25.0, 121.5, bearingDeg = 0, limitKmh = 50, address = address, direction = direction)

    private fun enforcementType(items: String) = CameraDescriber.enforcementType(items)

    @Test
    fun namesFreewaysWithChineseOrArabicNumbers() {
        assertEquals("ctx_freeway_1", CameraDescriber.describe(camera("國道一號北向21.5公里"), roadKnown = false).context)
        assertEquals("ctx_freeway_3a", CameraDescriber.describe(camera("國道3甲 2K"), roadKnown = false).context)
        assertEquals("ctx_freeway_10", CameraDescriber.describe(camera("國道十號 5K"), roadKnown = false).context)
    }

    @Test
    fun describesPlaces() {
        assertEquals("ctx_expressway", CameraDescriber.describe(camera("台64線快速公路20.24公里"), false).context)
        assertEquals("ctx_tunnel", CameraDescriber.describe(camera("北宜公路彭山隧道"), false).context)
        assertEquals("ctx_ramp", CameraDescriber.describe(camera("環河南路中興橋匝道"), false).context)
        assertNull(CameraDescriber.describe(camera("中山路與中正路口"), false).context)
    }

    @Test
    fun saysElevatedOnlyWhenTheRoadIsUnknown() {
        val elevated = camera("市民大道高架京站百貨前")

        assertEquals("ctx_elevated", CameraDescriber.describe(elevated, roadKnown = false).context)
        assertNull(CameraDescriber.describe(elevated, roadKnown = true).context)
    }

    @Test
    fun spotsRedLightNotes() {
        assertEquals("type_speed_red_light", CameraDescriber.describe(camera("忠孝路與義教街口", "南向北(超速闖紅燈)"), false).type)
        assertEquals("type_speed_red_light", CameraDescriber.describe(camera("縣道204線與新店路岔路口(兼闖紅燈)"), false).type)
        assertEquals("type_speed_camera", CameraDescriber.describe(camera("中山路"), false).type)
    }

    @Test
    fun typesEnforcementByItems() {
        assertEquals("type_intersection_speed", enforcementType("超速、闖紅燈、未禮讓行人"))
        assertEquals("type_pedestrian", enforcementType("車不停讓行人"))
        assertEquals("type_pedestrian", enforcementType("車輛行經行人穿越道不暫停讓行人優先通行"))
        assertEquals("type_turn", enforcementType("違規左轉、違規迴轉"))
        assertEquals("type_red_light", enforcementType("闖紅燈"))
        assertEquals("type_noise", enforcementType("噪音"))
        assertEquals("type_enforcement", enforcementType("闖紅燈、不停讓行人、不依規定轉彎"))
        assertEquals("type_enforcement", enforcementType(""))
    }
}
