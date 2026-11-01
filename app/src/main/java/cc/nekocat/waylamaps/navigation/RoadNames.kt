package cc.nekocat.waylamaps.navigation

/**
 * Turns the Navigation SDK's road text into the short name a person would say. The SDK packs
 * route numbers, alternative names and sign text together ("雲193鄉道 永興路",
 * "台3線 仁義路 / 內山公路", "30 號出口 國道1號 / 大林"); read out whole, that is long and odd.
 */
object RoadNames {
    /** A named road: 中山路, 龍潭北路40巷, 內山公路, 市民大道. */
    private val NAMED = Regex("[路街巷弄段道橋]$")

    /** A route number: 台3線, 國道1號, 雲193鄉道, 縣道154. */
    private val ROUTE = Regex("^(國道|台|臺|快速公路|縣道|鄉道|[\\u4e00-\\u9fff])?[0-9]+[甲乙丙]?(線|號|鄉道|縣道)?$|^(縣道|鄉道)[0-9]+$")

    /**
     * The name to say, or null when there is none worth saying (an unnamed road, or an
     * instruction such as 靠左). A named road beats a route number, and a freeway beats other
     * route numbers.
     */
    fun spoken(text: String): String? {
        val tokens = text.split('/', ' ', '　').map(String::trim).filter { it.isNotEmpty() }
            // Exit numbers and the like are sign text, not the road.
            .filterNot { "出口" in it || it.all(Char::isDigit) || it == "號" }
        val routes = tokens.filter { ROUTE.matches(it) }
        val named = tokens.filter { it !in routes && NAMED.containsMatchIn(it) }
        return named.firstOrNull()
            ?: routes.firstOrNull { it.startsWith("國道") }
            ?: routes.firstOrNull()
    }
}
