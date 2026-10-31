package cc.nekocat.waylamaps.data.enforcement

import cc.nekocat.waylamaps.alerts.EnforcementKind
import cc.nekocat.waylamaps.data.Csv
import cc.nekocat.waylamaps.data.SpeedCameraCsvParser
import java.math.RoundingMode
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Parses the county traffic enforcement (科技執法) CSVs listed in `assets/enforcement/sources.json`.
 *
 * Each county uses its own column names, encoding (UTF-8 or Big5) and quirks, so columns are
 * found by name and coordinates are sanity-checked rather than trusted:
 * - Taichung and Kinmen publish latitude and longitude swapped; swapped pairs are put right.
 * - Yunlin repeats the header in Chinese on the second line; rows without coordinates drop out.
 * - Average-speed sections (區間平均速率) are not points and are skipped.
 */
object EnforcementCsvParser {
    data class Point(
        val lat: Double,
        val lng: Double,
        val location: String,
        val items: String,
        val bearingDeg: Int?,
        val limitKmh: Int?,
        val kind: EnforcementKind,
    )

    data class Parsed(val points: List<Point>, val rejectedRows: Int)

    // Taiwan including Penghu, Kinmen and Matsu.
    private val LAT_RANGE = 21.8..26.5
    private val LNG_RANGE = 118.0..122.1

    /** Header prefixes per field, most specific first. Headers are compared without whitespace. */
    private val LOCATION = listOf("設置地點", "設置位置", "測照地點", "地點", "Address")
    private val ITEMS = listOf("取締項目", "違規取締項目", "ban")
    private val LAT = listOf("座標緯", "緯度", "Latitude")
    private val LNG = listOf("座標經", "經度", "Longitude")
    private val DIRECTION = listOf("拍攝方向", "拍攝行向", "測照行向", "測照方向", "direct")
    private val LIMIT = listOf("速限", "limit")
    private val TYPE = listOf("科技執法種類")

    private const val AVERAGE_SPEED = "區間"

    /** Violations committed while moving through, which make a point worth announcing. */
    private val MOVING = Regex(
        "闖紅燈|紅燈|讓行人|讓人|轉彎|迴轉|超速|測速|逆向|號誌|標線|標誌|車道|雙白|左轉|淨空|人行|方向燈|跨越|違規行駛|路口各項違規",
    )
    private val SPEED = Regex("超速|測速")
    private val PARKING = Regex("停車")
    private val VEHICLE_BAN = Regex("禁行|禁駛|車種")

    /** Decodes UTF-8 when the bytes are valid UTF-8, otherwise Big5 (Taipei, Taoyuan, Keelung, Miaoli). */
    fun decode(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        String(bytes, Charset.forName("Big5"))
    }

    fun parse(bytes: ByteArray): Parsed = parse(decode(bytes))

    fun parse(text: String): Parsed {
        val rows = Csv.parse(text)
        val headerIndex = rows.indexOfFirst { row -> row.column(LAT) >= 0 && row.column(LOCATION) >= 0 }
        require(headerIndex >= 0) { "No header with location and coordinate columns" }
        val header = rows[headerIndex]
        val location = header.column(LOCATION)
        val lat = header.column(LAT)
        val lng = header.column(LNG)
        require(lng >= 0) { "No longitude column" }
        val items = header.column(ITEMS)
        val direction = header.column(DIRECTION)
        val limit = header.column(LIMIT)
        val type = header.column(TYPE)

        // One device per place and direction; rows listing further items for it are merged in.
        val points = LinkedHashMap<Triple<Double, Double, Int?>, Point>()
        var rejected = 0
        for (row in rows.drop(headerIndex + 1)) {
            fun field(index: Int) = if (index < 0) "" else row.getOrNull(index)?.trim().orEmpty()
            if (AVERAGE_SPEED in field(type)) continue

            val coordinates = coordinates(field(lat), field(lng))
            if (coordinates == null) {
                rejected++
                continue
            }
            val bearing = SpeedCameraCsvParser.parseBearing(field(direction))
            val key = Triple(coordinates.first, coordinates.second, bearing)
            val itemText = field(items)
            val limitKmh = field(limit).takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toInt()
            val existing = points[key]
            points[key] = if (existing == null) {
                Point(coordinates.first, coordinates.second, field(location), itemText, bearing, limitKmh, kindOf(itemText))
            } else {
                val merged = listOf(existing.items, itemText).filter { it.isNotBlank() }.distinct().joinToString("、")
                existing.copy(items = merged, limitKmh = existing.limitKmh ?: limitKmh, kind = kindOf(merged))
            }
        }
        return Parsed(points.values.toList(), rejected)
    }

    fun kindOf(items: String): EnforcementKind = when {
        items.isBlank() || MOVING.containsMatchIn(items) -> EnforcementKind.INTERSECTION
        PARKING.containsMatchIn(items) -> EnforcementKind.PARKING
        VEHICLE_BAN.containsMatchIn(items) -> EnforcementKind.VEHICLE_BAN
        else -> EnforcementKind.INTERSECTION
    }

    /** True when the published items are only about speed, which a speed camera already covers. */
    fun isSpeedOnly(items: String): Boolean =
        SPEED.containsMatchIn(items) && !MOVING.containsMatchIn(items.replace(SPEED, ""))

    /** WGS84 (lat, lng) rounded to 6 decimals, swapping a reversed pair; null when unusable. */
    private fun coordinates(latText: String, lngText: String): Pair<Double, Double>? {
        val a = latText.toBigDecimalOrNull() ?: return null
        val b = lngText.toBigDecimalOrNull() ?: return null
        val first = a.setScale(6, RoundingMode.HALF_EVEN).toDouble()
        val second = b.setScale(6, RoundingMode.HALF_EVEN).toDouble()
        return when {
            first in LAT_RANGE && second in LNG_RANGE -> first to second
            second in LAT_RANGE && first in LNG_RANGE -> second to first
            else -> null
        }
    }

    private fun List<String>.column(prefixes: List<String>): Int {
        val names = map { it.filterNot(Char::isWhitespace).removePrefix("\uFEFF") }
        for (prefix in prefixes) {
            val index = names.indexOfFirst { it.startsWith(prefix, ignoreCase = true) }
            if (index >= 0) return index
        }
        return -1
    }
}
