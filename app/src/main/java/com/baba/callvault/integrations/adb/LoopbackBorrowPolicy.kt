/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

/**
 * Whether CallVault may **borrow** Wireless debugging for a few seconds to re-arm the off-Wi-Fi listener.
 *
 * ## Why this exists
 *
 * Respecting "the user turned Wireless debugging off" has always meant *never write the switch on*. On
 * 2026-09-19 that cost the maintainer's phone every call after a reboot, and it could not recover on its
 * own — see `docs/dev-notes/2026-09-19-reboot-deadlock-wd-off-by-user.md`. The shape of it:
 *
 * - a reboot always clears `service.adb.tcp.port`, so the off-Wi-Fi listener is gone on every boot;
 * - re-arming it needs an ADB connection;
 * - the only connection available is Wireless debugging;
 * - which was refused, because a flag saved before the reboot said the user had turned it off.
 *
 * The one condition that would have lifted the refusal — an armed listener — was the thing the refusal
 * prevented. Six launch attempts were refused in 107 ms and nothing tried again for as long as the phone
 * stayed up.
 *
 * ## Why borrowing is not overriding
 *
 * The switch ends up **exactly where the user left it**. CallVault turns it on, arms the listener, and
 * [AdbShell.releaseWirelessDebugging] turns it straight back off when the last ADB user finishes — the
 * same lease every other caller goes through. The user's preference is not cleared, so the next boot
 * borrows again rather than assuming it now owns the switch.
 *
 * ## Why all three conditions
 *
 * - **[offlineRecordingOn]** — the user turned off-Wi-Fi recording *on*. That setting cannot work without
 *   the listener, and the listener cannot exist without this one write. Refusing it does not respect the
 *   user; it silently contradicts a different thing they asked for. A user who has *not* enabled off-Wi-Fi
 *   recording and has turned Wireless debugging off is asking for the app to stop, and it stops.
 * - **[usbDebuggingOn]** — without it `adbd` dies with Wireless debugging and the listener would go with
 *   it, so the borrow would achieve nothing. It is also what makes the switch safe to give back.
 * - **[loopbackArmed] false** — this is the guard that keeps 2026-09-14's fix intact. Back then CallVault
 *   switched the user's Wireless debugging back on 50 ms after their tap, so it could not be turned off at
 *   all (measured on the OP9). With a listener already armed there is another way in, nothing needs
 *   recovering, and the user's tap is simply obeyed.
 */
object LoopbackBorrowPolicy {

    fun mayBorrow(
        offlineRecordingOn: Boolean,
        usbDebuggingOn: Boolean,
        loopbackArmed: Boolean,
    ): Boolean = offlineRecordingOn && usbDebuggingOn && !loopbackArmed
}
