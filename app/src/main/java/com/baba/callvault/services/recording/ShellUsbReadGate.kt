/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.services.recording

/**
 * Decides, on each keep-alive tick and on a Wireless debugging change, whether to ask the recorder for USB debugging.
 *
 * Every read is a fork+exec of `settings get` in the shell-uid recorder, charged to "Android System". It used to run
 * every 60 s tick while the recorder was up; now only while [com.baba.callvault.integrations.adb.UsbDebuggingPolicy.needsShellRead]
 * says it can change the answer. When it stops being needed the last read is forgotten, so a value read under
 * different switches never passes as fresh later. Logs once per change of need, never per tick.
 */
internal class ShellUsbReadGate {

    data class Step(val read: Boolean, val forget: Boolean, val log: String?)

    private var wasNeeded: Boolean? = null

    @Synchronized
    fun decide(needed: Boolean): Step {
        val log = if (needed == wasNeeded) {
            null
        } else if (needed) {
            "USB debugging is hidden from the app and Wireless debugging (or an unreadable adbd) leaves it open; " +
                "asking the recorder each keep-alive tick"
        } else {
            "USB debugging is decided without the recorder's read (setting or adbd); not asking it on keep-alive ticks"
        }
        wasNeeded = needed
        return Step(read = needed, forget = !needed, log = log)
    }
}
