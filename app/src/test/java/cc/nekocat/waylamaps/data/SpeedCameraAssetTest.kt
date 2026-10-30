package cc.nekocat.waylamaps.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class SpeedCameraAssetTest {
    @Test
    fun parsesCamerasWithAndWithoutBearing() {
        val asset = SpeedCameraAsset.parse(
            """
            {"version":"2026-10-31","source":"s","license":"l","cameras":[
              {"lat":25.0,"lng":121.5,"city":"臺北市","district":"中正區","address":"a",
               "direction":"南向北","bearing":0,"limit":50},
              {"lat":24.0,"lng":120.5,"city":"臺中市","district":"西屯區","address":"b",
               "direction":"雙向","bearing":null,"limit":null}
            ]}
            """.trimIndent(),
        )

        assertEquals("2026-10-31", asset.version)
        assertEquals(2, asset.cameras.size)
        assertEquals(0, asset.cameras[0].bearing)
        assertNull(asset.cameras[1].bearing)
        assertNull(asset.cameras[1].limit)
    }

    @Test
    fun mapsCameraToEntity() {
        val camera = SpeedCameraAsset.Camera(
            lat = 25.0, lng = 121.5, city = "臺北市", district = "中正區", address = "a",
            direction = "北向南", bearing = 180, limit = 60,
        )

        val entity = camera.toEntity()

        assertEquals(0L, entity.id)
        assertEquals("北向南", entity.directionText)
        assertEquals(180, entity.bearingDeg)
        assertEquals(60, entity.limitKmh)
    }

    @Test
    fun bundledAssetParses() {
        val file = File("src/main/assets/${SpeedCameraAsset.FILE_NAME}")
        val asset = SpeedCameraAsset.parse(file.readText())

        assert(asset.cameras.size > 1000) { "only ${asset.cameras.size} cameras" }
        assert(asset.cameras.all { it.lat in 21.8..26.5 && it.lng in 118.0..122.1 })
    }
}
