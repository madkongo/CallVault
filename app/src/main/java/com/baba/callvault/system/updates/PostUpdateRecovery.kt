/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.system.updates

import com.baba.callvault.data.PrivilegedMode

/**
 * What must happen after the app is replaced, as a decision that can be tested without a device.
 *
 * **Why this is its own type.** The rule used to live inside the broadcast receiver as an early
 * `return`, and it was wrong in a way nothing could catch: recovery was skipped whenever
 * `WRITE_SECURE_SETTINGS` survived the update, on the reasoning that there was then nothing to heal.
 * But healing the grant was never the only thing an update breaks — **the update also kills the
 * privileged recorder and stops our foreground services.**
 *
 * Measured cost, 2026-09-06: an update at 11:35:05 stopped the services (`FOREGROUND_SERVICE_STOP` at
 * that second). At 11:42:59 a call came in; the app woke, started recording 14 s later, ran for eleven
 * minutes and wrote nothing, because there was no daemon to capture through. **A 13-minute call was
 * lost, and the next call — after something else had restarted the daemon — recorded perfectly.**
 *
 * So the invariant, pinned by its own test: **[ensureRecorder] is true in every case.** An app replace
 * must never leave the phone unable to record the next call.
 */
internal object PostUpdateRecovery {

    /**
     * @param healGrant re-grant `WRITE_SECURE_SETTINGS` over a transport that is already up.
     * @param ensureRecorder bring the daemon and the keep-alive service back. **Always true.**
     * @param restartShizukuService tear down and restart the Shizuku user service, which survives a
     *   replace still holding the path of an APK that no longer exists.
     * @param restartKeepAlive bring back the keep-alive foreground service, which the replace stopped.
     *   It is not merely a watchdog: it **hosts app-call detection**, so leaving it down costs VoIP
     *   recording entirely, and lets the daemon be reaped when the phone goes idle. Measured on the
     *   OP12 on 2026-09-06: the first version of this fix restored the daemon and not this, and
     *   `dumpsys activity services` showed no service at all afterwards.
     */
    data class Plan(
        val healGrant: Boolean,
        val ensureRecorder: Boolean,
        val restartShizukuService: Boolean,
        val restartKeepAlive: Boolean,
    )

    fun plan(mode: PrivilegedMode, grantSurvived: Boolean, voipEnabled: Boolean = false): Plan = Plan(
        // Only the embedded-ADB mode has this grant to lose, and only when it actually went missing.
        healGrant = !mode.needsShizuku && !grantSurvived,
        // The one thing that is never conditional.
        ensureRecorder = true,
        restartShizukuService = mode.needsShizuku,
        // Runs in every mode (P1a): in Shizuku mode it is the process anchor, so it comes back with app calls on or off.
        restartKeepAlive = com.baba.callvault.services.recording.KeepAlivePolicy.shouldRun(mode, voipEnabled),
    )
}
