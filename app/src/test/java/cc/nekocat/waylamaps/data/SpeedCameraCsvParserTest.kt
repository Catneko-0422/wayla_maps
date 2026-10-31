package cc.nekocat.waylamaps.data

import cc.nekocat.waylamaps.data.SpeedCameraCsvParser.parseBearing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Same cases as tools/test_convert_speed_cameras.py. */
class SpeedCameraCsvParserTest {
    private val header = "\uFEFFCityName,RegionName,Address,DeptNm,BranchNm,Longitude,Latitude,direct,limit\r\n" +
        "設置縣市,設置市區鄉鎮,設置地址,管轄警局,管轄分局,經度,緯度,拍攝方向,速限\r\n"

    private fun csv(vararg rows: String) = header + rows.joinToString("\r\n")
    private fun row(direct: String, limit: String = "50", lat: String = "25.0", lng: String = "121.5") =
        "臺北市,中正區,測試路,局,分局,$lng,$lat,$direct,$limit"

    @Test
    fun fromToUsesDestination() {
        assertEquals(0, parseBearing("南向北"))
        assertEquals(180, parseBearing("北往南"))
        assertEquals(45, parseBearing("西南向東北"))
        assertEquals(225, parseBearing("東北向西南"))
        assertEquals(270, parseBearing("東往西向"))
    }

    @Test
    fun towards() {
        assertEquals(180, parseBearing("往南"))
        assertEquals(0, parseBearing("往北方向"))
        assertEquals(0, parseBearing("介壽路往北"))
    }

    @Test
    fun headingOnly() {
        assertEquals(180, parseBearing("南向"))
        assertEquals(0, parseBearing("北向(區間測速)"))
    }

    @Test
    fun northboundSouthbound() {
        assertEquals(0, parseBearing("北上車道"))
        assertEquals(180, parseBearing("南下方向"))
        assertEquals(0, parseBearing("往北上方向"))
    }

    @Test
    fun notesAreIgnored() {
        assertEquals(0, parseBearing("南向北(超速闖紅燈)"))
        assertEquals(270, parseBearing("東往西(區間測速)"))
    }

    @Test
    fun bothDirections() {
        listOf(
            "雙向", "南北雙向", "東西向", "南北", "多向", "往東西向",
            "東往西(雙向)", "南北雙向兼南下闖紅燈", "南向北(區間測速) 北向南(區間測速)",
        ).forEach { assertNull(it, parseBearing(it)) }
    }

    @Test
    fun placeNamesAreUnresolved() {
        listOf("往大溪方向", "往南崁方向", "往南桃園交流道方向", "往下山")
            .forEach { assertNull(it, parseBearing(it)) }
    }

    @Test
    fun splitLimitsBecomeTwoCameras() {
        val cameras = SpeedCameraCsvParser.parse(csv(row("南向60北向70", limit = ""))).cameras

        assertEquals(listOf(180 to 60, 0 to 70), cameras.map { it.bearing to it.limit })
    }

    @Test
    fun rejectsPointsOutsideTaiwanAndBadNumbers() {
        val parsed = SpeedCameraCsvParser.parse(csv(row("雙向", lat = "0", lng = "0"), row("雙向", lat = "x")))

        assertEquals(emptyList<SpeedCameraAsset.Camera>(), parsed.cameras)
        assertEquals(2, parsed.rejectedRows)
    }

    @Test
    fun dropsExactDuplicates() {
        assertEquals(1, SpeedCameraCsvParser.parse(csv(row("南向北"), row("南向北"))).cameras.size)
    }

    @Test
    fun missingLimitIsNull() {
        assertNull(SpeedCameraCsvParser.parse(csv(row("雙向", limit = ""))).cameras.single().limit)
    }

    @Test
    fun quotedFieldsMayContainCommas() {
        val line = "臺北市,中正區,\"重慶南路一段,122號\",局,分局,121.5,25.0,雙向,50"

        assertEquals("重慶南路一段,122號", SpeedCameraCsvParser.parse(csv(line)).cameras.single().address)
    }

    /** Runs when tools/data holds the raw CSV the bundled asset was generated from. */
    @Test
    fun matchesPythonConverterOnRealData() {
        val raw = File("../tools/data/speed_cameras_raw.csv")
        assumeTrue("raw CSV not downloaded", raw.exists())
        val asset = SpeedCameraAsset.parse(File("src/main/assets/${SpeedCameraAsset.FILE_NAME}").readText())

        val parsed = SpeedCameraCsvParser.parse(raw.readText())

        assertEquals(asset.cameras, parsed.cameras)
    }
}
