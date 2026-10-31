package cc.nekocat.waylamaps.voice

import cc.nekocat.waylamaps.alerts.AlertDescription
import org.junit.Assert.assertEquals
import org.junit.Test

class AlertPhrasesTest {
    private val limits = listOf("50", "100")

    @Test
    fun picksPhraseByWhatIsKnown() {
        val plain = AlertPhrases.ahead(AlertDescription("type_speed_camera"), 286.0, null, limits)
        assertEquals("alert_ahead", plain.phraseId)
        assertEquals(mapOf("distance" to 300, "type" to "type_speed_camera"), plain.args)

        val full = AlertPhrases.ahead(AlertDescription("type_speed_camera", "ctx_freeway_1"), 290.0, 100, limits)
        assertEquals("alert_ahead_context_limit", full.phraseId)
        assertEquals(100, full.args["limit"])
    }

    @Test
    fun dropsLimitWithoutFragment() {
        val prompt = AlertPhrases.ahead(AlertDescription("type_enforcement"), 200.0, 45, limits)

        assertEquals("alert_ahead", prompt.phraseId)
        assertEquals(false, "limit" in prompt.args)
    }
}
