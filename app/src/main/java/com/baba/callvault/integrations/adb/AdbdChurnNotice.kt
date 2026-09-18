/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

import android.content.Context
import com.baba.callvault.server.ShizukuBackend
import com.baba.callvault.utils.AppLogger

/**
 * Wraps the handful of operations that deliberately restart `adbd`, so one that stops somebody else's
 * Shizuku server starts it again and says so, instead of leaving it a mystery.
 *
 * There are exactly three of them: arming the off-Wi-Fi loopback listener (`tcpip:`), closing it again
 * (`usb:`), and changing the Default USB Configuration (`svc usb setScreenUnlockedFunctions`). Writing
 * `adb_wifi_enabled` is deliberately **not** on the list: measured on the OP9 on 2026-09-18, a full
 * off-and-on of Wireless debugging with USB debugging enabled left `adbd`'s pid unchanged and Shizuku
 * running, and the one path that cycles it when USB debugging is off ([AdbShell.reviveAdbdIfStopped])
 * only ever runs when `adbd` is already stopped — by which time any Shizuku server is already gone.
 *
 * See [ShizukuChurnPolicy] for why this only ever *tells* and never cancels, and [ShizukuHealPolicy]
 * for what it does about it afterwards.
 */
object AdbdChurnNotice {

    private const val TAG = "CV:AdbdChurn"

    /**
     * Runs [block], which restarts `adbd`, then starts Shizuku again if the restart stopped a running
     * server — and tells the user either way.
     *
     * The check is taken **before** the block, because afterwards the evidence is gone — that is the
     * whole difficulty with this class of failure, and why the reporter could only describe it as
     * Shizuku "disabling automatically". It is also what keeps the heal honest: only a server that was
     * answering a moment ago is one CallVault stopped and owes a restart.
     *
     * The heal itself does not run here. [ShizukuRestarter.healAfterAdbdRestart] hands it to a thread
     * that queues behind [AdbShell.heavyOperationLock], so it can never get in front of the ADB work
     * this wraps, and [block]'s result reaches the caller with no added delay.
     *
     * @param what a short description of the operation, for the log.
     * @param underDialog true when a dialog on screen already warned the user; what it covered is not
     *   repeated, but what it could not know — that CallVault tried to start Shizuku again — is.
     */
    fun <T> around(context: Context, what: String, underDialog: Boolean = false, block: () -> T): T {
        val advice = ShizukuChurnPolicy.decide(
            shizukuServerRunning = runCatching { ShizukuBackend.isRunning() }.getOrDefault(false),
            underDialog = underDialog,
        )
        val result = block()
        when (advice) {
            ShizukuChurnAdvice.NOTHING -> Unit
            ShizukuChurnAdvice.WARN_FIRST, ShizukuChurnAdvice.TELL_AFTER -> {
                AppLogger.w(TAG, "$what restarted adbd, which stopped the Shizuku server that was running")
                runCatching {
                    ShizukuRestarter.healAfterAdbdRestart(
                        context,
                        what,
                        underDialog = advice == ShizukuChurnAdvice.WARN_FIRST,
                    )
                }.onFailure { AppLogger.w(TAG, "Could not start the Shizuku heal: ${it.message}") }
            }
        }
        return result
    }
}
