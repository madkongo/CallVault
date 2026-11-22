/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

/** Android's own report of whether adbd is running — `init.svc.adbd`, which any app may read. */
enum class AdbdState {
    RUNNING,
    STOPPED,

    /** Unreadable, empty, or in between (`restarting`). Never treated as "down". */
    UNKNOWN;

    companion object {
        fun of(value: String?): AdbdState = when (value?.trim()) {
            "running" -> RUNNING
            "stopped" -> STOPPED
            else -> UNKNOWN
        }
    }
}

/** What CallVault may do about a stopped adbd. */
enum class AdbdRevival {
    NOTHING,

    /** Both switches off: switching Wireless debugging on starts adbd. */
    ENABLE_WIRELESS_DEBUGGING,

    /**
     * Wireless debugging reads on but adbd is stopped — what turning USB debugging off leaves behind. Only a
     * change of the switch makes AdbService start adbd again, so it is switched off and back on.
     */
    CYCLE_WIRELESS_DEBUGGING,

    /** No Wi-Fi, so Wireless debugging cannot run; writing it would be undone. */
    NEEDS_WIFI,

    /** No WRITE_SECURE_SETTINGS; only the user can change the switches. */
    NO_GRANT,
}

/** Why [AdbdRevivalPolicy] chose what it did — one per branch, so the log can name the real one (GH89-2). */
enum class AdbdRevivalReason(val text: String) {
    ADBD_NOT_STOPPED("adbd does not read stopped"),
    SHIZUKU_SERVER_ANSWERS("a Shizuku server still answers, which proves adbd is up"),
    USB_ON_WAITING_FOR_INIT("USB debugging reads on and adbd was first seen stopped just now; init gets a chance to restart it"),
    USB_ON_STUCK_CYCLE("USB debugging reads on but adbd has stayed stopped; init is not restarting it"),
    USB_ON_STUCK_WD_OFF("USB debugging reads on and adbd has stayed stopped, but Wireless debugging reads off, so there is nothing of ours to cycle"),
    NO_WIFI("no Wi-Fi, so Wireless debugging cannot run"),
    NO_GRANT("WRITE_SECURE_SETTINGS is not granted"),
    WD_ON_CYCLE("Wireless debugging reads on while adbd is stopped (what turning USB debugging off leaves)"),
    MAY_NOT_ENABLE("both switches read off and we may not switch Wireless debugging on here (Shizuku mode, or the user turned it off)"),
    BOTH_OFF_ENABLE("both switches read off"),
}

data class AdbdRevivalDecision(val revival: AdbdRevival, val reason: AdbdRevivalReason)

/**
 * How long `init.svc.adbd` has been reading stopped, as the revival logic has seen it. [checks] counts
 * sightings at least [AdbdStoppedTracker.MIN_GAP_MS] apart, so the switch observer and the keep-alive
 * arriving together are one sighting, not two.
 */
data class AdbdStoppedStreak(val checks: Int, val stoppedForMs: Long)

/** Counts consecutive stopped readings of adbd. Not thread-safe on its own: callers hold the revive lock. */
class AdbdStoppedTracker {
    private var firstMs = -1L
    private var lastCountedMs = -1L
    private var checks = 0

    fun observe(state: AdbdState, nowMs: Long): AdbdStoppedStreak {
        if (state != AdbdState.STOPPED) {
            firstMs = -1L; lastCountedMs = -1L; checks = 0
            return AdbdStoppedStreak(0, 0)
        }
        if (firstMs < 0) {
            firstMs = nowMs; lastCountedMs = nowMs; checks = 1
        } else if (nowMs - lastCountedMs >= MIN_GAP_MS) {
            lastCountedMs = nowMs; checks++
        }
        return AdbdStoppedStreak(checks, nowMs - firstMs)
    }

    companion object {
        const val MIN_GAP_MS = 5_000L
    }
}

/**
 * Decides how to bring adbd back. Free of `Context` so every branch is tested.
 *
 * Why it exists, measured 2026-09-14 (docs/dev-notes/2026-09-14-debugging-switches-model.md):
 * stock `init.usb.configfs.rc` has `on property:sys.usb.config=none … stop adbd`, so turning USB debugging
 * off stops adbd **even with Wireless debugging on**. AdbService does not restart it, because from its
 * point of view Wireless debugging never changed. On the OP9, switching Wireless debugging off and on
 * brought adbd back and left USB debugging off.
 */
object AdbdRevivalPolicy {

    /** Stopped this long with USB debugging reading on: init is not going to restart it (GH89-1, ~48 h on an Honor). */
    const val STUCK_AFTER_MS = 30_000L

    fun decide(
        adbd: AdbdState,
        usbDebuggingOn: Boolean,
        wirelessDebuggingOn: Boolean,
        wifi: WifiState,
        hasGrant: Boolean,
        mayEnable: Boolean = true,
        shizukuServerRunning: Boolean = false,
        stoppedStreak: AdbdStoppedStreak = AdbdStoppedStreak(checks = 1, stoppedForMs = 0),
    ): AdbdRevival = decideWithReason(
        adbd, usbDebuggingOn, wirelessDebuggingOn, wifi, hasGrant, mayEnable, shizukuServerRunning, stoppedStreak,
    ).revival

    /**
     * @param mayEnable Whether switching Wireless debugging ON from off is allowed here. False in Shizuku mode
     *   (CallVault does not own the switches there) and when the user turned the switch off themselves. A cycle
     *   of a switch that reads on is still allowed: it ends with the switch exactly as it was.
     * @param shizukuServerRunning Whether a Shizuku server answers right now. It is a shell-uid process hosted
     *   by adbd and dies with it, so one that answers is proof adbd is up whatever `init.svc.adbd` said a
     *   moment ago. Cycling on the stale reading would restart a live adbd and kill the server (#39).
     * @param stoppedStreak how long adbd has read stopped. With USB debugging on, init normally restarts adbd
     *   within a moment, so the first sighting waits; but on an Honor BVL-N49 (Android 17, GH #89) it stayed
     *   stopped ~48 h with both switches reading on, and "init restarts it" blocked revival forever. A streak
     *   past [STUCK_AFTER_MS] or two spaced checks is that case: cycle Wireless debugging, the measured fix.
     */
    fun decideWithReason(
        adbd: AdbdState,
        usbDebuggingOn: Boolean,
        wirelessDebuggingOn: Boolean,
        wifi: WifiState,
        hasGrant: Boolean,
        mayEnable: Boolean = true,
        shizukuServerRunning: Boolean = false,
        stoppedStreak: AdbdStoppedStreak = AdbdStoppedStreak(checks = 1, stoppedForMs = 0),
    ): AdbdRevivalDecision {
        fun d(r: AdbdRevival, why: AdbdRevivalReason) = AdbdRevivalDecision(r, why)
        if (adbd != AdbdState.STOPPED) return d(AdbdRevival.NOTHING, AdbdRevivalReason.ADBD_NOT_STOPPED)
        if (shizukuServerRunning) return d(AdbdRevival.NOTHING, AdbdRevivalReason.SHIZUKU_SERVER_ANSWERS)
        if (usbDebuggingOn) {
            val stuck = stoppedStreak.checks >= 2 || stoppedStreak.stoppedForMs >= STUCK_AFTER_MS
            if (!stuck) return d(AdbdRevival.NOTHING, AdbdRevivalReason.USB_ON_WAITING_FOR_INIT)
            // Never switches Wireless debugging on from off: only a switch that reads on is cycled.
            if (!wirelessDebuggingOn) return d(AdbdRevival.NOTHING, AdbdRevivalReason.USB_ON_STUCK_WD_OFF)
        }
        return when {
            wifi == WifiState.NOT_CONNECTED -> d(AdbdRevival.NEEDS_WIFI, AdbdRevivalReason.NO_WIFI)
            !hasGrant -> d(AdbdRevival.NO_GRANT, AdbdRevivalReason.NO_GRANT)
            wirelessDebuggingOn -> d(
                AdbdRevival.CYCLE_WIRELESS_DEBUGGING,
                if (usbDebuggingOn) AdbdRevivalReason.USB_ON_STUCK_CYCLE else AdbdRevivalReason.WD_ON_CYCLE,
            )
            !mayEnable -> d(AdbdRevival.NOTHING, AdbdRevivalReason.MAY_NOT_ENABLE)
            else -> d(AdbdRevival.ENABLE_WIRELESS_DEBUGGING, AdbdRevivalReason.BOTH_OFF_ENABLE)
        }
    }
}
