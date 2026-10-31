package cc.nekocat.waylamaps.data

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Parses the NPA "測速執法設置點" CSV on device.
 *
 * Mirrors `tools/convert_speed_cameras.py`; keep the two in sync (the tests cover the same cases,
 * and one compares against the bundled asset when the raw CSV is available).
 */
object SpeedCameraCsvParser {
    data class Parsed(val cameras: List<SpeedCameraAsset.Camera>, val rejectedRows: Int)

    private val COMPASS = mapOf(
        "北" to 0, "東北" to 45, "東" to 90, "東南" to 135,
        "南" to 180, "西南" to 225, "西" to 270, "西北" to 315,
    )

    // Two-character directions first so 東北 is not read as 東.
    private const val DIR = "(東北|東南|西北|西南|北|東|南|西)"
    private val BOTH_WAYS = Regex("雙向|相向|多向|南北|北南|東西|西東")
    private val FROM_TO = Regex("$DIR[向往]$DIR")
    private val TOWARDS = Regex("往$DIR(?=$|方向|向|[(（])")
    private val HEADING_ONLY = Regex("^${DIR}向(?=$|[(（])")
    private val SPLIT_LIMITS = Regex("^${DIR}向(\\d+)${DIR}向(\\d+)$")

    // Taiwan including Penghu, Kinmen and Matsu.
    private val LAT_RANGE = 21.8..26.5
    private val LNG_RANGE = 118.0..122.1

    private val COLUMNS = listOf("CityName", "RegionName", "Address", "Longitude", "Latitude", "direct", "limit")

    fun parse(csv: String): Parsed {
        val iterator = Csv.parse(csv).iterator()
        require(iterator.hasNext()) { "Empty CSV" }
        val header = iterator.next()
        val index = COLUMNS.associateWith { column ->
            header.indexOf(column).also { require(it >= 0) { "Missing column $column" } }
        }

        val cameras = mutableListOf<SpeedCameraAsset.Camera>()
        val seen = mutableSetOf<List<Any?>>()
        var rejected = 0
        for (fields in iterator) {
            val row = index.mapValues { (_, i) -> fields.getOrNull(i)?.trim().orEmpty() }
            // The second line repeats the header in Chinese.
            if (row["CityName"] == "設置縣市") continue

            val expanded = expandRow(row)
            if (expanded == null) {
                rejected++
                continue
            }
            for (camera in expanded) {
                if (camera.lat !in LAT_RANGE || camera.lng !in LNG_RANGE) {
                    rejected++
                    continue
                }
                if (seen.add(listOf(camera.lat, camera.lng, camera.bearing, camera.limit))) {
                    cameras += camera
                }
            }
        }
        return Parsed(cameras, rejected)
    }

    /**
     * Travel direction (degrees, 0 = north) the camera enforces, or null for both directions
     * or a direction that cannot be resolved (such as a place name).
     */
    fun parseBearing(direct: String): Int? {
        val text = direct.trim()
        if (BOTH_WAYS.containsMatchIn(text)) return null
        val destinations = FROM_TO.findAll(text).map { it.groupValues[2] }.toSet()
        if (destinations.size > 1) return null
        destinations.singleOrNull()?.let { return COMPASS.getValue(it) }
        if ("北上" in text) return 0
        if ("南下" in text) return 180
        TOWARDS.find(text)?.let { return COMPASS.getValue(it.groupValues[1]) }
        HEADING_ONLY.find(text)?.let { return COMPASS.getValue(it.groupValues[1]) }
        return null
    }

    private fun expandRow(row: Map<String, String>): List<SpeedCameraAsset.Camera>? {
        val lat = roundCoordinate(row.getValue("Latitude")) ?: return null
        val lng = roundCoordinate(row.getValue("Longitude")) ?: return null
        val direct = row.getValue("direct")
        val base = SpeedCameraAsset.Camera(
            lat = lat,
            lng = lng,
            city = row.getValue("CityName"),
            district = row.getValue("RegionName"),
            address = row.getValue("Address"),
            direction = direct,
        )
        SPLIT_LIMITS.matchEntire(direct)?.let { m ->
            val (d1, l1, d2, l2) = m.destructured
            return listOf(
                base.copy(bearing = COMPASS.getValue(d1), limit = l1.toInt()),
                base.copy(bearing = COMPASS.getValue(d2), limit = l2.toInt()),
            )
        }
        val limitText = row.getValue("limit")
        val limit = if (limitText.isNotEmpty() && limitText.all { it.isDigit() }) limitText.toInt() else null
        return listOf(base.copy(bearing = parseBearing(direct), limit = limit))
    }

    private fun roundCoordinate(text: String): Double? =
        text.toBigDecimalOrNull()?.setScale(6, RoundingMode.HALF_EVEN)?.toDouble()

    private fun String.toBigDecimalOrNull(): BigDecimal? = try {
        BigDecimal(trim())
    } catch (_: NumberFormatException) {
        null
    }
}
