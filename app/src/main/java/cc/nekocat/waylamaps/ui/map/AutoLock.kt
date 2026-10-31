package cc.nekocat.waylamaps.ui.map

/**
 * When to lock the control panel while moving, so it cannot be operated by accident on a
 * handlebar mount. Locks after [lockAfterMs] above [lockSpeedKmh]; unlocks after [unlockAfterMs]
 * at or below it. A manual unlock (by a passenger) holds until the vehicle has stopped once.
 *
 * Pure Kotlin with an injected clock (milliseconds); not thread-safe.
 */
class AutoLock(
    private val lockSpeedKmh: Int = 10,
    private val lockAfterMs: Long = 5_000,
    private val unlockAfterMs: Long = 10_000,
) {
    enum class Change { LOCKED, UNLOCKED_STOPPED }

    var locked = false
        private set

    private var fastSince: Long? = null
    private var slowSince: Long? = null
    private var manuallyUnlocked = false

    /** @param speedKmh null when unknown (no ride running): never locks, and unlocks. */
    fun update(now: Long, speedKmh: Int?, enabled: Boolean): Change? {
        if (!enabled || speedKmh == null) {
            reset()
            return null
        }
        if (speedKmh > lockSpeedKmh) {
            slowSince = null
            val since = fastSince ?: now.also { fastSince = it }
            if (!locked && !manuallyUnlocked && now - since >= lockAfterMs) {
                locked = true
                return Change.LOCKED
            }
        } else {
            fastSince = null
            val since = slowSince ?: now.also { slowSince = it }
            if (now - since >= unlockAfterMs) {
                manuallyUnlocked = false
                if (locked) {
                    locked = false
                    return Change.UNLOCKED_STOPPED
                }
            }
        }
        return null
    }

    fun unlockManually() {
        locked = false
        manuallyUnlocked = true
    }

    private fun reset() {
        locked = false
        manuallyUnlocked = false
        fastSince = null
        slowSince = null
    }
}
