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
 * ## The rule
 *
 * `service.adb.tcp.port` is set by `adbd` as part of handling `tcpip:`, and **persists across the restart
 * it triggers** — that is the whole premise of off-Wi-Fi recording. So the property answers "did the arm
 * take?" long before a socket will answer "is it listening yet?". Keep retrying while the property says
 * armed; give up at once when it does not, because then the request never landed and waiting is just
 * delay. Free of Android types so every branch is tested.
 */
object LoopbackArmWait {

    /**
     * @param budgetMs how long to keep trying once the property says the arm took.
     * @param intervalMs pause between attempts.
     * @param now monotonic clock, in milliseconds.
     * @param sleep waits the given milliseconds.
     * @param isArmed reads `service.adb.tcp.port` and compares it to our port.
     * @param connect one attempt to reach the listener; true once it answers.
     * @return true when the listener answered within the budget.
     */
    fun awaitListener(
        budgetMs: Long,
        intervalMs: Long,
        now: () -> Long,
        sleep: (Long) -> Unit,
        isArmed: () -> Boolean,
        connect: () -> Boolean,
    ): Boolean {
        if (connect()) return true
        val deadline = now() + budgetMs
        while (now() < deadline) {
            // Checked every round, not once: an arm that did not take must not hold the caller for the
            // whole budget, and this is the only signal that tells that apart from "not listening yet".
            if (!isArmed()) return false
            sleep(intervalMs)
            if (connect()) return true
        }
        return false
    }
}
