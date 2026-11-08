/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

/**
 * When [MicOpAutoHeal] may replace the recorder daemon to clear a stuck microphone indicator.
 *
 * The dot costs no audio — nothing is recording while it shows. A daemon that is ended at the wrong moment, or that
 * cannot be brought back, costs a real call. So the default here is to do nothing, and every rule names a way the heal
 * could do more harm than the dot.
 *
 * Pure, so each rule is tested on its own.
 */
object MicOpHealPolicy {

    /**
     * After a heal, how long before another is allowed.
     *
     * If the op survives a replacement it belongs to something else running as shell; without this, every call would
     * end with the daemon being replaced for nothing.
     */
    const val COOLDOWN_MS: Long = 10 * 60_000L

    enum class Decision {
        /** Replace the daemon. */
        HEAL,

        /** The indicator is not stuck. */
        NOTHING_HELD,

        /** A call or recording is running; the op may be the recording itself, and ending the daemon would end it. */
        CALL_ACTIVE,

        /** Healed recently; see [COOLDOWN_MS]. */
        COOLING_DOWN,

        /** No way to relaunch the daemon if it is ended, so the next call would not record. The dot is the lesser harm. */
        NO_WAY_BACK,
    }

    fun decide(
        shellMicOpsHeld: Int,
        callOrRecordingActive: Boolean,
        canRelaunch: Boolean,
        lastHealAtMs: Long?,
        nowMs: Long,
    ): Decision = when {
        shellMicOpsHeld <= 0 -> Decision.NOTHING_HELD
        callOrRecordingActive -> Decision.CALL_ACTIVE
        lastHealAtMs != null && nowMs - lastHealAtMs < COOLDOWN_MS -> Decision.COOLING_DOWN
        !canRelaunch -> Decision.NO_WAY_BACK
        else -> Decision.HEAL
    }

    /**
     * Shizuku mode: the recorder is CallVault's own Shizuku user service, started and restarted by the app (as after
     * every update), so it can be replaced whenever Shizuku itself is running. Until 2026-11-07 Shizuku mode was left
     * out ("not ours to end"), and a OnePlus CPH2747 on Android 17 in Shizuku mode had to stop CallVault by hand to
     * clear the microphone after some calls.
     */
    fun canRelaunchShizuku(shizukuReady: Boolean): Boolean = shizukuReady

    /**
     * Whether the keep-alive could bring the daemon back if it were ended now.
     *
     * An endpoint is what [DaemonRecoveryPolicy] counts as one — Wireless debugging, or an armed loopback port while
     * `adbd` is up — plus the WRITE_SECURE_SETTINGS grant, which lets the recovery switch Wireless debugging back on as
     * long as there is Wi-Fi for it. The network rule is the keep-alive's own: off Wi-Fi it relaunches only when offline
     * recording is on.
     */
    fun canRelaunch(
        wdEnabled: Boolean,
        usbDebuggingEnabled: Boolean,
        loopbackArmed: Boolean,
        hasWriteSecureSettings: Boolean,
        offlineEnabled: Boolean,
        wifiConnected: Boolean,
    ): Boolean {
        val adbdRunning = wdEnabled || usbDebuggingEnabled
        val endpoint = wdEnabled || (loopbackArmed && adbdRunning) || (hasWriteSecureSettings && wifiConnected)
        val network = offlineEnabled || wifiConnected
        return endpoint && network
    }
}
