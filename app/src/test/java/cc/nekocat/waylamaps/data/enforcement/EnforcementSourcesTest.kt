package cc.nekocat.waylamaps.data.enforcement

import cc.nekocat.waylamaps.data.enforcement.EnforcementSources.Distribution
import cc.nekocat.waylamaps.data.enforcement.EnforcementSources.Field
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Same cases as tools/test_fetch_enforcement.py. */
class EnforcementSourcesTest {
    private fun resource(format: String, description: String, url: String, fields: List<String> = listOf("地點", "座標緯度")) =
        Distribution(format, description, url, fields.map(::Field))

    @Test
    fun latestAnnouncementWins() {
        val url = EnforcementSources.pickCsvUrl(
            listOf(
                resource("JSON", "第4次公告", "json4"),
                resource("CSV", "設置地點第3次公告", "csv3"),
                resource("CSV", "設置地點第4次公告", "csv4"),
                resource("CSV", "設置地點第1次公告", "csv1"),
            ),
        )
        assertEquals("csv4", url)
    }

    @Test
    fun firstCsvWithoutAnnouncements() {
        val url = EnforcementSources.pickCsvUrl(
            listOf(resource("XML", "地點", "xml"), resource("CSV", "取締地點", "first"), resource("CSV", "區間", "second")),
        )
        assertEquals("first", url)
    }

    @Test
    fun prefersCsvWithCoordinates() {
        val url = EnforcementSources.pickCsvUrl(
            listOf(resource("CSV", "設置地點", "titles_only", listOf("Seq", "Column1")), resource("CSV", "設置地點", "with_coordinates")),
        )
        assertEquals("with_coordinates", url)
    }

    @Test
    fun noCsv() {
        assertNull(EnforcementSources.pickCsvUrl(listOf(resource("PDF", "一覽表", "pdf"))))
    }

    @Test
    fun readsDatasetResponse() {
        val dataset = EnforcementSources.parseDataset(
            """{"success":true,"result":{"datasetId":1,"distribution":[{"resourceFormat":"CSV",""" +
                """"resourceDownloadUrl":"u","resourceField":[{"name":"緯度","description":"x"}]}]}}""",
        )
        assertEquals("u", EnforcementSources.pickCsvUrl(dataset.distribution))
    }
}
