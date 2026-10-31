package cc.nekocat.waylamaps.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceModeTest {
    @Test
    fun allPlaysEverything() {
        Priority.entries.forEach { assertTrue(VoiceMode.ALL.allows(it)) }
    }

    @Test
    fun essentialKeepsTurnsAndCameras() {
        assertTrue(VoiceMode.ESSENTIAL.allows(Priority.TURN))
        assertTrue(VoiceMode.ESSENTIAL.allows(Priority.SPEED_CAMERA))
        assertFalse(VoiceMode.ESSENTIAL.allows(Priority.TRAFFIC))
        assertFalse(VoiceMode.ESSENTIAL.allows(Priority.CHATTER))
    }

    @Test
    fun mutedPlaysNothing() {
        Priority.entries.forEach { assertFalse(VoiceMode.MUTED.allows(it)) }
    }

    @Test
    fun nextCyclesThroughAllModes() {
        assertEquals(VoiceMode.ESSENTIAL, VoiceMode.ALL.next())
        assertEquals(VoiceMode.MUTED, VoiceMode.ESSENTIAL.next())
        assertEquals(VoiceMode.ALL, VoiceMode.MUTED.next())
    }
}
