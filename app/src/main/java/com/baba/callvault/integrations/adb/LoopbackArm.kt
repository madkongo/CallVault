/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.integrations.adb

/**
 * How arming the off-Wi-Fi loopback listener ended, in the terms a user can act on.
 *
 * One boolean used to stand for all of these, and the screen printed the same sentence whichever had
 * happened: "connect to Wi-Fi once, then try from Settings". mirror176 read that in #30 while on Wi-Fi
 * with Wireless debugging already on (#30). A failure that names the wrong cause is worse than one that
 * admits it does not know.
 */
enum class LoopbackArm {
    /** The listener is up; recording works off Wi-Fi until the next reboot. */
    ARMED,

    /** Wireless debugging is off and CallVault could not switch it on — the user has to. */
    NEEDS_WIRELESS_DEBUGGING,

    /** USB debugging is off, so off-Wi-Fi recording could never work; nothing was armed. */
    NEEDS_USB_DEBUGGING,

    /** Not on Wi-Fi, so Wireless debugging cannot run to arm through. */
    NO_WIFI,

    /** Android switched Wireless debugging back off after CallVault turned it on — an untrusted network. */
    WIRELESS_DEBUGGING_REFUSED,

    /** Wireless debugging is on, but no adb service could be reached to arm through. */
    NO_ADB_SERVICE,

    /** The listener was armed but the port did not come back up in time. Usually worth retrying. */
    PORT_DID_NOT_COME_UP,
}
