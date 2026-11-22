/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

/**
 * Which waits are pointless while `init.svc.adbd` reads stopped (GH89-4, C65).
 *
 * `service.adb.tcp.port` persists across adbd restarts, so an armed loopback port looks "about to come back"
 * even when adbd is stopped and nothing will bring it back (the Honor BVL-N49 sat like that for ~48 h). The
 * 12 s self-heal wait, the mDNS search and the mDNS re-arm all wait for a listener that cannot appear; the
 * thing to do is the revival decision ([AdbdRevivalPolicy]), which is where the launcher goes instead.
 */
internal object StoppedAdbdWaits {
    /** Waiting for the loopback listener to reappear after an adbd restart. */
    fun mayWaitForLoopbackSelfHeal(loopbackArmed: Boolean, adbd: AdbdState): Boolean =
        loopbackArmed && adbd != AdbdState.STOPPED

    /** Searching mDNS for a Wireless-debugging connect port, or re-arming through it. */
    fun maySearchOrRearmViaMdns(adbd: AdbdState): Boolean = adbd != AdbdState.STOPPED
}
