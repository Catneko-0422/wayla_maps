package cc.nekocat.waylamaps.data

/** Minimal RFC 4180 reader: quoted fields may contain commas, doubled quotes and line breaks. */
object Csv {
    /** All rows of [text], without a leading BOM; blank lines are skipped. */
    fun parse(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var fields = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var i = 0
        val input = text.removePrefix("\uFEFF")

        fun endRow() {
            fields += current.toString()
            current.clear()
            if (fields.any { it.isNotBlank() }) rows += fields
            fields = mutableListOf()
        }

        while (i < input.length) {
            val c = input[i]
            when {
                quoted && c == '"' && input.getOrNull(i + 1) == '"' -> { current.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> { fields += current.toString(); current.clear() }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && input.getOrNull(i + 1) == '\n') i++
                    endRow()
                }
                else -> current.append(c)
            }
            i++
        }
        if (current.isNotEmpty() || fields.isNotEmpty()) endRow()
        return rows
    }
}
