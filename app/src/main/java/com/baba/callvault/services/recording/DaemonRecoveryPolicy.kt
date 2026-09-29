/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the keep-alive should do on this relaunch attempt. */
enum class RecoveryStep {
    /** A usable endpoint exists and nothing is obviously wrong — just connect and launch. */
    CONNECT,

    /** There is nothing this app can dial. Switch Wireless debugging on before attempting anything. */
    RESTORE_WIRELESS_DEBUGGING,

    /**
     * Attempts keep timing out against an endpoint that looks fine. Tear the ADB connection down and
     * rebuild it rather than retrying into the same wedged state.
     */
    REBUILD_CONNECTION,
}

/**
 * Decides how to recover a dead daemon, and notices when recovery itself has stopped working.
 *
 * **The 2026-08-18 wedge this exists to prevent.** A OnePlus 12 was found with no daemon and recording
 * silently dead. It had survived the app being force-quit *and* a full reboot; every keep-alive cycle
 * logged the same pair of lines, indefinitely:
 *
 * ```
 * keep-alive: daemon down — relaunching (force=false offline=true)
 * keep-alive: relaunch still blocked after 45000ms — abandoning it and dropping the ADB connection
 * ```
 *
 * ### What counts as an endpoint
 *
 * Two separate things must both hold before the app can connect, and the old guard conflated them:
 *
 *  - **`adbd` must be running**, which happens only while USB debugging or Wireless debugging is
 *    enabled.
 *  - **It must be listening on a TCP port this app can dial.** The embedded client speaks TCP, so a
 *    USB cable is not an endpoint, and an armed `service.adb.tcp.port` says only *where* `adbd` would
 *    listen — not that it is running. Assuming otherwise caused the 1.4.8 regression; see
 *    `adbd-transport-and-oem-quirks`.
 *
 * So a usable endpoint is Wireless debugging, or an armed loopback port **with `adbd` alive**. USB
 * debugging alone satisfies neither: it keeps `adbd` up but offers nothing to dial. The old guard
 * treated it as sufficient and skipped the restore, so a device with USB debugging on, Wireless
 * debugging off and no armed loopback had no way back.
 *
 * ### Why that alone was not enough
 *
 * On the device that actually wedged, the loopback port *was* armed and `adbd` *was* alive, so an
 * endpoint genuinely existed — and connecting still hung every time. Restoring an endpoint could not
 * have helped there. The escape is [RecoveryStep.REBUILD_CONNECTION]: after [escalateAfterFailures]
 * consecutive failures, stop trusting the endpoint, drop the connection and rebuild. Retrying an
 * identical attempt is not a recovery strategy.
 *
 * Restoring a missing endpoint outranks escalating, because rebuilding a connection to nothing cannot
 * help.
 *
 * Deliberately pure and separate from [DaemonKeepAliveService], following [RewarmGate]: the service
 * cannot be unit tested, and this is the part that was wrong.
 *
 * Thread-safe: the watchdog handler thread reads [nextStep] while a relaunch worker reports the
 * outcome, so the streak genuinely crosses threads.
 *
 * @param escalateAfterFailures consecutive failures tolerated before rebuilding the connection.
 */
class DaemonRecoveryPolicy(
    private val escalateAfterFailures: Int = DEFAULT_ESCALATE_AFTER_FAILURES,
) {

    private var consecutiveFailures = 0

    private val _stuckState = MutableStateFlow(false)

    /** [isStuck] as a flow, so Home's card changes the moment recovery gets stuck or comes back. */
    val stuckState: StateFlow<Boolean> = _stuckState.asStateFlow()

    /**
     * Whether recovery has failed enough times running to be worth telling the user about.
     *
     * The original outage was invisible — the app showed nothing while recording was dead for hours.
     * Home reads this to say so.
     */
    val isStuck: Boolean
        @Synchronized get() = consecutiveFailures >= escalateAfterFailures

    /**
     * The action to take now.
     *
     * @param wirelessDebuggingEnabled whether Wireless debugging is on — an endpoint by itself.
     * @param usbDebuggingEnabled whether USB debugging is on. Not an endpoint on its own; it only
     *   establishes that `adbd` is running, which is what makes an armed loopback port dialable.
     * @param loopbackArmed whether `service.adb.tcp.port` matches the loopback port we expect.
     */
    @Synchronized
    fun nextStep(
        wirelessDebuggingEnabled: Boolean,
        usbDebuggingEnabled: Boolean,
        loopbackArmed: Boolean,
    ): RecoveryStep {
        val adbdRunning = wirelessDebuggingEnabled || usbDebuggingEnabled
        val hasEndpoint = wirelessDebuggingEnabled || (loopbackArmed && adbdRunning)

        return when {
            // Nothing to dial. Nothing else is worth trying until there is.
            !hasEndpoint -> RecoveryStep.RESTORE_WIRELESS_DEBUGGING

            // An endpoint exists and attempts keep timing out against it anyway.
            consecutiveFailures >= escalateAfterFailures -> RecoveryStep.REBUILD_CONNECTION

            else -> RecoveryStep.CONNECT
        }
    }

    /** Records a relaunch that did not bring the daemon up (including a timeout). */
    @Synchronized
    fun onAttemptFailed() {
        // Saturate rather than overflow: this counter is only compared against a small threshold, and a
        // device left down for days must not wrap back into looking healthy.
        if (consecutiveFailures < Int.MAX_VALUE) consecutiveFailures++
        _stuckState.value = isStuck
    }

    /** Records a relaunch that brought the daemon up, clearing the streak. */
    @Synchronized
    fun onAttemptSucceeded() {
        consecutiveFailures = 0
        _stuckState.value = false
    }

    companion object {
        /**
         * Two failures is ~90 s of a dead recorder at the current launch budget — long enough not to
         * escalate over one slow launch, short enough to recover well inside a single missed call.
         */
        const val DEFAULT_ESCALATE_AFTER_FAILURES = 2
    }
}
