/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import android.content.Context
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.system.health.SilentFailureNotifier
import com.baba.callvault.utils.AppLogger

/**
 * Whether Android has forgotten CallVault's Wireless-debugging pairing (issue #43).
 *
 * Android deletes a pairing whose key has not made a key-authenticated connection for 7 days
 * (`adb_allowed_connection_time`); Wireless-debugging (TLS) connections do not reset that clock, loopback
 * ones do. When it happens, libadb's connect throws `AdbPairingRequiredException` — which [AdbShell] used to
 * swallow into "refused", so the recorder retried forever and the user saw only "ADB not connected".
 *
 * **One refusal is not proof.** A flaky TLS handshake can look the same, and telling a user whose pairing is
 * fine to pair again is the worse mistake. So the pairing counts as lost only after [REFUSALS_BEFORE_LOST]
 * pairing refusals in a row, and any successful connection — Wireless debugging, loopback, or a fresh
 * pairing — wipes the count.
 *
 * Deliberately separate from `adb_paired`: clearing that flag would send an established user back through
 * onboarding. This only drives a Home card and a log line; recordings and settings are never touched.
 */
object PairingLoss {
    private const val TAG = "CV:PairingLoss"

    /** Refusals in a row, with no success between them, before the pairing counts as gone. */
    const val REFUSALS_BEFORE_LOST = 3

    /** The count after one more pairing refusal. Capped, so months of retries cannot overflow it. */
    fun afterRefusal(refusals: Int): Int = (refusals + 1).coerceAtMost(REFUSALS_BEFORE_LOST)

    /** The count after any successful connection or pairing. */
    fun afterConnected(): Int = 0

    fun isLost(refusals: Int): Boolean = refusals >= REFUSALS_BEFORE_LOST

    /** Records one `AdbPairingRequiredException` from a Wireless-debugging connect. */
    fun recordRefusal(context: Context) {
        val prefs = AppPreferences(context)
        val before = prefs.getPairingRefusals()
        val after = afterRefusal(before)
        prefs.setPairingRefusals(after)
        if (isLost(after) && !isLost(before)) {
            AppLogger.w(TAG, "Android no longer trusts CallVault's pairing ($after refusals in a row) — the user must pair again")
            SilentFailureNotifier.warnPairingLost(context)
        } else if (!isLost(after)) {
            AppLogger.w(TAG, "Wireless debugging refused CallVault's pairing ($after of $REFUSALS_BEFORE_LOST before it counts as lost)")
        }
    }

    /** Records a successful connection or pairing; clears any refusals. */
    fun recordConnected(context: Context) {
        val prefs = AppPreferences(context)
        if (prefs.getPairingRefusals() == 0) return
        if (isLost(prefs.getPairingRefusals())) {
            AppLogger.i(TAG, "CallVault's pairing works again")
            SilentFailureNotifier.clearPairingLost(context)
        }
        prefs.setPairingRefusals(afterConnected())
    }

    fun isLost(context: Context): Boolean = isLost(AppPreferences(context).getPairingRefusals())
}
