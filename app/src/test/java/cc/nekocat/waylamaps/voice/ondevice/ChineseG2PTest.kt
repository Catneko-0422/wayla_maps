package cc.nekocat.waylamaps.voice.ondevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChineseG2PTest {
    private val tables = File("src/main/assets/g2p_zh")
    private val g2p = ChineseG2P(
        File(tables, "symbols.txt").inputStream(),
        File(tables, "opencpop.txt").inputStream(),
        File(tables, "chars.txt").inputStream(),
        File(tables, "words.txt").inputStream(),
        File(tables, "t2s.txt").inputStream(),
    )

    @Test
    fun readsRoadNames() {
        assertEquals(listOf("zh", "ong1", "sh", "an1", "l", "u4"), g2p.phones("中山路"))
        // 一 before a fourth tone is said yi2, as in 一段.
        assertEquals(listOf("l", "u4", "y", "i2", "d", "uan4"), g2p.phones("路一段"))
    }

    @Test
    fun readsNumbers() {
        assertEquals("四十", ChineseG2P.numberToChinese(40))
        assertEquals("一百零一", ChineseG2P.numberToChinese(101))
        assertEquals("十二", ChineseG2P.numberToChinese(12))
        assertEquals("龙潭北路四十巷", g2p.normalize("龍潭北路40巷"))
    }

    @Test
    fun matchesGenieOnRealRoadNames() {
        val rows = File("src/test/resources/g2p_zh_golden.tsv").readLines().map { it.split('\t') }
        val misses = rows.filter { (name, phones) -> g2p.phones(name).joinToString(" ") != phones }
        misses.take(15).forEach { (name, phones) -> println("$name\texpected $phones\tgot ${g2p.phones(name).joinToString(" ")}") }
        val rate = 1.0 - misses.size.toDouble() / rows.size
        println("match rate ${"%.3f".format(rate)} of ${rows.size}")
        // The rest differ mostly where Genie itself is off for Taiwan (2段 read 兩段, 192 read digit by digit).
        assertTrue("match rate $rate", rate >= 0.9)
    }
}
