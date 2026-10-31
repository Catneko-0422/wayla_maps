package cc.nekocat.waylamaps.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoLockTest {
    private val lock = AutoLock()

    private fun ride(fromS: Int, toS: Int, kmh: Int?): List<AutoLock.Change> =
        (fromS..toS).mapNotNull { lock.update(it * 1_000L, kmh, enabled = true) }

    @Test
    fun locksAfterFiveSecondsMovingAndUnlocksAfterTenStopped() {
        assertEquals(emptyList<AutoLock.Change>(), ride(0, 4, 30))
        assertEquals(listOf(AutoLock.Change.LOCKED), ride(5, 20, 30))
        assertTrue(lock.locked)

        // A red light shorter than ten seconds keeps it locked.
        assertEquals(emptyList<AutoLock.Change>(), ride(21, 29, 0))
        assertEquals(emptyList<AutoLock.Change>(), ride(30, 31, 30))
        assertEquals(listOf(AutoLock.Change.UNLOCKED_STOPPED), ride(32, 45, 0))
        assertFalse(lock.locked)
    }

    @Test
    fun manualUnlockHoldsUntilTheNextStop() {
        ride(0, 10, 30)
        lock.unlockManually()
        assertEquals(emptyList<AutoLock.Change>(), ride(11, 60, 30))

        ride(61, 72, 0)
        assertEquals(listOf(AutoLock.Change.LOCKED), ride(73, 80, 30))
    }

    @Test
    fun fixesWithoutSpeedKeepTheState() {
        ride(0, 10, 30)
        assertTrue(lock.locked)
        ride(11, 20, null)
        assertTrue(lock.locked)
    }

    @Test
    fun neverLocksWhenDisabledOrSpeedUnknown() {
        ride(0, 30, null)
        assertFalse(lock.locked)
        assertNull((0..30).firstNotNullOfOrNull { lock.update(it * 1_000L, 50, enabled = false) })
        assertFalse(lock.locked)
    }
}
