/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

/**
 * Waits for `adbd`'s loopback listener to come back after a `tcpip:` arm, instead of taking one look.
 *
 * ## Why
 *
 * Arming restarts `adbd`. The old code slept a fixed 2 s (`TCPIP_RESTART_WAIT_MS` +
 * `POST_DISCONNECT_WAIT_MS`), tried to connect **once**, and called the arm a failure if that one attempt
 * was refused. On an idle phone 2 s is enough — measured at 2.04 s on the OP12 once it had settled, and
 * the OP9 arms in the same time. **During boot it is not**, and the cost of guessing wrong is
 * disproportionate:
 *
 * - the round is written off as `PORT_DID_NOT_COME_UP`;
 * - the next round has no connection, so it goes back to mDNS — but `adbd` is in tcpip mode now, its TLS
 *   service advert is stale, and discovery burns its **full 12 s timeout** before giving up;
 * - three of those make one `ensureServerRunning`, which the boot path runs more than once;
 * - and every round borrows Wireless debugging again, so the switch visibly flickers on and off.
 *
 * Measured on the OP12, boot of 2026-09-19 17:04 (`docs/dev-notes/2026-09-19-reboot-deadlock-wd-off-by-user.md`):
 * the arm failed at 17:04:41, 17:04:58 and 17:05:15 before succeeding at 17:05:44 — **73 seconds** and
 * five or six Wireless-debugging cycles to do something that takes two seconds on a settled phone. Each
 * of those restarts `adbd`, and every `adbd` restart takes a running Shizuku server with it (R10).
 *
 * ## The rule, and the premise the device corrected
 *
 * ⚠️ The first version of this waited only while `service.adb.tcp.port` already read as our port, and gave
 * up at once when it did not — on the belief that `adbd` sets the property as it accepts `tcpip:`, so its
 * absence meant the request never landed. **The OP12 disproved that on the 17:28 boot**: two arms were
 * abandoned after **4 ms and 9 ms** because the property was still unset two seconds in, and one of those
 * same arms had plainly landed, because a later round found the listener up. The property is written when
 * `adbd` actually restarts and binds, and on a booting phone that takes longer than two seconds.
 * (`getSystemProperty` is in-process reflection on `android.os.SystemProperties`, so the reading was
 * honest — the premise was wrong, not the measurement.)
 *
 * So the property is treated as a **positive** signal only. Its absence is ambiguous early on and
 * meaningful later:
 *
 * - inside [graceMs] an unset property means "`adbd` has not restarted yet" — keep waiting;
 * - after it, an unset property means the request never landed — stop, rather than spending the budget;
 * - once it reads armed, keep polling to the full budget however long that takes.
 *
 * Free of Android types so every branch is tested.
 */
object LoopbackArmWait {

    /**
     * @param budgetMs how long to keep trying in total.
     * @param graceMs how long an unset `service.adb.tcp.port` is read as "`adbd` is still restarting"
     *   rather than "the arm never landed". Must be well over the restart a booting phone needs.
     * @param intervalMs pause between attempts.
     * @param now monotonic clock, in milliseconds.
     * @param sleep waits the given milliseconds.
     * @param isArmed reads `service.adb.tcp.port` and compares it to our port.
     * @param connect one attempt to reach the listener; true once it answers.
     * @return true when the listener answered within the budget.
     */
    fun awaitListener(
        budgetMs: Long,
        graceMs: Long,
        intervalMs: Long,
        now: () -> Long,
        sleep: (Long) -> Unit,
        isArmed: () -> Boolean,
        connect: () -> Boolean,
    ): Boolean {
        if (connect()) return true
        val startedAt = now()
        val deadline = startedAt + budgetMs
        while (now() < deadline) {
            // Re-read every round rather than once: the property may appear at any point during the
            // restart, and once it does this stops being a guess.
            if (!isArmed() && now() - startedAt >= graceMs) return false
            sleep(intervalMs)
            if (connect()) return true
        }
        return false
    }
}
