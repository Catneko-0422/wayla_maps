package cc.nekocat.waylamaps.voice.ondevice

import java.io.InputStream

/**
 * Chinese text to GPT-SoVITS v2 phone ids, for short texts such as road names, on the phone.
 *
 * A compact version of Genie-TTS's Chinese G2P: traditional characters become simplified (as in
 * Genie), numbers are read out, words come from a longest-match dictionary that only lists
 * readings differing from character by character (多音字 in context, third tones changing inside
 * a word), then 一 and 不 change tone, and each syllable maps to an initial and a toned final
 * (the Opencpop phone set). Tables come from tools/voice/export_ondevice.py.
 *
 * Pure Kotlin so it can be unit tested against Genie's own output.
 */
class ChineseG2P(
    /** symbols.txt: one symbol per line, the line number is its id. */
    symbols: InputStream,
    /** opencpop.txt: "lu<TAB>l u", a toneless syllable to its initial and final. */
    syllables: InputStream,
    /** chars.txt: "路<TAB>lu4", each character's usual reading. */
    chars: InputStream,
    /** words.txt: "长安<TAB>chang2 an1", readings that differ from the characters'. */
    words: InputStream,
    /** t2s.txt: "長<TAB>长", traditional to simplified. */
    traditional: InputStream,
) {
    private val simplified: Map<Char, Char> = traditional.tsv().entries.associate { it.key.first() to it.value.first() }
    private val symbolIds: Map<String, Int> = symbols.lines().withIndex().associate { (index, symbol) -> symbol to index }
    private val syllableTable: Map<String, Pair<String, String>> = syllables.tsv().mapValues { (_, phones) ->
        phones.split(' ').let { it[0] to it[1] }
    }
    private val charReadings: Map<Char, String> = chars.tsv().mapKeys { it.key.first() }
    private val wordReadings: Map<String, List<String>> = words.tsv().mapValues { it.value.split(' ') }
    private val longestWord = wordReadings.keys.maxOfOrNull { it.length } ?: 1

    /** Phone symbols for [text], e.g. "中山路" to [zh, ong1, sh, an1, l, u4]. */
    fun phones(text: String): List<String> {
        val syllables = sandhi(readings(normalize(text)))
        return syllables.flatMap { (char, pinyin) ->
            if (pinyin == null) return@flatMap listOfNotNull(PUNCTUATION[char])
            val tone = pinyin.last().takeIf(Char::isDigit) ?: '5'
            val (initial, final) = syllableTable[pinyin.trimEnd { it.isDigit() }] ?: return@flatMap emptyList()
            listOf(initial, "$final$tone")
        }.filter { it in symbolIds }
    }

    fun ids(text: String): LongArray = phones(text).map { symbolIds.getValue(it).toLong() }.toLongArray()

    /** Digits read as numbers ("40巷" to "四十巷"); everything but Han characters and pauses dropped. */
    internal fun normalize(text: String): String {
        val read = DIGITS.replace(text) { numberToChinese(it.value.toLong()) }
        return read.filter { it in '一'..'鿿' || it in PUNCTUATION }.map { simplified[it] ?: it }.joinToString("")
    }

    /** Each character with its reading (null for punctuation), words first by longest match. */
    private fun readings(text: String): List<Pair<Char, String?>> {
        val result = ArrayList<Pair<Char, String?>>(text.length)
        var i = 0
        while (i < text.length) {
            val match = (minOf(longestWord, text.length - i) downTo 2).firstNotNullOfOrNull { length ->
                wordReadings[text.substring(i, i + length)]
            }
            if (match != null) {
                match.forEachIndexed { k, pinyin -> result += text[i + k] to pinyin }
                i += match.size
            } else {
                result += text[i] to charReadings[text[i]]
                i++
            }
        }
        return result
    }

    /** 不 and 一 before a fourth tone become second tone, 一 before others fourth. */
    private fun sandhi(syllables: List<Pair<Char, String?>>): List<Pair<Char, String?>> {
        val out = syllables.toMutableList()
        fun tone(i: Int) = out.getOrNull(i)?.second?.lastOrNull()
        for (i in out.indices) {
            val (char, pinyin) = out[i]
            if (pinyin == null) continue
            val next = tone(i + 1)
            when (char) {
                '不' -> if (next == '4') out[i] = char to "bu2"
                '一' -> when {
                    // A number, or the last syllable: 一 keeps its own tone.
                    next == null || out[i + 1].first in NUMERALS -> Unit
                    next == '4' || next == '5' -> out[i] = char to "yi2"
                    else -> out[i] = char to "yi4"
                }
            }
        }
        return out
    }

    companion object {
        private val DIGITS = Regex("[0-9]+")
        private const val NUMERALS = "零一二三四五六七八九十百千萬"
        private val PUNCTUATION = mapOf('，' to ",", '、' to ",", '。' to ".", ',' to ",", '.' to ".")

        private fun InputStream.lines(): List<String> = bufferedReader(Charsets.UTF_8).use { it.readLines() }

        private fun InputStream.tsv(): Map<String, String> = lines().mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0) null else line.substring(0, tab) to line.substring(tab + 1)
        }.toMap()

        /** 0 to 99 999 999, as said aloud: 40 四十, 101 一百零一, 1000 一千, 10 十. */
        internal fun numberToChinese(value: Long): String {
            if (value == 0L) return "零"
            if (value >= 100_000_000) return value.toString().map { "零一二三四五六七八九"[it - '0'] }.joinToString("")
            val digits = "零一二三四五六七八九"
            fun below10k(n: Int): String {
                val units = listOf(1000 to "千", 100 to "百", 10 to "十", 1 to "")
                val sb = StringBuilder()
                var zero = false
                for ((unit, name) in units) {
                    val d = n / unit % 10
                    if (d == 0) {
                        if (sb.isNotEmpty()) zero = true
                        continue
                    }
                    if (zero) sb.append('零').also { zero = false }
                    sb.append(digits[d]).append(name)
                }
                return sb.toString()
            }
            val high = (value / 10_000).toInt()
            val low = (value % 10_000).toInt()
            val text = if (high > 0) {
                below10k(high) + "萬" + (if (low in 1..999) "零" else "") + below10k(low)
            } else {
                below10k(low)
            }
            // 十二 rather than 一十二.
            return if (text.startsWith("一十")) text.drop(1) else text
        }
    }
}
