package cc.nekocat.waylamaps.data.enforcement

import cc.nekocat.waylamaps.alerts.EnforcementKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EnforcementCsvParserTest {
    private val dir = File("src/main/assets/${EnforcementSources.DIR}")
    private fun parse(key: String) = EnforcementCsvParser.parse(File(dir, "$key.csv").readBytes())

    /** Every bundled county parses to plausible points, in Taiwan, with nothing rejected silently en masse. */
    @Test
    fun everyBundledSourceParses() {
        val sources = EnforcementSources.parseSources(File(dir, "sources.json").readText())
        val manifest = EnforcementSources.parseManifest(File(dir, "manifest.json").readText())
        for (source in sources) {
            assertTrue("${source.key} missing from manifest", source.key in manifest.files)
            val parsed = parse(source.key)
            assertTrue("${source.key}: no points", parsed.points.isNotEmpty())
            assertTrue("${source.key}: ${parsed.rejectedRows} rejected", parsed.rejectedRows <= parsed.points.size / 5 + 1)
            for (point in parsed.points) {
                assertTrue("${source.key}: lat ${point.lat}", point.lat in 21.8..26.5)
                assertTrue("${source.key}: lng ${point.lng}", point.lng in 118.0..122.1)
                assertTrue("${source.key}: empty location", point.location.isNotBlank())
            }
        }
    }

    @Test
    fun readsBig5() {
        val first = parse("taipei").points.first()

        assertEquals("鄭州路與西寧北路口", first.location)
        assertEquals(25.050497, first.lat, 1e-6)
        assertEquals(121.508167, first.lng, 1e-6)
        assertEquals(EnforcementKind.INTERSECTION, first.kind)
    }

    @Test
    fun swapsReversedCoordinates() {
        // Taichung's 經度 column holds latitudes; Kinmen's 座標緯度 holds longitudes.
        for (key in listOf("taichung", "kinmen")) {
            val point = parse(key).points.first()
            assertTrue(key, point.lat < 26.5 && point.lng > 118.0)
        }
    }

    @Test
    fun skipsAverageSpeedSections() {
        assertTrue(parse("taichung").points.none { "區間" in it.location && "K" in it.location })
    }

    @Test
    fun skipsChineseHeaderRow() {
        val yunlin = parse("yunlin")
        assertTrue(yunlin.points.none { it.location == "設置地點" })
        // The description row has no coordinates; it is the only row left out.
        assertEquals(1, yunlin.rejectedRows)
    }

    @Test
    fun readsDirectionAndLimit() {
        val changhua = parse("changhua").points.first()

        assertEquals(0, changhua.bearingDeg)
        assertEquals(50, changhua.limitKmh)
    }

    @Test
    fun mergesRowsForTheSameDevice() {
        // Taoyuan lists 三民路三段與中正路口 twice: once for red lights, once for pedestrians.
        val point = parse("taoyuan").points.single { it.location == "三民路三段與中正路口" }

        assertTrue("闖紅燈" in point.items && "未停讓行人" in point.items)
        assertNull(point.limitKmh)
    }

    @Test
    fun classifiesKinds() {
        assertEquals(EnforcementKind.INTERSECTION, EnforcementCsvParser.kindOf("闖紅燈、違規停車"))
        assertEquals(EnforcementKind.PARKING, EnforcementCsvParser.kindOf("違規(臨時)停車"))
        assertEquals(EnforcementKind.VEHICLE_BAN, EnforcementCsvParser.kindOf("禁行車種"))
        assertEquals(EnforcementKind.INTERSECTION, EnforcementCsvParser.kindOf(""))
    }

    @Test
    fun recognisesSpeedOnlyItems() {
        assertTrue(EnforcementCsvParser.isSpeedOnly("超速"))
        assertFalse(EnforcementCsvParser.isSpeedOnly("超速、闖紅燈"))
        assertFalse(EnforcementCsvParser.isSpeedOnly("違規停車"))
    }
}
