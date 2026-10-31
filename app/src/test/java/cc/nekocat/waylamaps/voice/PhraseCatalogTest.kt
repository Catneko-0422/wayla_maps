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
        val resolved = catalog.resolve("speed_camera_ahead_limit", mapOf("distance" to 300, "limit" to 50), "zh-TW")

        assertEquals(listOf("ahead_300m", "speed_camera", "limit_50"), resolved.fragmentIds)
        assertEquals("前方三百公尺，測速照相，限速五十", resolved.text)
    }

    @Test
    fun languagesHaveTheirOwnWordOrder() {
        val resolved = catalog.resolve("speed_camera_ahead", mapOf("distance" to 100), "en")

        assertEquals(listOf("speed_camera", "ahead_100m"), resolved.fragmentIds)
        assertEquals("Speed camera, in 100 meters", resolved.text)
    }

    @Test
    fun rejectsMissingArgumentAndUnknownValues() {
        assertThrows(IllegalArgumentException::class.java) {
            catalog.resolve("speed_camera_ahead", emptyMap(), "zh-TW")
        }
        assertThrows(IllegalArgumentException::class.java) {
            catalog.resolve("speed_camera_ahead", mapOf("distance" to 123), "zh-TW")
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
                for (args in slotCombinations()) {
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

    private fun slotCombinations(): List<Map<String, Any>> =
        catalog.slots.entries.fold(listOf(emptyMap())) { acc, (name, values) ->
            acc.flatMap { partial -> values.map { partial + (name to it) } }
        }
}
