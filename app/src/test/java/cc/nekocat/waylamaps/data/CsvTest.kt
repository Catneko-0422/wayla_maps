package cc.nekocat.waylamaps.data

import org.junit.Assert.assertEquals
import org.junit.Test

class CsvTest {
    @Test
    fun handlesQuotesCommasAndLineBreaksInFields() {
        val text = "\uFEFFa,\"b\nc\",d\r\n\"x,y\",\"say \"\"hi\"\"\",\r\n\r\nlast"

        assertEquals(
            listOf(listOf("a", "b\nc", "d"), listOf("x,y", "say \"hi\"", ""), listOf("last")),
            Csv.parse(text),
        )
    }
}
