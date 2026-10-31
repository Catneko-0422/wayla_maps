package cc.nekocat.waylamaps.voice

import cc.nekocat.waylamaps.alerts.CameraAheadDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PhraseCatalogTest {
    private val catalog = PhraseCatalog.parse(File("src/main/assets/${PhraseCatalog.ASSET_PATH}").readText())

    @Test
    fun fillsSlotsAndJoinsText() {
        val resolved = catalog.resolve(
            "alert_ahead_context_limit",
            mapOf("distance" to 1050, "context" to "ctx_freeway_1", "type" to "type_speed_camera", "limit" to 100),
            "zh-TW",
        )

        assertEquals(listOf("ahead_1050m", "ctx_freeway_1", "type_speed_camera", "limit_100"), resolved.fragmentIds)
        assertEquals("前方一千零五十公尺，國道一號，測速照相，限速一百", resolved.text)
    }

    @Test
    fun languagesHaveTheirOwnWordOrder() {
        val resolved = catalog.resolve("alert_ahead", mapOf("distance" to 100, "type" to "type_speed_camera"), "en")

        assertEquals(listOf("type_speed_camera", "ahead_100m"), resolved.fragmentIds)
        assertEquals("Speed camera, in 100 meters", resolved.text)
    }

    @Test
    fun rejectsMissingArgumentAndUnknownValues() {
        assertThrows(IllegalArgumentException::class.java) {
            catalog.resolve("alert_ahead", mapOf("type" to "type_speed_camera"), "zh-TW")
        }
        assertThrows(IllegalArgumentException::class.java) {
            catalog.resolve("alert_ahead", mapOf("distance" to 123, "type" to "type_speed_camera"), "zh-TW")
        }
        assertThrows(IllegalArgumentException::class.java) {
            catalog.resolve("no_such_phrase", emptyMap(), "zh-TW")
        }
    }

    /** Every phrase resolves in every language for every combination of its slot values. */
    @Test
    fun bundledTableIsComplete() {
        for (phraseId in catalog.phraseIds) {
            for (language in catalog.languages) {
                for (args in slotCombinations(catalog.slotsOf(phraseId))) {
                    catalog.resolve(phraseId, args, language)
                }
            }
        }
    }

    @Test
    fun everySpokenCameraDistanceHasAFragment() {
        val distances = catalog.slots.getValue("distance")
        (0..300).map { CameraAheadDetector.spokenDistanceMeters(it.toDouble()) }.toSet().forEach {
            assertTrue("no fragment for $it m", it.toString() in distances)
        }
    }

    private fun slotCombinations(names: Set<String>): List<Map<String, Any>> =
        catalog.slots.filterKeys { it in names }.entries.fold(listOf(emptyMap())) { acc, (name, values) ->
            acc.flatMap { partial -> values.map { partial + (name to it) } }
        }
}
