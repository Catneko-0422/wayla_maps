package cc.nekocat.waylamaps.alerts

/** What to call a point in a prompt: phrase table fragments for its type and, optionally, its place. */
data class AlertDescription(
    /** A `type_*` fragment. */
    val type: String,
    /** A `ctx_*` fragment, only when it helps tell the point apart from roads nearby. */
    val context: String? = null,
)

/**
 * Describes speed cameras and enforcement points from the dataset text alone (address, direction
 * notes, 取締項目). Prompts stay short: one type, at most one place.
 */
object CameraDescriber {
    private val FREEWAY = Regex("國道\\s*([0-9]+|[一二三四五六七八九十]+)\\s*號?\\s*(甲)?")
    private val EXPRESSWAY = Regex("快速道路|快速公路")
    private val TUNNEL = Regex("隧道")
    private val RAMP = Regex("匝道|交流道")
    private const val ELEVATED = "高架"
    private val FREEWAYS = setOf("1", "1a", "2", "2a", "3", "3a", "4", "5", "6", "7", "8", "9", "10")
    private val CHINESE_DIGITS = mapOf(
        "一" to "1", "二" to "2", "三" to "3", "四" to "4", "五" to "5",
        "六" to "6", "七" to "7", "八" to "8", "九" to "9", "十" to "10",
    )

    private val RED_LIGHT = Regex("闖紅燈")
    private val SPEED = Regex("超速|測速")
    private val PEDESTRIAN = Regex("[停禮]?讓行人|車不讓人|行人優先|行人穿越")
    private val TURN = Regex("違規轉彎|不依規定轉彎|違規左轉|違規右轉|違規迴轉|轉彎")
    private val NOISE = Regex("噪音")
    private val FOLLOWING_DISTANCE = Regex("車距")
    private val ITEM_SEPARATOR = Regex("[、，,；;/]|\\s+")

    /**
     * @param roadKnown true when navigation knows the current road, in which case elevated
     *   cameras have already been matched to the rider's level and need no "on the elevated road".
     */
    fun describe(camera: SpeedCamera, roadKnown: Boolean): AlertDescription {
        val text = camera.address + camera.direction
        val type = when {
            AverageSpeedZones.isZone(camera) -> "type_average_speed_start"
            RED_LIGHT.containsMatchIn(text) -> "type_speed_red_light"
            else -> "type_speed_camera"
        }
        return AlertDescription(type, context(camera.address, roadKnown))
    }

    fun describe(point: EnforcementPoint, roadKnown: Boolean): AlertDescription =
        AlertDescription(enforcementType(point.items), context(point.location, roadKnown))

    fun enforcementType(items: String): String = when {
        SPEED.containsMatchIn(items) -> "type_intersection_speed"
        onlyMatches(items, NOISE) -> "type_noise"
        onlyMatches(items, FOLLOWING_DISTANCE) -> "type_following_distance"
        onlyMatches(items, PEDESTRIAN) -> "type_pedestrian"
        onlyMatches(items, TURN) -> "type_turn"
        onlyMatches(items, RED_LIGHT) -> "type_red_light"
        else -> "type_enforcement"
    }

    private fun context(address: String, roadKnown: Boolean): String? {
        FREEWAY.find(address)?.let { m ->
            val number = m.groupValues[1].let { CHINESE_DIGITS[it] ?: it }
            val key = number + if (m.groupValues[2].isNotEmpty()) "a" else ""
            if (key in FREEWAYS) return "ctx_freeway_$key"
        }
        return when {
            EXPRESSWAY.containsMatchIn(address) -> "ctx_expressway"
            ELEVATED in address && !roadKnown -> "ctx_elevated"
            TUNNEL.containsMatchIn(address) -> "ctx_tunnel"
            RAMP.containsMatchIn(address) -> "ctx_ramp"
            else -> null
        }
    }

    /** True when [items] lists something and every listed item matches [pattern]. */
    private fun onlyMatches(items: String, pattern: Regex): Boolean {
        val listed = items.split(ITEM_SEPARATOR).filter { it.isNotBlank() }
        return listed.isNotEmpty() && listed.all { pattern.containsMatchIn(it) }
    }
}
