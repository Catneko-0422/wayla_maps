package cc.nekocat.waylamaps.service

import cc.nekocat.waylamaps.alerts.Fix
import cc.nekocat.waylamaps.alerts.Geo

enum class RideStatusEvent { NO_FIX_YET, SIGNAL_LOST, SIGNAL_RECOVERED, STATIONARY_LONG }

/**
 * Watches position updates for problems worth saying out loud: no fix after starting, the
 * signal dropping and coming back, and standing still for a long time with only the alerts on.
 *
 * Pure Kotlin with an injected clock (milliseconds); not thread-safe.
 */
class RideStatusMonitor(
    private val noFixTimeoutMs: Long = 30_000,
    private val signalLostMs: Long = 10_000,
    private val stationaryMs: Long = 20 * 60_000,
    private val stationaryRadiusM: Double = 50.0,
) {
    private var startedAt = 0L
    private var lastFixAt: Long? = null
    private var noFixReported = false
    private var signalLost = false
    private var anchor: Fix? = null
    private var anchorSince = 0L
    private var stationaryReported = false

    fun start(now: Long) {
        startedAt = now
        lastFixAt = null
        noFixReported = false
        signalLost = false
        anchor = null
        stationaryReported = false
    }

    fun onFix(now: Long, fix: Fix): RideStatusEvent? {
        lastFixAt = now
        val current = anchor
        if (current == null || Geo.distanceMeters(current.lat, current.lng, fix.lat, fix.lng) > stationaryRadiusM) {
            anchor = fix
            anchorSince = now
            stationaryReported = false
        }
        if (!signalLost) return null
        signalLost = false
        return RideStatusEvent.SIGNAL_RECOVERED
    }

    /**
     * Call about once a second.
     * @param alertsOnly true when only the speed camera alerts are running (not navigating);
     *   standing still is normal at a destination while navigating ends on its own.
     */
    fun onTick(now: Long, alertsOnly: Boolean): RideStatusEvent? {
        val last = lastFixAt
        if (last == null) {
            if (!noFixReported && now - startedAt >= noFixTimeoutMs) {
                noFixReported = true
                return RideStatusEvent.NO_FIX_YET
            }
            return null
        }
        if (!signalLost && now - last >= signalLostMs) {
            signalLost = true
            return RideStatusEvent.SIGNAL_LOST
        }
        if (alertsOnly && !stationaryReported && anchor != null && now - anchorSince >= stationaryMs) {
            stationaryReported = true
            return RideStatusEvent.STATIONARY_LONG
        }
        return null
    }
}
